package app.orchard.tv.weather

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.net.URLEncoder
import java.util.Locale
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlin.math.roundToInt

enum class WeatherKind { Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Thunder }

/** [high] and [low] are today's, shown under the large clock; null in a reading saved by an older version. */
data class WeatherNow(val celsius: Int, val kind: WeatherKind, val day: Boolean, val high: Int? = null, val low: Int? = null)

data class WeatherHourly(
    val time: String,
    val isNow: Boolean,
    val celsius: Int,
    val kind: WeatherKind,
    val day: Boolean,
)

data class WeatherDaily(
    val date: String,
    val dayIndex: Int,
    val high: Int,
    val low: Int,
    val kind: WeatherKind,
)

data class WeatherForecast(
    val now: WeatherNow,
    val hourly: List<WeatherHourly>,
    val daily: List<WeatherDaily>,
)

/**
 * The weather beside the home clock, from Open-Meteo: no key, reachable from China. The place is
 * the one the owner names on the phone page, or else a guess from the network's public address.
 * The guess is often wrong behind a proxy (a whole home behind one shows another country), which
 * is why the phone page shows it and lets the owner correct it.
 *
 * Fetched when Home comes to the front, at most once an hour; nothing runs while the TV is idle.
 * Compose state, written on the main thread only.
 */
object Weather {
    private const val TAG = "AerialWeather"
    private const val PREFS = "weather"
    private const val REFRESH_MS = 30 * 60 * 1000L // 30-60 min auto-refresh
    /** After a failed reading; only checked when Home comes to the front (issue #1576). */
    private const val RETRY_MS = 3 * 60 * 1000L
    private const val RELOCATE_MS = 24 * 60 * 60 * 1000L
    /** A reading older than this is not shown at all. */
    private const val STALE_MS = 3 * 60 * 60 * 1000L
    /** Stands for "no high or low" in the prefs. */
    private const val NONE = Int.MIN_VALUE

    var now by mutableStateOf<WeatherNow?>(null); private set
    var hourly by mutableStateOf<List<WeatherHourly>>(emptyList()); private set
    var daily by mutableStateOf<List<WeatherDaily>>(emptyList()); private set
    var refreshing by mutableStateOf(false); private set

    private var rawPlace by mutableStateOf<String?>(null)

    /** Where the weather is for, as a name to show; null until known. */
    val place: String?
        get() = rawPlace?.let { if (Locale.getDefault().language == "ru") RussianCities.translate(it) else it }

    /** True when the owner named the place; false while it is guessed from the network. */
    var chosen by mutableStateOf(false); private set

    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var lastAttempt = 0L
    private var job: Job? = null

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        rawPlace = prefs.getString("place", null)
        chosen = prefs.getBoolean("chosen", false)
        if (rawPlace == null && Locale.getDefault().language == "ru") {
            rawPlace = "Сыктывкар"
            prefs.edit {
                putString("place", "Сыктывкар")
                if (prefs.getFloat("lat", Float.NaN).isNaN()) {
                    putFloat("lat", 61.6688f)
                    putFloat("lon", 50.8355f)
                }
            }
        }
        if (System.currentTimeMillis() - prefs.getLong("fetched_at", 0) < STALE_MS) {
            val kind = runCatching { WeatherKind.valueOf(prefs.getString("kind", null) ?: "") }.getOrNull()
            if (kind != null) now = WeatherNow(
                prefs.getInt("celsius", 0), kind, prefs.getBoolean("day", true),
                high = prefs.getInt("high", NONE).takeIf { it != NONE },
                low = prefs.getInt("low", NONE).takeIf { it != NONE },
            )
        }
        val hJson = prefs.getString("hourly_json", null)
        if (!hJson.isNullOrBlank()) {
            hourly = runCatching {
                val arr = org.json.JSONArray(hJson)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    WeatherHourly(
                        time = o.getString("time"),
                        isNow = o.optBoolean("isNow", false),
                        celsius = o.getInt("celsius"),
                        kind = WeatherKind.valueOf(o.getString("kind")),
                        day = o.getBoolean("day"),
                    )
                }
            }.getOrDefault(emptyList())
        }
        val dJson = prefs.getString("daily_json", null)
        if (!dJson.isNullOrBlank()) {
            daily = runCatching {
                val arr = org.json.JSONArray(dJson)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    WeatherDaily(
                        date = o.getString("date"),
                        dayIndex = o.getInt("dayIndex"),
                        high = o.getInt("high"),
                        low = o.getInt("low"),
                        kind = WeatherKind.valueOf(o.getString("kind")),
                    )
                }
            }.getOrDefault(emptyList())
        }
        startPeriodic()
    }

    private var periodicJob: Job? = null

    private fun startPeriodic() {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L) // check every 5 minutes
                refreshIfStale()
            }
        }
    }

    private var failed = false

    /** Like the daily photos: a failed reading is retried when the network connects (issue #129). */
    fun refreshIfStale(networkCameUp: Boolean = false) {
        if (!started) return
        val since = System.currentTimeMillis() - lastAttempt
        if (since >= REFRESH_MS || (failed && (networkCameUp || since >= RETRY_MS))) refresh()
    }

    private var lastResult = "none"

    /**
     * For the diagnostics report: weather gone after an update and back only once Aerial was
     * restarted, with nothing in the log (MiTV-MFTR0 1.86 -> 1.96 and Xiaomi TV 2, issue #1506).
     * No place name or coordinates: the report is posted in public.
     */
    fun diagnostics(): String {
        if (!started) return "not started in this process"
        fun ago(at: Long) = if (at == 0L) "never" else "${(System.currentTimeMillis() - at) / 60_000} min ago"
        return "shown=${now != null} chosen=$chosen located=${!prefs.getFloat("lat", Float.NaN).isNaN()}; " +
            "read ${ago(prefs.getLong("fetched_at", 0))}; attempt ${ago(lastAttempt)}, result=$lastResult, " +
            "inFlight=${job?.isActive == true}"
    }

    fun refresh() {
        val since = System.currentTimeMillis() - lastAttempt
        if (refreshing && since < 3_000L) return
        lastAttempt = System.currentTimeMillis()
        lastResult = "pending"
        refreshing = true
        job?.cancel()
        job = scope.launch {
            try {
                var why: String? = null
                val reading = withContext(Dispatchers.IO) {
                    runCatching { fetch() }.onFailure { Log.w(TAG, "weather unavailable", it); why = reasonOf(it) }.getOrNull()
                }
                prefs.getString("place", null)?.let { rawPlace = it }
                failed = reading == null
                lastResult = if (reading != null) "success" else "failed ($why)"
                if (reading != null) {
                    Log.i(TAG, "weather read")
                    now = reading.now
                    hourly = reading.hourly
                    daily = reading.daily
                    prefs.edit {
                        putLong("fetched_at", System.currentTimeMillis())
                        putInt("celsius", reading.now.celsius)
                        putString("kind", reading.now.kind.name)
                        putBoolean("day", reading.now.day)
                        putInt("high", reading.now.high ?: NONE)
                        putInt("low", reading.now.low ?: NONE)
                        val hArr = org.json.JSONArray()
                        reading.hourly.forEach { h ->
                            val o = JSONObject()
                            o.put("time", h.time)
                            o.put("isNow", h.isNow)
                            o.put("celsius", h.celsius)
                            o.put("kind", h.kind.name)
                            o.put("day", h.day)
                            hArr.put(o)
                        }
                        putString("hourly_json", hArr.toString())
                        val dArr = org.json.JSONArray()
                        reading.daily.forEach { d ->
                            val o = JSONObject()
                            o.put("date", d.date)
                            o.put("dayIndex", d.dayIndex)
                            o.put("high", d.high)
                            o.put("low", d.low)
                            o.put("kind", d.kind.name)
                            dArr.put(o)
                        }
                        putString("daily_json", dArr.toString())
                    }
                }
            } finally {
                refreshing = false
            }
        }
    }

    /**
     * Why a reading failed, for the diagnostics: the HTTP status or the exception's kind, never its
     * message, which can hold the URL and so the coordinates. Two 1.97 reports showed only
     * "failed" for hours while our TVs read the weather (issues #1538 #1548).
     */
    private fun reasonOf(e: Throwable): String = (e as? HttpStatus)?.message ?: e.javaClass.simpleName

    private class HttpStatus(code: Int) : java.io.IOException("HTTP $code")

    /** Off the main thread. */
    private fun fetch(): WeatherForecast {
        var lat = prefs.getFloat("lat", Float.NaN)
        var lon = prefs.getFloat("lon", Float.NaN)
        val guessedLongAgo = System.currentTimeMillis() - prefs.getLong("located_at", 0) > RELOCATE_MS
        if (prefs.getBoolean("chosen", false) && !lat.isNaN() && !prefs.getBoolean("checked_against_cities", false)) {
            correctChosenPlace(lat, lon)?.let { lat = it.lat.toFloat(); lon = it.lon.toFloat() }
        }
        if (!prefs.getBoolean("chosen", false) && (lat.isNaN() || guessedLongAgo)) {
            val guess = locate()
            if (guess != null) {
                lat = guess.lat
                lon = guess.lon
                val city = guess.city
                val zh = city?.takeIf { guess.cn && Locale.getDefault().language == "zh" }
                    ?.let {
                        (Cities.near(app, it, lat.toDouble(), lon.toDouble(), km = 80.0, ascii = true)
                            ?: Cities.mainNear(app, lat.toDouble(), lon.toDouble(), km = 20.0))?.name
                    }
                val ru = city?.takeIf { Locale.getDefault().language == "ru" }
                    ?.let { RussianCities.findCity(lat, lon, it) }
                val resolvedPlace = ru ?: zh ?: city
                prefs.edit {
                    putFloat("lat", lat); putFloat("lon", lon)
                    putString("place", resolvedPlace)
                    putLong("located_at", System.currentTimeMillis())
                }
            }
        }
        if (lat.isNaN() || lon.isNaN()) {
            if (Locale.getDefault().language == "ru") {
                lat = 61.6688f
                lon = 50.8355f
                if (rawPlace == null) rawPlace = "Сыктывкар"
                prefs.edit { putFloat("lat", lat); putFloat("lon", lon); putString("place", "Сыктывкар") }
            } else {
                lat = 39.9042f
                lon = 116.4074f
            }
        }
        val isRussian = Locale.getDefault().language == "ru"
        if (isRussian) {
            val queryCity = rawPlace ?: "\u0421\u044b\u043a\u0442\u044b\u0432\u043a\u0430\u0440"
            return runCatching { fetchMailRu(queryCity) }.getOrElse { errMailRu ->
                Log.w(TAG, "Mail.ru failed, falling back to wttr.in", errMailRu)
                runCatching { fetchWttr(lat, lon) }.getOrElse { errWttr ->
                    Log.w(TAG, "wttr.in fallback failed", errWttr)
                    fetchOpenMeteo(lat, lon)
                }
            }
        }
        return runCatching { fetchOpenMeteo(lat, lon) }.getOrElse { errOm ->
            Log.w(TAG, "Open-Meteo failed, falling back to wttr.in", errOm)
            runCatching { fetchWttr(lat, lon) }.getOrElse { errWttr ->
                Log.w(TAG, "wttr.in fallback also failed", errWttr)
                throw errOm
            }
        }
    }

    private fun fetchOpenMeteo(lat: Float, lon: Float): WeatherForecast {
        val json = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=7&timezone=auto"))
        val current = json.getJSONObject("current")
        val dailyObj = json.optJSONObject("daily")
        fun today(key: String) = dailyObj?.optJSONArray(key)?.optDouble(0)?.takeIf { !it.isNaN() }?.roundToInt()
        val now = WeatherNow(
            celsius = current.getDouble("temperature_2m").roundToInt(),
            kind = kindOf(current.getInt("weather_code")),
            day = current.optInt("is_day", 1) == 1,
            high = today("temperature_2m_max"),
            low = today("temperature_2m_min"),
        )

        val hourlyList = mutableListOf<WeatherHourly>()
        val hourlyObj = json.optJSONObject("hourly")
        if (hourlyObj != null) {
            val hTimes = hourlyObj.optJSONArray("time")
            val hTemps = hourlyObj.optJSONArray("temperature_2m")
            val hCodes = hourlyObj.optJSONArray("weather_code")
            val hDays = hourlyObj.optJSONArray("is_day")
            if (hTimes != null && hTemps != null && hCodes != null) {
                val curTime = current.optString("time", "")
                val curHourPrefix = if (curTime.length >= 13) curTime.take(13) else ""
                var startIdx = 0
                if (curHourPrefix.isNotEmpty()) {
                    for (i in 0 until hTimes.length()) {
                        if (hTimes.getString(i).startsWith(curHourPrefix)) {
                            startIdx = i
                            break
                        }
                    }
                }
                val endIdx = minOf(hTimes.length(), startIdx + 24)
                for (k in startIdx until endIdx) {
                    val tStr = hTimes.getString(k)
                    val hour = runCatching { tStr.substring(11, 16) }.getOrDefault(tStr)
                    val isNow = (k == startIdx)
                    val celsius = hTemps.getDouble(k).roundToInt()
                    val code = hCodes.getInt(k)
                    val isDay = hDays?.optInt(k, 1) == 1
                    hourlyList.add(WeatherHourly(time = hour, isNow = isNow, celsius = celsius, kind = kindOf(code), day = isDay))
                }
            }
        }

        val dailyList = mutableListOf<WeatherDaily>()
        if (dailyObj != null) {
            val dTimes = dailyObj.optJSONArray("time")
            val dCodes = dailyObj.optJSONArray("weather_code")
            val dMaxs = dailyObj.optJSONArray("temperature_2m_max")
            val dMins = dailyObj.optJSONArray("temperature_2m_min")
            if (dTimes != null && dCodes != null && dMaxs != null && dMins != null) {
                for (i in 0 until dTimes.length()) {
                    val dateStr = dTimes.getString(i)
                    val max = dMaxs.optDouble(i).roundToInt()
                    val min = dMins.optDouble(i).roundToInt()
                    val code = dCodes.optInt(i, 0)
                    dailyList.add(WeatherDaily(date = dateStr, dayIndex = i, high = max, low = min, kind = kindOf(code)))
                }
            }
        }

        return WeatherForecast(now = now, hourly = hourlyList, daily = dailyList)
    }

    private fun fetchWttr(lat: Float, lon: Float): WeatherForecast {
        val jsonStr = get("https://wttr.in/$lat,$lon?format=j1")
        val json = JSONObject(jsonStr)
        val cur = json.getJSONArray("current_condition").getJSONObject(0)
        val temp = cur.getString("temp_C").toIntOrNull() ?: 0
        val weatherCode = cur.optInt("weatherCode", 113)
        val weatherDays = json.optJSONArray("weather")
        val todayObj = weatherDays?.optJSONObject(0)
        val high = todayObj?.optString("maxtempC")?.toIntOrNull()
        val low = todayObj?.optString("mintempC")?.toIntOrNull()
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val now = WeatherNow(
            celsius = temp,
            kind = wttrKindOf(weatherCode),
            day = currentHour in 6..21,
            high = high,
            low = low,
        )
        val hourlyList = mutableListOf<WeatherHourly>()
        if (todayObj != null) {
            val hArr = todayObj.optJSONArray("hourly")
            if (hArr != null) {
                var closestIdx = 0
                var minDiff = Int.MAX_VALUE
                for (i in 0 until hArr.length()) {
                    val h = hArr.getJSONObject(i)
                    val tInt = h.optInt("time", 0) / 100
                    val diff = kotlin.math.abs(tInt - currentHour)
                    if (diff < minDiff) {
                        minDiff = diff
                        closestIdx = i
                    }
                }
                for (i in 0 until hArr.length()) {
                    val h = hArr.getJSONObject(i)
                    val tInt = h.optInt("time", 0) / 100
                    val timeStr = String.format(Locale.US, "%02d:00", tInt)
                    val hTemp = h.optString("tempC").toIntOrNull() ?: 0
                    val hCode = h.optInt("weatherCode", 113)
                    hourlyList.add(WeatherHourly(
                        time = timeStr,
                        isNow = (i == closestIdx),
                        celsius = hTemp,
                        kind = wttrKindOf(hCode),
                        day = tInt in 6..21
                    ))
                }
            }
        }
        val dailyList = mutableListOf<WeatherDaily>()
        if (weatherDays != null) {
            for (i in 0 until weatherDays.length()) {
                val d = weatherDays.getJSONObject(i)
                val max = d.optString("maxtempC").toIntOrNull() ?: 0
                val min = d.optString("mintempC").toIntOrNull() ?: 0
                val firstHCode = d.optJSONArray("hourly")?.optJSONObject(0)?.optInt("weatherCode", 113) ?: 113
                dailyList.add(WeatherDaily(
                    date = d.optString("date", ""),
                    dayIndex = i,
                    high = max,
                    low = min,
                    kind = wttrKindOf(firstHCode)
                ))
            }
        }
        return WeatherForecast(now = now, hourly = hourlyList, daily = dailyList)
    }

    private fun fetchMailRu(cityName: String): WeatherForecast {
        val q = URLEncoder.encode(cityName.trim(), "UTF-8")
        val suggestUrl = "https://pogoda.mail.ru/ajax/suggest/?q=$q"
        var pagePath: String? = null
        runCatching {
            val json = JSONObject(get(suggestUrl))
            val items = json.optJSONArray("items")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val grp = items.getJSONObject(i)
                    val subItems = grp.optJSONArray("items")
                    if (subItems != null) {
                        for (j in 0 until subItems.length()) {
                            val u = subItems.getJSONObject(j).optString("url")
                            if (u.isNotBlank()) {
                                pagePath = u
                                break
                            }
                        }
                    }
                    if (pagePath != null) break
                }
            }
        }.onFailure { Log.w(TAG, "Mail.ru suggest failed", it) }

        val targetUrl = if (pagePath != null) "https://pogoda.mail.ru$pagePath" else "https://pogoda.mail.ru/prognoz/syktyvkar/"
        val html = get(targetUrl)
        val p = html.indexOf("window.__PRELOADED_STATE__PAGE")
        if (p == -1) throw IOException("No preloaded state in Mail.ru page")
        val pEnd = html.indexOf("</script>", p)
        val raw = if (pEnd != -1) html.substring(p, pEnd) else html.substring(p)
        val eq = raw.indexOf('=')
        val jsonStr = raw.substring(eq + 1).trim().removeSuffix(";")
        val state = JSONObject(jsonStr).getJSONObject("data")

        val cur = state.getJSONObject("current")
        val temp = cur.optInt("tempe", 0)
        val iconCode = cur.optString("icon", "01")
        val resolvedCity = state.optJSONObject("city")?.optString("name")?.ifBlank { null }
        if (resolvedCity != null) {
            rawPlace = resolvedCity
            prefs.edit { putString("place", resolvedCity) }
        }

        val dailyList = mutableListOf<WeatherDaily>()
        val fItems = state.optJSONObject("forecast")?.optJSONArray("items")
        var todayHigh: Int? = null
        var todayLow: Int? = null
        if (fItems != null) {
            val count = minOf(fItems.length(), 7)
            for (i in 0 until count) {
                val it = fItems.getJSONObject(i)
                val high = it.optInt("temperature", 0)
                val low = it.optInt("tempe_night", 0)
                if (i == 0) {
                    todayHigh = high
                    todayLow = low
                }
                val dObj = it.optJSONObject("date")
                val dateStr = dObj?.optString("anchor")?.removePrefix("d-") ?: dObj?.optString("date_short") ?: "day-$i"
                val ic = it.optString("icon", "01")
                dailyList.add(WeatherDaily(
                    date = dateStr,
                    dayIndex = i,
                    high = high,
                    low = low,
                    kind = mailRuKindOf(ic)
                ))
            }
        }

        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val now = WeatherNow(
            celsius = temp,
            kind = mailRuKindOf(iconCode),
            day = currentHour in 6..21,
            high = todayHigh,
            low = todayLow,
        )

        val hourlyList = mutableListOf<WeatherHourly>()
        val hItems = state.optJSONArray("hforecast")
        if (hItems != null) {
            var isFirst = true
            for (i in 0 until hItems.length()) {
                val h = hItems.getJSONObject(i)
                if (h.has("type")) continue // Skip sunrise/sunset markers
                val timeStr = h.optString("time", "00:00")
                val tVal = h.optInt("temperature", h.optInt("tempe", 0))
                val ic = h.optString("icon", "01")
                val hHour = timeStr.split(':').firstOrNull()?.toIntOrNull() ?: 0
                hourlyList.add(WeatherHourly(
                    time = timeStr,
                    isNow = isFirst,
                    celsius = tVal,
                    kind = mailRuKindOf(ic),
                    day = hHour in 6..21
                ))
                isFirst = false
            }
        }

        return WeatherForecast(now = now, hourly = hourlyList, daily = dailyList)
    }

    private fun mailRuKindOf(icon: String): WeatherKind {
        val code = icon.toIntOrNull() ?: 1
        return when (code) {
            1, 2 -> WeatherKind.Clear
            3, 4, 5 -> WeatherKind.PartlyCloudy
            6, 7, 8 -> WeatherKind.Cloudy
            9, 10 -> WeatherKind.Fog
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20 -> WeatherKind.Rain
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30 -> WeatherKind.Snow
            31, 32, 33, 34, 35 -> WeatherKind.Thunder
            else -> WeatherKind.Cloudy
        }
    }

    private fun wttrKindOf(code: Int): WeatherKind = when (code) {
        113 -> WeatherKind.Clear
        116 -> WeatherKind.PartlyCloudy
        119, 122 -> WeatherKind.Cloudy
        143, 248, 260 -> WeatherKind.Fog
        200, 386, 389 -> WeatherKind.Thunder
        227, 230, 317, 320, 323, 326, 329, 332, 335, 338, 350, 368, 371, 374, 377, 392, 395 -> WeatherKind.Snow
        else -> WeatherKind.Rain
    }

    private class Guess(val lat: Float, val lon: Float, val city: String?, val cn: Boolean)

    private fun locate(): Guess? {
        // 1. Try ip-api.com (fast, no key, works great in Russia and worldwide)
        runCatching {
            val ip = JSONObject(get("http://ip-api.com/json"))
            if (ip.optString("status") == "success") {
                return Guess(
                    ip.getDouble("lat").toFloat(),
                    ip.getDouble("lon").toFloat(),
                    ip.optString("city").ifBlank { null },
                    ip.optString("countryCode").equals("CN", ignoreCase = true),
                )
            }
        }.onFailure { Log.w(TAG, "ip-api.com", it) }

        // 2. Try Sypex Geo (Russia dedicated geolocation)
        runCatching {
            val json = JSONObject(get("https://api.sypexgeo.net/json/"))
            val cityObj = json.optJSONObject("city")
            val countryObj = json.optJSONObject("country")
            if (cityObj != null) {
                val cityName = cityObj.optString("name_ru").ifBlank { cityObj.optString("name_en") }
                return Guess(
                    cityObj.getDouble("lat").toFloat(),
                    cityObj.getDouble("lon").toFloat(),
                    cityName.ifBlank { null },
                    countryObj?.optString("iso") == "CN",
                )
            }
        }.onFailure { Log.w(TAG, "sypexgeo", it) }

        // 3. Try ipwho.is
        val v4 = runCatching { get("https://ipv4.icanhazip.com").trim() }.getOrNull()
            ?.takeIf { Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(it) }
        runCatching {
            val ip = JSONObject(get(if (v4 != null) "https://ipwho.is/$v4" else "https://ipwho.is/"))
            if (ip.optBoolean("success", true)) {
                return Guess(
                    ip.getDouble("latitude").toFloat(),
                    ip.getDouble("longitude").toFloat(),
                    ip.optString("city").ifBlank { null },
                    ip.optString("country_code").equals("CN", ignoreCase = true),
                )
            }
        }.onFailure { Log.w(TAG, "ipwho.is", it) }

        // 4. Try ipinfo.io
        runCatching {
            val ip = JSONObject(get("https://ipinfo.io/json"))
            val loc = ip.getString("loc").split(',')
            return Guess(
                loc[0].toFloat(),
                loc[1].toFloat(),
                ip.optString("city").ifBlank { null },
                ip.optString("country") == "CN",
            )
        }.onFailure { Log.w(TAG, "ipinfo.io", it) }

        // 5. Fallback for Russian locale: default to Syktyvkar
        if (Locale.getDefault().language == "ru") {
            return Guess(61.6688f, 50.8355f, "Сыктывкар", false)
        }

        return null
    }

    private fun correctChosenPlace(lat: Float, lon: Float): Cities.City? {
        val name = prefs.getString("place", null)
        val city = name?.let { runCatching { Cities.find(app, it) }.getOrNull() }
            ?.takeIf { Cities.near(app, it.name, lat.toDouble(), lon.toDouble(), km = 30.0) == null }
        prefs.edit {
            putBoolean("checked_against_cities", true)
            if (city != null) {
                Log.i(TAG, "chosen place $name moved to the city of that name")
                putFloat("lat", city.lat.toFloat()); putFloat("lon", city.lon.toFloat())
            }
        }
        return city
    }

    suspend fun choose(name: String): Boolean {
        val hit = withContext(Dispatchers.IO) {
            RussianCities.findCoordinates(name)
                ?: runCatching { Cities.find(app, name) }.onFailure { Log.w(TAG, "could not read the city list", it) }.getOrNull()
                    ?.let { Triple(it.lat, it.lon, it.name) }
                ?: runCatching {
                    val q = URLEncoder.encode(name.trim(), "UTF-8")
                    val results = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&language=ru&format=json"))
                        .optJSONArray("results")
                    val all = (0 until (results?.length() ?: 0)).map { results!!.getJSONObject(it) }
                    fun seat(it: JSONObject) = it.optString("feature_code").let { c -> c.startsWith("PPLA") || c == "PPLC" }
                    val best = all.maxWithOrNull(compareBy<JSONObject>({ it.optLong("population") }, { seat(it) }, { -all.indexOf(it) }))
                    best?.let { Triple(it.getDouble("latitude"), it.getDouble("longitude"), it.optString("name").ifBlank { name.trim() }) }
                }.onFailure { Log.w(TAG, "could not look up $name", it) }.getOrNull()
        } ?: return false
        prefs.edit {
            putFloat("lat", hit.first.toFloat())
            putFloat("lon", hit.second.toFloat())
            putString("place", hit.third)
            putBoolean("chosen", true)
            putBoolean("checked_against_cities", true)
        }
        chosen = true
        rawPlace = prefs.getString("place", null)
        refresh()
        return true
    }

        fun useNetworkLocation() {
        prefs.edit { putBoolean("chosen", false); remove("lat"); remove("lon"); remove("located_at") }
        chosen = false
        refresh()
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    private fun kindOf(code: Int): WeatherKind = when (code) {
        0 -> WeatherKind.Clear
        1, 2 -> WeatherKind.PartlyCloudy
        3 -> WeatherKind.Cloudy
        45, 48 -> WeatherKind.Fog
        in 71..77, 85, 86 -> WeatherKind.Snow
        in 95..99 -> WeatherKind.Thunder
        else -> WeatherKind.Rain // drizzle, rain and showers: 51..67, 80..82
    }

    /**
     * Android before 7.1 does not trust the Let's Encrypt root these hosts use; they answer the
     * same over plain HTTP (allowed for them in network_security_config.xml). A handshake that
     * never answers goes the same way: MiTV-MFTR0 on 1.98 timed out in the TLS handshake to
     * open-meteo for hours, while a PC on the same network read it in a second (issue #1576, also
     * #1555 #1487 #1538).
     */
    private val trustAllSslSocketFactory by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        sslContext.socketFactory
    }

    private fun get(url: String): String = try {
        read(url)
    } catch (e: IOException) {
        if (e !is SSLException && e !is SocketTimeoutException) throw e
        read(url.replaceFirst("https://", "http://"))
    }

    private fun read(url: String, redirectCount: Int = 0): String {
        if (redirectCount > 5) throw IOException("Too many redirects: $url")
        val c = URL(url).openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) {
            c.sslSocketFactory = trustAllSslSocketFactory
            c.setHostnameVerifier { _, _ -> true }
        }
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        c.instanceFollowRedirects = true
        c.connectTimeout = 8_000
        c.readTimeout = 15_000
        return try {
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                if (!loc.isNullOrBlank()) {
                    val nextUrl = if (loc.startsWith("http://") || loc.startsWith("https://")) loc else URL(URL(url), loc).toString()
                    return read(nextUrl, redirectCount + 1)
                }
            }
            if (code !in 200..299) throw HttpStatus(code)
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }
}
