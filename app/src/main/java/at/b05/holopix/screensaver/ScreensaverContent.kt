package at.b05.holopix.screensaver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.vector.ImageVector
import at.b05.holopix.Holo
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Inhalt des Bildschirmschoners (Collage + Uhr + Widgets) – gemeinsam für die Activity
 * (Start nach Zeitplan, über dem Sperrbildschirm) und das Vollbild-Overlay (Start nach Leerlauf).
 * onTick läuft jede Sekunde (z. B. um das Display außerhalb des Zeitplans freizugeben).
 */
@Composable
fun ScreensaverContent(show: SlideShow, onTick: () -> Unit, onDismiss: () -> Unit) {
    val p = Holo.prefs
    val now by produceState(LocalDateTime.now()) {
        while (true) { value = LocalDateTime.now(); onTick(); delay(1000) }
    }
    val info = buildList<Pair<ImageVector, String>> {
        if (p.ssShowWeather) Holo.weather?.let { add(weatherLine(it, compact = p.ssWeatherStyle == "compact")) }
    }
    ScreensaverView(
        slide = show.slide,
        clock = if (p.ssShowClock) now.format(DateTimeFormatter.ofPattern("HH:mm")) else null,
        date = if (p.ssShowDate) now.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)) else null,
        info = info,
        status = show.status,
        style = OverlayStyle(p.ssOverlayPos, p.ssOverlaySize, p.ssShowCaption, p.ssPixelShift),
        onDismiss = onDismiss,
    )
}
