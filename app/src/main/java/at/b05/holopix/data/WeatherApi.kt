package at.b05.holopix.data

import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DayForecast(
    val date: LocalDate,
    val code: Int,
    val max: Double,
    val min: Double,
    val rainProb: Int,
    val text: String? = null,
)

data class HourForecast(val time: LocalDateTime, val code: Int, val temp: Double, val rainProb: Int, val isDay: Boolean)

data class Weather(
    val city: String,
    val temp: Double,
    val humidity: Int,
    val wind: Double,
    val code: Int,
    val sunrise: String,
    val sunset: String,
    val days: List<DayForecast>,
    val feelsLike: Double = temp,
    val uv: Double? = null,
    val isDay: Boolean = true,
    /** Beschreibung vom Anbieter, sonst aus dem Code abgeleitet. */
    val text: String? = null,
    val hours: List<HourForecast> = emptyList(),
    val source: String = "Open-Meteo",
) {
    val description: String get() = text ?: WeatherApi.describe(code)
}

/**
 * Wetter über Open-Meteo (kostenlos, kein API-Key), WMO-Wettercodes.
 */
class WeatherApi {

    data class Place(val name: String, val lat: Double, val lon: Double)

    suspend fun geocode(name: String): Place {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=de&name=" + URLEncoder.encode(name, "UTF-8")
        val o = JSONObject(Request.Builder().url(url).build().fetch())
        val r = o.optJSONArray("results")?.optJSONObject(0) ?: throw IOException("Ort „$name“ nicht gefunden")
        return Place(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"))
    }

    // ---------------- Open-Meteo ----------------

    suspend fun forecast(city: String, lat: Double, lon: Double): Weather {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,is_day&forecast_hours=25" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max,uv_index_max" +
            "&timezone=auto&forecast_days=7"
        val o = JSONObject(Request.Builder().url(url).build().fetch())
        val c = o.getJSONObject("current")
        val d = o.getJSONObject("daily")
        val dates = d.getJSONArray("time")
        val days = (0 until dates.length()).map {
            DayForecast(
                LocalDate.parse(dates.getString(it)),
                d.getJSONArray("weather_code").optInt(it),
                d.getJSONArray("temperature_2m_max").optDouble(it),
                d.getJSONArray("temperature_2m_min").optDouble(it),
                d.optJSONArray("precipitation_probability_max")?.optInt(it) ?: 0,
            )
        }
        val hours = o.optJSONObject("hourly")?.let { h ->
            val t = h.getJSONArray("time")
            (0 until t.length()).map {
                HourForecast(
                    LocalDateTime.parse(t.getString(it)),
                    h.getJSONArray("weather_code").optInt(it),
                    h.getJSONArray("temperature_2m").optDouble(it),
                    h.optJSONArray("precipitation_probability")?.optInt(it) ?: 0,
                    h.optJSONArray("is_day")?.optInt(it, 1) != 0,
                )
            }.filter { !it.time.isBefore(LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)) }.take(24)
        } ?: emptyList()
        fun hm(s: String) = s.substringAfter('T', s).take(5)
        return Weather(
            city = city,
            temp = c.optDouble("temperature_2m"),
            humidity = c.optInt("relative_humidity_2m"),
            wind = c.optDouble("wind_speed_10m"),
            code = c.optInt("weather_code"),
            sunrise = hm(d.getJSONArray("sunrise").optString(0)),
            sunset = hm(d.getJSONArray("sunset").optString(0)),
            days = days,
            feelsLike = c.optDouble("apparent_temperature", c.optDouble("temperature_2m")),
            uv = d.optJSONArray("uv_index_max")?.optDouble(0)?.takeIf { !it.isNaN() },
            isDay = c.optInt("is_day", 1) != 0,
            hours = hours,
            source = "Open-Meteo",
        )
    }

    companion object {
        fun describe(code: Int): String = when (code) {
            0 -> "sonnig"
            1 -> "überwiegend sonnig"
            2 -> "leicht bewölkt"
            3 -> "bewölkt"
            45, 48 -> "Nebel"
            51, 53, 55, 56, 57 -> "Nieselregen"
            61, 63, 65, 66, 67, 80, 81, 82 -> "Regen"
            71, 73, 75, 77, 85, 86 -> "Schnee"
            95, 96, 99 -> "Gewitter"
            else -> "–"
        }

    }
}
