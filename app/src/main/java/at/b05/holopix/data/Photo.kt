package at.b05.holopix.data

/** Ein Foto einer Quelle. folder = übergeordneter Ordner (für "smarte" Gruppierung und Ausnahmen). */
data class Photo(val url: String, val folder: String, val name: String)

/** Bereich aller erkannten Gesichter im Foto (Anteile 0..1, nach EXIF-Drehung). */
data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
}
