package at.b05.holopix.screensaver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.vector.ImageVector
import at.b05.holopix.Holo
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val context = androidx.compose.ui.platform.LocalContext.current
    // Kiosk-Modus: erst 4× schnell tippen beendet ihn
    var taps by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    var lastTap by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0L) }
    var askPin by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val onTap: () -> Unit = {
        if (!Holo.kioskMode) onDismiss()
        else {
            val t = System.currentTimeMillis()
            taps = if (t - lastTap < 1200) taps + 1 else 1
            lastTap = t
            if (taps >= 4) {
                taps = 0
                if (Holo.kioskPinSet) askPin = true else { Holo.setKiosk(context, false); onDismiss() }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(lastTap) { delay(1500); taps = 0 }
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
        onDismiss = onTap,
    )
    if (askPin) {
        at.b05.holopix.ui.PinPad(
            title = "PIN zum Beenden des Kiosk-Modus",
            onSubmit = { pin ->
                if (Holo.checkKioskPin(pin)) { askPin = false; Holo.setKiosk(context, false); onDismiss(); true } else false
            },
            onCancel = { askPin = false },
        )
    } else if (Holo.kioskMode && taps in 1..3) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.Text(
                "Kiosk-Modus: noch ${4 - taps}× tippen zum Beenden",
                color = androidx.compose.ui.graphics.Color.White, fontSize = 22.sp,
                modifier = androidx.compose.ui.Modifier
                    .background(androidx.compose.ui.graphics.Color(0x99000000), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            )
        }
    }
}
