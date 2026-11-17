package app.orchard.tv.weather

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object RussianCities {
    private val DICTIONARY = mapOf(
        "Moscow" to "Москва",
        "Saint Petersburg" to "Санкт-Петербург",
        "St. Petersburg" to "Санкт-Петербург",
        "St Petersburg" to "Санкт-Петербург",
        "Novosibirsk" to "Новосибирск",
        "Yekaterinburg" to "Екатеринбург",
        "Ekaterinburg" to "Екатеринбург",
        "Kazan" to "Казань",
        "Nizhny Novgorod" to "Нижний Новгород",
        "Chelyabinsk" to "Челябинск",
        "Krasnoyarsk" to "Красноярск",
        "Samara" to "Самара",
        "Ufa" to "Уфа",
        "Rostov-on-Don" to "Ростов-на-Дону",
        "Rostov" to "Ростов-на-Дону",
        "Omsk" to "Омск",
        "Krasnodar" to "Краснодар",
        "Voronezh" to "Воронеж",
        "Perm" to "Пермь",
        "Volgograd" to "Волгоград",
        "Saratov" to "Саратов",
        "Tyumen" to "Тюмень",
        "Tolyatti" to "Тольятти",
        "Barnaul" to "Барнаул",
        "Izhevsk" to "Ижевск",
        "Makhachkala" to "Махачкала",
        "Khabarovsk" to "Хабаровск",
        "Ulyanovsk" to "Ульяновск",
        "Irkutsk" to "Иркутск",
        "Vladivostok" to "Владивосток",
        "Yaroslavl" to "Ярославль",
        "Sevastopol" to "Севастополь",
        "Stavropol" to "Ставрополь",
        "Orenburg" to "Оренбург",
        "Tomsk" to "Томск",
        "Kemerovo" to "Кемерово",
        "Novokuznetsk" to "Новокузнецк",
        "Ryazan" to "Рязань",
        "Astrakhan" to "Астрахань",
        "Naberezhnye Chelny" to "Набережные Челны",
        "Penza" to "Пенза",
        "Kirov" to "Киров",
        "Lipetsk" to "Липецк",
        "Cheboksary" to "Чебоксары",
        "Balashikha" to "Балашиха",
        "Kaliningrad" to "Калининград",
        "Tula" to "Тула",
        "Kursk" to "Курск",
        "Sochi" to "Сочи",
        "Ulan-Ude" to "Улан-Удэ",
        "Tver" to "Тверь",
        "Magnitogorsk" to "Магнитогорск",
        "Ivanovo" to "Иваново",
        "Bryansk" to "Брянск",
        "Belgorod" to "Белгород",
        "Surgut" to "Сургут",
        "Vladimir" to "Владимир",
        "Chita" to "Чита",
        "Arkhangelsk" to "Архангельск",
        "Simferopol" to "Симферополь",
        "Kaluga" to "Калуга",
        "Smolensk" to "Смоленск",
        "Volzhsky" to "Волжский",
        "Yakutsk" to "Якутск",
        "Saransk" to "Саранск",
        "Cherepovets" to "Череповец",
        "Vologda" to "Вологда",
        "Vladikavkaz" to "Владикавказ",
        "Grozny" to "Грозный",
        "Murmansk" to "Мурманск",
        "Tambov" to "Тамбов",
        "Petrozavodsk" to "Петрозаводск",
        "Kostroma" to "Кострома",
        "Nizhnevartovsk" to "Нижневартовск",
        "Novorossiysk" to "Новороссийск",
        "Yoshkar-Ola" to "Йошкар-Ола",
        "Taganrog" to "Таганрог",
        "Komsomolsk-on-Amur" to "Комсомольск-на-Амуре",
        "Syktyvkar" to "Сыктывкар",
        "Nalchik" to "Нальчик",
        "Nizhnekamsk" to "Нижнекамск",
        "Shakhty" to "Шахты",
        "Dzerzhinsk" to "Дзержинск",
        "Bratsk" to "Братск",
        "Orsk" to "Орск",
        "Angarsk" to "Ангарск",
        "Engels" to "Энгельс",
        "Blagoveshchensk" to "Благовещенск",
        "Stary Oskol" to "Старый Оскол",
        "Veliky Novgorod" to "Великий Новгород",
        "Korolyov" to "Королёв",
        "Korolev" to "Королёв",
        "Pskov" to "Псков",
        "Mytishchi" to "Мытищи",
        "Biysk" to "Бийск",
        "Lyubertsy" to "Люберцы",
        "Prokopyevsk" to "Прокопьевск",
        "Yuzhno-Sakhalinsk" to "Южно-Сахалинск",
        "Abakan" to "Абакан",
        "Armavir" to "Армавир",
        "Norilsk" to "Норильск",
        "Petropavlovsk-Kamchatsky" to "Петропавловск-Камчатский",
        "Zlatoust" to "Златоуст",
        "Ussuriysk" to "Уссурийск",
        "Kamensk-Uralsky" to "Каменск-Уральский",
        "Novocherkassk" to "Новочеркасск",
        "Khimki" to "Химки",
        "Podolsk" to "Подольск",
        "Krasnogorsk" to "Красногорск",
        "Minsk" to "Минск",
        "Almaty" to "Алматы",
        "Astana" to "Астана",
        "Tashkent" to "Ташкент",
        "Baku" to "Баку",
        "Yerevan" to "Ереван",
        "Tbilisi" to "Тбилиси",
        "Bishkek" to "Бишкек",
        "Dushanbe" to "Душанбе",
        "Ashgabat" to "Ашхабад",
        "London" to "Лондон",
        "Paris" to "Париж",
        "New York" to "Нью-Йорк",
        "Berlin" to "Берлин",
        "Rome" to "Рим",
        "Madrid" to "Мадрид",
        "Tokyo" to "Токио",
        "Beijing" to "Пекин",
        "Dubai" to "Дубай",
        "Istanbul" to "Стамбул",
    )

    data class CityGeo(val ru: String, val en: String, val lat: Double, val lon: Double)

    val GEO_CITIES = listOf(
        CityGeo("Сыктывкар", "Syktyvkar", 61.6688, 50.8355),
        CityGeo("Москва", "Moscow", 55.7558, 37.6173),
        CityGeo("Санкт-Петербург", "Saint Petersburg", 59.9343, 30.3351),
        CityGeo("Новосибирск", "Novosibirsk", 55.0084, 82.9357),
        CityGeo("Екатеринбург", "Yekaterinburg", 56.8389, 60.6057),
        CityGeo("Казань", "Kazan", 55.7887, 49.1221),
        CityGeo("Нижний Новгород", "Nizhny Novgorod", 56.2965, 43.9361),
        CityGeo("Челябинск", "Chelyabinsk", 55.1644, 61.4368),
        CityGeo("Красноярск", "Krasnoyarsk", 56.0153, 92.8932),
        CityGeo("Самара", "Samara", 53.1959, 50.1002),
        CityGeo("Уфа", "Ufa", 54.7388, 55.9721),
        CityGeo("Ростов-на-Дону", "Rostov-on-Don", 47.2357, 39.7015),
        CityGeo("Омск", "Omsk", 54.9885, 73.3242),
        CityGeo("Краснодар", "Krasnodar", 45.0355, 38.9753),
        CityGeo("Воронеж", "Voronezh", 51.6720, 39.1843),
        CityGeo("Пермь", "Perm", 58.0105, 56.2502),
        CityGeo("Волгоград", "Volgograd", 48.7080, 44.5133),
        CityGeo("Саратов", "Saratov", 51.5406, 46.0086),
        CityGeo("Тюмень", "Tyumen", 57.1613, 65.5250),
        CityGeo("Тольятти", "Tolyatti", 53.5088, 49.4192),
        CityGeo("Барнаул", "Barnaul", 53.3606, 83.7636),
        CityGeo("Ижевск", "Izhevsk", 56.8498, 53.2045),
        CityGeo("Махачкала", "Makhachkala", 42.9849, 47.5047),
        CityGeo("Хабаровск", "Khabarovsk", 48.4827, 135.0838),
        CityGeo("Ульяновск", "Ulyanovsk", 54.3142, 48.4031),
        CityGeo("Иркутск", "Irkutsk", 52.2870, 104.3050),
        CityGeo("Владивосток", "Vladivostok", 43.1198, 131.8869),
        CityGeo("Ярославль", "Yaroslavl", 57.6261, 39.8845),
        CityGeo("Севастополь", "Sevastopol", 44.6167, 33.5254),
        CityGeo("Ставрополь", "Stavropol", 45.0428, 41.9734),
        CityGeo("Оренбург", "Orenburg", 51.7727, 55.0988),
        CityGeo("Томск", "Tomsk", 56.4977, 84.9744),
        CityGeo("Кемерово", "Kemerovo", 55.3547, 86.0884),
        CityGeo("Новокузнецк", "Novokuznetsk", 53.7596, 87.1216),
        CityGeo("Рязань", "Ryazan", 54.6295, 39.7424),
        CityGeo("Астрахань", "Astrakhan", 46.3497, 48.0408),
        CityGeo("Набережные Челны", "Naberezhnye Chelny", 55.7437, 52.4173),
        CityGeo("Пенза", "Penza", 53.2007, 45.0046),
        CityGeo("Киров", "Kirov", 58.6035, 49.6679),
        CityGeo("Липецк", "Lipetsk", 52.6103, 39.5947),
        CityGeo("Чебоксары", "Cheboksary", 56.1439, 47.2489),
        CityGeo("Калининград", "Kaliningrad", 54.7104, 20.4522),
        CityGeo("Тула", "Tula", 54.1961, 37.6182),
        CityGeo("Курск", "Kursk", 51.7304, 36.1927),
        CityGeo("Сочи", "Sochi", 43.6028, 39.7342),
        CityGeo("Улан-Удэ", "Ulan-Ude", 51.8348, 107.5845),
        CityGeo("Тверь", "Tver", 56.8587, 35.9176),
        CityGeo("Магнитогорск", "Magnitogorsk", 53.4129, 58.9798),
        CityGeo("Иваново", "Ivanovo", 56.9972, 40.9714),
        CityGeo("Брянск", "Bryansk", 53.2521, 34.3717),
        CityGeo("Белгород", "Belgorod", 50.5997, 36.5983),
        CityGeo("Сургут", "Surgut", 61.2540, 73.3962),
        CityGeo("Владимир", "Vladimir", 56.1366, 40.3966),
        CityGeo("Чита", "Chita", 52.0317, 113.5009),
        CityGeo("Архангельск", "Arkhangelsk", 64.5401, 40.5433),
        CityGeo("Симферополь", "Simferopol", 44.9572, 34.1108),
        CityGeo("Калуга", "Kaluga", 54.5293, 36.2754),
        CityGeo("Смоленск", "Smolensk", 54.7818, 32.0401),
        CityGeo("Якутск", "Yakutsk", 62.0355, 129.6755),
        CityGeo("Саранск", "Saransk", 54.1838, 45.1838),
        CityGeo("Череповец", "Cherepovets", 59.1323, 37.9092),
        CityGeo("Вологда", "Vologda", 59.2205, 39.8915),
        CityGeo("Владикавказ", "Vladikavkaz", 43.0367, 44.6678),
        CityGeo("Грозный", "Grozny", 43.3178, 45.6986),
        CityGeo("Мурманск", "Murmansk", 68.9585, 33.0827),
        CityGeo("Тамбов", "Tambov", 52.7317, 41.4433),
        CityGeo("Петрозаводск", "Petrozavodsk", 61.7849, 34.3469),
        CityGeo("Кострома", "Kostroma", 57.7665, 40.9269),
        CityGeo("Нижневартовск", "Nizhnevartovsk", 60.9385, 76.5589),
        CityGeo("Новороссийск", "Novorossiysk", 44.7154, 37.7619),
        CityGeo("Йошкар-Ола", "Yoshkar-Ola", 56.6344, 47.8999),
        CityGeo("Нальчик", "Nalchik", 43.4853, 43.6071),
        CityGeo("Псков", "Pskov", 57.8136, 28.3496),
        CityGeo("Великий Новгород", "Veliky Novgorod", 58.5228, 31.2699),
        CityGeo("Южно-Сахалинск", "Yuzhno-Sakhalinsk", 46.9541, 142.7360),
        CityGeo("Петропавловск-Камчатский", "Petropavlovsk-Kamchatsky", 53.0370, 158.6559),
        CityGeo("Норильск", "Norilsk", 69.3558, 88.1893),
        CityGeo("Минск", "Minsk", 53.9006, 27.5590),
        CityGeo("Алматы", "Almaty", 43.2220, 76.8512),
        CityGeo("Астана", "Astana", 51.1694, 71.4491),
        CityGeo("Ташкент", "Tashkent", 41.2995, 69.2401),
        CityGeo("Баку", "Baku", 40.4093, 49.8671),
        CityGeo("Ереван", "Yerevan", 40.1792, 44.4991),
        CityGeo("Тбилиси", "Tbilisi", 41.7151, 44.8271),
    )

    fun findCoordinates(name: String?): Triple<Double, Double, String>? {
        if (name.isNullOrBlank()) return null
        val q = name.trim().lowercase()
        GEO_CITIES.firstOrNull { it.ru.lowercase() == q || it.en.lowercase() == q }
            ?.let { return Triple(it.lat, it.lon, it.ru) }
        GEO_CITIES.firstOrNull { it.ru.lowercase().startsWith(q) || q.startsWith(it.ru.lowercase()) }
            ?.let { return Triple(it.lat, it.lon, it.ru) }
        return null
    }

    fun translate(name: String?): String? {
        if (name == null) return null
        val trimmed = name.trim()
        DICTIONARY[trimmed]?.let { return it }
        DICTIONARY.entries.firstOrNull { it.key.equals(trimmed, ignoreCase = true) }?.value?.let { return it }
        return name
    }

    /** Geocode via BigDataCloud or Open-Meteo off the main thread. */
    fun findCity(lat: Float, lon: Float, englishCity: String?): String? {
        translate(englishCity)?.takeIf { it != englishCity }?.let { return it }

        val bdc = runCatching {
            val url = URL("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=ru")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.inputStream.bufferedReader().use { r ->
                val json = JSONObject(r.readText())
                json.optString("city").ifBlank { json.optString("locality").ifBlank { null } }
            }
        }.getOrNull()
        if (!bdc.isNullOrBlank()) return bdc

        if (!englishCity.isNullOrBlank()) {
            val om = runCatching {
                val url = URL("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(englishCity, "UTF-8")}&count=1&language=ru")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.inputStream.bufferedReader().use { r ->
                    val json = JSONObject(r.readText())
                    json.optJSONArray("results")?.optJSONObject(0)?.optString("name")?.ifBlank { null }
                }
            }.getOrNull()
            if (!om.isNullOrBlank()) return om
        }

        return englishCity
    }
}
