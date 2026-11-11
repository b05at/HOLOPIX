package at.b05.holopix.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

private class Palette(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val primaryDark: Color,
    val bg: Color,
    val card: Color,
    val line: Color,
    val text: Color,
    val textSoft: Color,
    val tip: Color,
    val chip: Color,
    val warn: Color,
)

/** Hell. */
private val LIGHT = Palette(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    primaryDark = Color(0xFF041E49),
    bg = Color(0xFFF8FAFD),
    card = Color(0xFFFFFFFF),
    line = Color(0xFFDADCE0),
    text = Color(0xFF1F1F1F),
    textSoft = Color(0xFF5F6368),
    tip = Color(0xFFFEF7E0),
    chip = Color(0xFFE8EEF7),
    warn = Color(0xFFE37400),
)

/** Dunkel. */
private val DARK = Palette(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    primaryDark = Color(0xFFD3E3FD),
    bg = Color(0xFF131314),
    card = Color(0xFF1E1F20),
    line = Color(0xFF3C4043),
    text = Color(0xFFE3E3E3),
    textSoft = Color(0xFF9AA0A6),
    tip = Color(0xFF3A3324),
    chip = Color(0xFF2D2F31),
    warn = Color(0xFFFFB95C),
)

/** Aktuelle App-Farben. Umschalten über [Z.dark]; Compose zeichnet automatisch neu. */
object Z {
    var dark by mutableStateOf(false)
    private val p: Palette get() = if (dark) DARK else LIGHT

    val Primary get() = p.primary
    val OnPrimary get() = p.onPrimary
    val PrimaryContainer get() = p.primaryContainer
    val PrimaryDark get() = p.primaryDark
    val Bg get() = p.bg
    val Card get() = p.card
    val Line get() = p.line
    val Text get() = p.text
    val TextSoft get() = p.textSoft
    val Tip get() = p.tip
    val Chip get() = p.chip
    val Sun = Color(0xFFF9AB00)
    val Rain = Color(0xFF1A73E8)
    // Hinweis-/Fehlerfarbe bewusst ohne Rot (Rot-Grün-Schwäche): Orange/Amber
    val Warn get() = p.warn
}

@Composable
fun HolopixTheme(content: @Composable () -> Unit) {
    val scheme = if (Z.dark) {
        darkColorScheme(
            primary = Z.Primary, onPrimary = Z.OnPrimary,
            primaryContainer = Z.PrimaryContainer, onPrimaryContainer = Z.PrimaryDark,
            secondary = Color(0xFF4DB6AC),
            background = Z.Bg, surface = Z.Card, surfaceContainer = Z.Card, surfaceContainerHigh = Z.Chip,
            onSurface = Z.Text, onBackground = Z.Text, onSurfaceVariant = Z.TextSoft,
            outline = Z.Line, outlineVariant = Z.Line, error = Z.Warn,
        )
    } else {
        lightColorScheme(
            primary = Z.Primary, onPrimary = Z.OnPrimary,
            primaryContainer = Z.PrimaryContainer, onPrimaryContainer = Z.PrimaryDark,
            secondary = Color(0xFF009688),
            background = Z.Bg, surface = Z.Card, surfaceContainer = Z.Bg,
            onSurface = Z.Text, onBackground = Z.Text,
            outline = Z.Line, error = Z.Warn,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** true = schmales Gerät (Smartphone, < 600 dp Breite) → eigene, einspaltige Aufteilung. */
val LocalPhone = androidx.compose.runtime.staticCompositionLocalOf { false }
