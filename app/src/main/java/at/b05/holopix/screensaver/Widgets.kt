package at.b05.holopix.screensaver

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import at.b05.holopix.data.Weather

/** Wetter-Symbol zum WMO-Code (Open-Meteo). */
fun weatherIcon(code: Int, isDay: Boolean = true): ImageVector = when (code) {
    0, 1 -> if (isDay) Icons.Rounded.WbSunny else Icons.Rounded.NightsStay
    2 -> if (isDay) Icons.Rounded.WbCloudy else Icons.Rounded.NightsStay
    3, 45, 48 -> Icons.Rounded.Cloud
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Icons.Rounded.WaterDrop
    71, 73, 75, 77, 85, 86 -> Icons.Rounded.AcUnit
    95, 96, 99 -> Icons.Rounded.Thunderstorm
    else -> Icons.Rounded.Cloud
}

/** Wetter-Zeile: „6 °C · leicht bewölkt · 2° / 9° · Regen 40 %“. */
fun weatherLine(w: Weather): Pair<ImageVector, String> {
    val today = w.days.firstOrNull()
    val parts = mutableListOf("${Math.round(w.temp)} °C", w.description)
    if (today != null) {
        parts += "${Math.round(today.min)}° / ${Math.round(today.max)}°"
        if (today.rainProb >= 20) parts += "Regen ${today.rainProb} %"
    }
    return weatherIcon(w.code, w.isDay) to parts.joinToString(" · ")
}

/** Einkaufsliste kompakt: „Milch, Eier, Brot +4“. */
fun shoppingLine(items: List<String>): Pair<ImageVector, String>? {
    if (items.isEmpty()) return null
    val shown = items.take(4).joinToString(", ")
    return Icons.Rounded.ShoppingCart to (if (items.size > 4) "$shown +${items.size - 4}" else shown)
}
