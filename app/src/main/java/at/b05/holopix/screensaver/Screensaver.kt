package at.b05.holopix.screensaver

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.b05.holopix.data.LoadedPhoto
import kotlinx.coroutines.delay

/**
 * Overlay-Einstellungen des Bildschirmschoners.
 * position: "bl" unten links, "bc" unten Mitte, "br" unten rechts, "tl" oben links, "tr" oben rechts,
 *           "split" = Uhr unten links, Infos unten rechts.
 * size: 0 klein, 1 mittel, 2 groß.
 */
data class OverlayStyle(val position: String = "bl", val size: Int = 1, val showCaption: Boolean = true, val shift: Boolean = true)

/** Vollbild-Bildschirmschoner: Collage + Uhr + Infozeilen. Tippen beendet ihn. */
@Composable
fun ScreensaverView(
    slide: Slide?,
    clock: String?,
    date: String?,
    info: List<Pair<ImageVector, String>>,
    status: String? = null,
    style: OverlayStyle = OverlayStyle(),
    onDismiss: () -> Unit,
) {
    val top = style.position.startsWith("t")
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1220))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
    ) {
        if (slide == null) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1E3A6E), Color(0xFF0B1220)))))
        } else {
            Crossfade(targetState = slide, animationSpec = tween(1600), label = "collage") { s -> CollageView(s) }
        }
        val captionLine = if (style.showCaption) slide?.caption else null
        Overlay(style, clock, date, info, captionLine)

        if (status != null) {
            Text(
                status, color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp,
                modifier = Modifier.align(if (top) Alignment.BottomStart else Alignment.TopStart).padding(24.dp)
                    .background(Color(0x66000000), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Overlay(
    style: OverlayStyle,
    clock: String?,
    date: String?,
    info: List<Pair<ImageVector, String>>,
    caption: String?,
) {
    val phone = LocalConfiguration.current.screenWidthDp < 600
    val scale = listOf(0.72f, 1f, 1.3f)[style.size.coerceIn(0, 2)] * (if (phone) 0.75f else 1f)
    val pad = (if (phone) 20 else 32).dp

    // Pixel-Verschiebung gegen Einbrennen: jede Minute ein paar dp weiter
    val step by produceState(0) { while (true) { delay(60_000); value = (value + 1) % 4 } }
    val (dx, dy) = if (!style.shift) 0 to 0 else listOf(0 to 0, 6 to -4, 0 to -8, -6 to -4)[step]

    val align = when (style.position) {
        "br" -> Alignment.BottomEnd; "bc" -> Alignment.BottomCenter
        "tl" -> Alignment.TopStart; "tr" -> Alignment.TopEnd
        else -> Alignment.BottomStart
    }
    val hAlign = when (style.position) {
        "br", "tr" -> Alignment.End; "bc" -> Alignment.CenterHorizontally; else -> Alignment.Start
    }
    val textAlign = when (hAlign) { Alignment.End -> TextAlign.End; Alignment.CenterHorizontally -> TextAlign.Center; else -> TextAlign.Start }

    val lines = info + listOfNotNull(caption?.let { Icons.Rounded.PhotoLibrary to it })

    if (style.position == "split" && !phone) {
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(pad).offset(dx.dp, dy.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            ClockBlock(clock, date, scale, Alignment.Start, TextAlign.Start)
            InfoBlock(lines, scale, Alignment.End)
        }
    } else {
        Column(
            Modifier.align(align).padding(pad).offset(dx.dp, dy.dp),
            horizontalAlignment = hAlign,
        ) {
            ClockBlock(clock, date, scale, hAlign, textAlign)
            InfoBlock(lines, scale, hAlign)
        }
    }
}

/** Weicher Schatten hinter der Schrift – lesbar auch auf hellen Fotos, ohne das Bild abzudunkeln. */
private val TextShadow = TextStyle(shadow = Shadow(Color(0x99000000), Offset(0f, 2f), 8f))

@Composable
private fun ClockBlock(clock: String?, date: String?, scale: Float, hAlign: Alignment.Horizontal, textAlign: TextAlign) {
    Column(horizontalAlignment = hAlign) {
        if (clock != null) Text(clock, color = Color.White, fontSize = (88 * scale).sp, fontWeight = FontWeight.Light, style = TextShadow)
        if (date != null) Text(date, color = Color.White, fontSize = (26 * scale).sp, textAlign = textAlign, style = TextShadow)
    }
}

@Composable
private fun InfoBlock(lines: List<Pair<ImageVector, String>>, scale: Float, hAlign: Alignment.Horizontal) {
    Column(horizontalAlignment = hAlign) {
        lines.forEach { (icon, text) ->
            Row(Modifier.padding(top = (6 * scale).dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size((24 * scale).dp))
                Spacer(Modifier.width((10 * scale).dp))
                Text(text, color = Color.White, fontSize = (22 * scale).sp, style = TextShadow)
            }
        }
    }
}

@Composable
private fun CollageView(slide: Slide) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
        val screenAspect = maxWidth.value / maxHeight.value
        // Passt die Collage nicht gut (z. B. nur ein einzelnes Hochformatfoto), statt Beschnitt
        // das ganze Foto zeigen – mit weichgezeichnetem Hintergrund statt schwarzer Ränder.
        val mismatch = kotlin.math.abs(kotlin.math.ln(slide.root.aspect / screenAspect))
        var counter = 0
        val order = HashMap<String, Int>()
        slide.root.leaves.forEach { order[it.photo.url] = counter++ }
        NodeView(slide.root, slide.images, order, Modifier.fillMaxSize(), fit = mismatch > 0.25)
    }
}

/** Zeichnet den Collage-Baum: Breiten/Höhen exakt nach den Seitenverhältnissen der Fotos. */
@Composable
private fun NodeView(node: CNode, images: Map<String, LoadedPhoto>, order: Map<String, Int>, modifier: Modifier, fit: Boolean) {
    val gap = 4.dp
    when (node) {
        is CLeaf -> images[node.photo.url]?.let { Tile(it, modifier, fit, order[node.photo.url] ?: 0) } ?: Box(modifier)
        is CSplit -> if (node.horizontal) {
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
                node.children.forEach { NodeView(it, images, order, Modifier.weight(it.aspect.toFloat()).fillMaxHeight(), fit) }
            }
        } else {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
                node.children.forEach { NodeView(it, images, order, Modifier.weight((1.0 / it.aspect).toFloat()).fillMaxWidth(), fit) }
            }
        }
    }
}

/**
 * Ein Foto: Zuschnitt und langsamer Zoom auf die Gesichter (falls erkannt),
 * Kacheln blenden nacheinander ein. fit = ganzes Foto mit unscharfem Hintergrund.
 */
@Composable
private fun Tile(photo: LoadedPhoto, modifier: Modifier, fit: Boolean, index: Int) {
    val img = remember(photo) { photo.bitmap.asImageBitmap() }
    val blurred = remember(photo, fit) {
        if (!fit) null else android.graphics.Bitmap.createScaledBitmap(
            photo.bitmap, 24, (24 / photo.aspect).toInt().coerceAtLeast(1), true,
        ).asImageBitmap()
    }
    val zoom = remember(photo) { Animatable(1f) }
    val alpha = remember(photo) { Animatable(0f) }
    LaunchedEffect(photo) {
        delay(index * 220L)
        alpha.animateTo(1f, tween(700))
    }
    LaunchedEffect(photo) { zoom.animateTo(1.10f, tween(20000, easing = LinearEasing)) }
    val bias = BiasAlignment(photo.focusX * 2f - 1f, photo.focusY * 2f - 1f)
    Box(modifier.clip(RoundedCornerShape(8.dp)).graphicsLayer { this.alpha = alpha.value }) {
        if (blurred != null) {
            Image(
                bitmap = blurred, contentDescription = null, contentScale = ContentScale.Crop,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(0x66000000), androidx.compose.ui.graphics.BlendMode.Darken),
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.2f; scaleY = 1.2f },
            )
        }
        Image(
            bitmap = img,
            contentDescription = null,
            contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
            alignment = if (fit) Alignment.Center else bias,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom.value; scaleY = zoom.value
                // Zoom auf den wichtigsten Bildbereich (Gesichter)
                transformOrigin = TransformOrigin(photo.focusX, photo.focusY)
            },
        )
    }
}
