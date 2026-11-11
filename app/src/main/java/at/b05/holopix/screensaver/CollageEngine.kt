package at.b05.holopix.screensaver

import at.b05.holopix.data.LoadedPhoto
import at.b05.holopix.data.Photo
import at.b05.holopix.data.SmbSource
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.random.Random

/**
 * Collage als "Guillotine"-Baum: Blätter sind Fotos, Knoten teilen den Platz
 * nebeneinander (horizontal) oder übereinander. Jeder Knoten hat ein exaktes Seitenverhältnis:
 * - nebeneinander (gleiche Höhe): Summe der Seitenverhältnisse
 * - übereinander (gleiche Breite): 1 / Summe der Kehrwerte
 * Passt das Seitenverhältnis der Wurzel zum Bildschirm, wird kein Foto beschnitten
 * und es bleiben keine schwarzen Ränder.
 */
sealed class CNode {
    abstract val aspect: Double
    abstract val leaves: List<CLeaf>
}

class CLeaf(val photo: Photo, aspect: Double) : CNode() {
    override val aspect: Double = aspect.coerceIn(0.2, 5.0)
    override val leaves get() = listOf(this)
}

class CSplit(val horizontal: Boolean, val children: List<CNode>) : CNode() {
    override val aspect: Double =
        if (horizontal) children.sumOf { it.aspect } else 1.0 / children.sumOf { 1.0 / it.aspect }
    override val leaves get() = children.flatMap { it.leaves }

    /** Anteil des Kindes an der Fläche dieses Knotens. */
    fun share(child: CNode): Double =
        if (horizontal) child.aspect / children.sumOf { it.aspect }
        else (1.0 / child.aspect) / children.sumOf { 1.0 / it.aspect }
}

/**
 * Bildschirm, für den die Collage gebaut wird. Aus Größe (dp) und Dichte folgen:
 * - wie viele Fotos höchstens in eine Collage passen (Handy 2, 8"-Tablet 5, 10"+ bis 7),
 * - die Mindestgröße einer Kachel (keine Briefmarken),
 * - die Auflösung, mit der Fotos geladen werden.
 */
data class ScreenProfile(val widthPx: Int, val heightPx: Int, val density: Float) {
    val aspect: Double get() = widthPx.toDouble() / heightPx.coerceAtLeast(1)
    val widthDp: Double get() = widthPx / density.toDouble()
    val heightDp: Double get() = heightPx / density.toDouble()
    /** Fotos je Collage nach Bildschirmfläche: ca. 110 000 dp² pro Foto. */
    val maxPhotos: Int get() = ((widthDp * heightDp) / 110_000.0).toInt().coerceIn(2, 7)
    /** Kürzeste Kachelseite, unter der ein Foto zu klein wirkt. */
    val minTileDp: Double get() = if (minOf(widthDp, heightDp) < 480) 140.0 else 170.0
    val maxEdge: Int get() = maxOf(widthPx, heightPx).coerceIn(800, 3840)

    companion object {
        /** Ganze Bildschirmfläche (inkl. Bereich der Systemleisten – der Bildschirmschoner läuft im Vollbild). */
        @Suppress("DEPRECATION")
        fun of(context: android.content.Context): ScreenProfile {
            val dm = context.resources.displayMetrics
            val wm = context.getSystemService(android.view.WindowManager::class.java)
            var w = dm.widthPixels; var h = dm.heightPixels
            try {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val b = wm.maximumWindowMetrics.bounds; w = b.width(); h = b.height()
                } else {
                    val real = android.util.DisplayMetrics(); wm.defaultDisplay.getRealMetrics(real); w = real.widthPixels; h = real.heightPixels
                }
            } catch (e: Exception) { }
            return ScreenProfile(w, h, dm.density)
        }
    }
}

/** Eine fertige Collage: Layout-Baum + geladene Bilder (nach URL). */
data class Slide(
    val root: CNode,
    val images: Map<String, LoadedPhoto>,
    /** z. B. "Urlaub Kroatien · 7. Juni 2024" (Ordnername + Aufnahmedatum aus dem Dateinamen). */
    val caption: String? = null,
    val key: Long = System.nanoTime(),
)

/** Aufnahmezeit aus typischen Dateinamen: 20240607_105752.jpg, IMG_20240607_105752, PXL_20240607_105752123 … */
fun photoTime(name: String): java.time.LocalDateTime? {
    val m = Regex("(20\\d{2})(\\d{2})(\\d{2})[_-]?(\\d{2})(\\d{2})(\\d{2})").find(name) ?: return null
    val (y, mo, d, h, mi, se) = m.destructured
    return try { java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), se.toInt()) } catch (e: Exception) { null }
}

/** Ordnername + Datum als Bildunterschrift. */
fun captionFor(p: Photo): String? {
    val folder = java.net.URLDecoder.decode(p.folder.trimEnd('/').substringAfterLast('/'), "UTF-8")
        .takeIf { it.isNotBlank() && !it.matches(Regex("(?i)(dcim|camera|fotos?|photos?|bilder|pictures|\\d{4}|\\d{2,4}[-_ .]\\d{2})")) }
    val date = photoTime(p.name)?.toLocalDate()?.format(java.time.format.DateTimeFormatter.ofPattern("d. MMMM yyyy", java.util.Locale.GERMAN))
    return listOfNotNull(folder, date).joinToString(" · ").ifBlank { null }
}

class CollageEngine(private val source: at.b05.holopix.data.PhotoSource) {

    @Volatile private var all: List<Photo> = emptyList()
    @Volatile private var byFolder: Map<String, List<Photo>> = emptyMap()
    private val shown: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    /** Seitenverhältnisse (nach EXIF-Drehung), damit Dateiköpfe nur einmal gelesen werden. */
    private val aspects = ConcurrentHashMap<String, Double>()

    val photoCount get() = all.size

    private fun setPhotos(list: List<Photo>) {
        all = list
        byFolder = list.groupBy { it.folder }.mapValues { (_, v) -> v.sortedBy { it.name } }
    }

    /** Bei Speichermangel Zwischenspeicher leeren. */
    fun trimMemory() { if (aspects.size > 5000) aspects.clear() }

    /** Nach geänderten SMB-Einstellungen: Liste verwerfen. */
    fun clear() { setPhotos(emptyList()); shown.clear(); aspects.clear() }

    /** Zwischengespeicherte Liste sofort nutzen (Start ohne Wartezeit). */
    fun loadCache(): Boolean {
        val cached = source.loadCachedIndex()
        if (cached.isNotEmpty()) setPhotos(cached)
        return cached.isNotEmpty()
    }

    val cacheStale: Boolean get() = source.cacheAgeMs > 12 * 3600_000L

    /** Freigabe neu durchsuchen; Zwischenstände werden sofort verwendet, damit die Diashow früh startet. */
    suspend fun refresh(onProgress: (Int) -> Unit = {}) {
        val hadPhotos = all.isNotEmpty()
        val list = source.listPhotos(onBatch = { batch ->
            if (!hadPhotos) setPhotos(batch)
            onProgress(batch.size)
        })
        setPhotos(list)
        shown.retainAll(list.map { it.url }.toSet())
    }

    private fun pickAnchor(): Photo? {
        val list = all
        if (list.isEmpty()) return null
        if (shown.size >= list.size) shown.clear()
        repeat(30) {
            val p = list[Random.nextInt(list.size)]
            if (p.url !in shown) return p
        }
        return list.firstOrNull { it.url !in shown } ?: list.random()
    }

    /**
     * Partner aus demselben Ordner – aber keine Serienbilder (fast gleiche Fotos kurz hintereinander):
     * mindestens 45 s Abstand laut Dateiname, sonst mindestens 2 Dateien Abstand.
     */
    private fun neighbours(anchor: Photo, count: Int): List<Photo> {
        val folder = byFolder[anchor.folder].orEmpty()
        val idx = folder.indexOfFirst { it.url == anchor.url }
        val picked = ArrayList<IndexedValue<Photo>>()
        picked += IndexedValue(idx, anchor)
        fun tooClose(c: IndexedValue<Photo>): Boolean = picked.any { p ->
            val a = photoTime(p.value.name); val b = photoTime(c.value.name)
            if (a != null && b != null) kotlin.math.abs(java.time.Duration.between(a, b).seconds) < 45
            else abs(p.index - c.index) < 2
        }
        folder.withIndex()
            .filter { it.value.url != anchor.url && it.value.url !in shown }
            .sortedBy { abs(it.index - idx) + Random.nextInt(6) }
            .forEach { c -> if (picked.size <= count && !tooClose(c)) picked += c }
        val near = picked.drop(1).map { it.value }.take(count)
        if (near.size >= count) return near
        // Ordner zu klein → mit zufälligen Fotos auffüllen
        val list = all
        val extra = ArrayList<Photo>()
        repeat(count * 4) {
            if (extra.size >= count - near.size || list.isEmpty()) return@repeat
            val p = list[Random.nextInt(list.size)]
            if (p.url != anchor.url && p !in near && p !in extra) extra += p
        }
        return near + extra
    }

    private suspend fun aspectOf(p: Photo): Double? =
        aspects[p.url] ?: source.probeAspect(p)?.also { aspects[p.url] = it }

    /** Aufbau der letzten Collage – die nächste soll anders aussehen. */
    @Volatile private var lastSignature: String? = null

    /**
     * Stellt die nächste Collage zusammen.
     *
     * 1. Ein noch nicht gezeigtes Foto als Anker, dazu Kandidaten aus demselben Ordner (keine Serienbilder).
     * 2. Von allen nur den Dateikopf lesen (Seitenverhältnis) – schnell, wenige KB pro Foto.
     * 3. Beste Anordnung für genau diesen Bildschirm berechnen (Größe, Format, Mindestkachelgröße),
     *    dann nur die verwendeten Fotos laden – jeweils in der Auflösung ihrer Kachel.
     */
    suspend fun nextSlide(
        screen: ScreenProfile,
        maxPhotos: Int = screen.maxPhotos,
        onProgress: (String) -> Unit = {},
    ): Slide? = coroutineScope {
        val anchor = pickAnchor() ?: return@coroutineScope null
        shown += anchor.url
        onProgress("Prüfe Foto „${anchor.name}“")
        val anchorAspect = aspectOf(anchor) ?: return@coroutineScope null
        val others = (maxPhotos - 1).coerceAtLeast(0)

        // Kandidaten nacheinander prüfen (nur Dateikopf) – etwas mehr als nötig, damit die Auswahl passt
        val cands = neighbours(anchor, if (others == 0) 0 else minOf(8, others + 3))
        val candLeaves = ArrayList<CLeaf>()
        cands.forEachIndexed { i, p ->
            onProgress("Prüfe Fotos ${i + 1}/${cands.size}")
            aspectOf(p)?.let { candLeaves += CLeaf(p, it) }
        }

        val sigBefore = lastSignature
        var best = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            bestLayout(CLeaf(anchor, anchorAspect), candLeaves, screen, maxOthers = others, avoidSignature = sigBefore)
        }
        val leaves = best.leaves
        // Auflösung je Kachel: so groß wie die Kachel auf dem Bildschirm (+ Reserve für den Zoom)
        val rects = leafRects(best, screen.widthPx.toDouble(), screen.heightPx.toDouble())
        val loaded = leaves.mapIndexed { i, l ->
            onProgress("Lade Foto ${i + 1}/${leaves.size}: ${l.photo.name}")
            val r = rects[l.photo.url]
            val edge = if (leaves.size == 1 || r == null) screen.maxEdge
            else (maxOf(r.first, r.second) * 1.25).toInt().coerceIn(480, screen.maxEdge)
            var lastPct = -1
            source.load(l.photo, edge) { done, total ->
                if (total > 0) {
                    val pct = (done * 100 / total).toInt()
                    if (pct / 5 != lastPct / 5) {
                        lastPct = pct
                        onProgress("Lade Foto ${i + 1}/${leaves.size}: ${l.photo.name}\n$pct % von ${"%.1f".format(total / 1048576.0)} MB")
                    }
                }
            }
        }
        val images = HashMap<String, LoadedPhoto>()
        leaves.zip(loaded).forEach { (l, img) -> if (img != null) images[l.photo.url] = img }
        leaves.forEach { shown += it.photo.url }

        if (images.isEmpty()) return@coroutineScope null
        if (images.size < leaves.size) {
            // ein Foto ließ sich nicht laden → mit den geladenen neu anordnen
            val ok = leaves.filter { images.containsKey(it.photo.url) }
            best = bestLayout(ok.first(), ok.drop(1), screen, maxOthers = ok.size - 1)
            best.leaves.map { it.photo.url }.toSet().let { used -> images.keys.retainAll(used) }
        }
        lastSignature = signature(best)
        Slide(best, images, captionFor(anchor))
    }

    companion object {
        /**
         * Beste Anordnung für Anker + Kandidaten auf diesem Bildschirm.
         * Bis 5 Fotos werden alle Guillotine-Bäume durchprobiert, darüber zufällige Bäume (sonst zu viele).
         */
        fun bestLayout(
            anchor: CLeaf, candidates: List<CLeaf>, screen: ScreenProfile,
            rnd: Random = Random, maxOthers: Int = screen.maxPhotos - 1, avoidSignature: String? = null,
        ): CNode {
            // gewünschte Fotoanzahl dieses Mal – sorgt für Abwechslung zwischen ruhigen und vollen Collagen
            val maxLeaves = (maxOthers + 1).coerceAtLeast(1)
            val preferred = if (maxLeaves <= 2) maxLeaves else 2 + rnd.nextInt(maxLeaves - 1)
            fun score(t: CNode) = cost(t, screen, preferred, avoidSignature, rnd)

            var best: CNode = anchor
            var bestCost = score(anchor)
            // Einzelbild, das (fast) perfekt passt, gelegentlich bewusst als "Hingucker"
            if (abs(ln(anchor.aspect / screen.aspect)) < 0.12 && rnd.nextFloat() < 0.25f) bestCost -= 1.0

            val others = candidates.take(8)
            val n = others.size
            // 1) exakt: alle Teilmengen bis 4 Partner (≤ 5 Fotos) aus den ersten 6 Kandidaten
            val exactN = minOf(n, 6)
            for (mask in 1 until (1 shl exactN)) {
                val k = Integer.bitCount(mask)
                if (k > minOf(maxOthers, 4)) continue
                val set = ArrayList<CNode>().apply {
                    add(anchor)
                    for (i in 0 until exactN) if (mask and (1 shl i) != 0) add(others[i])
                }
                for (t in trees(set)) {
                    val c = score(t)
                    if (c < bestCost) { bestCost = c; best = t }
                }
            }
            // 2) große Bildschirme: 6–8 Fotos über zufällige Bäume
            if (maxOthers >= 5 && n >= 5) {
                repeat(6000) {
                    val k = 5 + rnd.nextInt(minOf(maxOthers, n) - 4) // 5 … maxOthers Partner
                    val set = ArrayList<CNode>(k + 1).apply { add(anchor); addAll(others.shuffled(rnd).take(k)) }
                    val t = randomTree(set, rnd)
                    val c = score(t)
                    if (c < bestCost) { bestCost = c; best = t }
                }
            }
            return shuffleOrder(best, rnd)
        }

        /** Alle binären Guillotine-Bäume über die Menge (ohne Spiegel-Duplikate). */
        private fun trees(items: List<CNode>): List<CNode> {
            if (items.size == 1) return items
            val out = ArrayList<CNode>()
            val first = items[0]
            val rest = items.drop(1)
            val m = rest.size
            for (mask in 0 until (1 shl m)) {
                val left = arrayListOf(first)
                val right = ArrayList<CNode>()
                for (i in 0 until m) if (mask and (1 shl i) != 0) left += rest[i] else right += rest[i]
                if (right.isEmpty()) continue
                for (l in trees(left)) for (r in trees(right)) {
                    out += CSplit(true, listOf(l, r))
                    out += CSplit(false, listOf(l, r))
                }
            }
            return out
        }

        private fun randomTree(items: List<CNode>, rnd: Random): CNode {
            if (items.size == 1) return items[0]
            val shuffled = items.shuffled(rnd)
            val cut = 1 + rnd.nextInt(shuffled.size - 1)
            return CSplit(rnd.nextBoolean(), listOf(randomTree(shuffled.take(cut), rnd), randomTree(shuffled.drop(cut), rnd)))
        }

        /** Kachelgrößen (Breite, Höhe) je Foto, wenn der Baum die Fläche w × h füllt – genau wie beim Zeichnen. */
        fun leafRects(node: CNode, w: Double, h: Double, out: MutableMap<String, Pair<Double, Double>> = HashMap()): Map<String, Pair<Double, Double>> {
            when (node) {
                is CLeaf -> out[node.photo.url] = w to h
                is CSplit -> node.children.forEach { c ->
                    val f = node.share(c)
                    if (node.horizontal) leafRects(c, w * f, h, out) else leafRects(c, w, h * f, out)
                }
            }
            return out
        }

        private fun leafRectList(node: CNode, w: Double, h: Double, out: MutableList<Triple<Double, Double, Double>>) {
            when (node) {
                is CLeaf -> out += Triple(w, h, node.aspect)
                is CSplit -> node.children.forEach { c ->
                    val f = node.share(c)
                    if (node.horizontal) leafRectList(c, w * f, h, out) else leafRectList(c, w, h * f, out)
                }
            }
        }

        /** Aufbau als Text (Anzahl + Teilungsrichtungen), um gleiche Collagen hintereinander zu vermeiden. */
        fun signature(node: CNode): String = when (node) {
            is CLeaf -> if (node.aspect >= 1) "L" else "P"
            is CSplit -> (if (node.horizontal) "H(" else "V(") + node.children.joinToString(",") { signature(it) } + ")"
        }

        /**
         * Bewertung – kleiner ist besser. Gerechnet wird mit den echten Kachelgrößen auf dem Bildschirm:
         * - Beschnitt je Foto (Flächen-gewichtet): Foto-Format gegen Kachel-Format,
         * - zu kleine Kacheln (unter der Mindestgröße in dp),
         * - zu ungleiche Größen (ein Riesenfoto neben Briefmarken),
         * - Abweichung von der gewünschten Fotoanzahl, Wiederholung der letzten Anordnung.
         */
        private fun cost(node: CNode, screen: ScreenProfile, preferred: Int, avoidSignature: String?, rnd: Random): Double {
            val rects = ArrayList<Triple<Double, Double, Double>>()
            leafRectList(node, screen.widthDp, screen.heightDp, rects)
            val total = screen.widthDp * screen.heightDp
            var c = 0.0
            var minArea = Double.MAX_VALUE; var maxArea = 0.0
            for ((w, h, a) in rects) {
                val area = w * h
                minArea = minOf(minArea, area); maxArea = maxOf(maxArea, area)
                c += (area / total) * abs(ln(a / (w / h))) * 4.0          // Beschnitt
                val short = minOf(w, h)
                if (short < screen.minTileDp) c += (screen.minTileDp - short) / screen.minTileDp * 2.0 // Briefmarken
            }
            val n = rects.size
            if (n > 1 && maxArea / minArea > 4.0) c += (ln(maxArea / minArea) - ln(4.0)) * 0.6       // Gleichgewicht
            c += if (n == 1) 0.35 else abs(n - preferred) * 0.05
            if (avoidSignature != null && n > 1 && signature(node) == avoidSignature) c += 0.15
            return c + rnd.nextDouble() * 0.06
        }

        /** Reihenfolge der Kinder zufällig drehen – gleiche Passform, andere Optik. */
        private fun shuffleOrder(node: CNode, rnd: Random): CNode = when (node) {
            is CLeaf -> node
            is CSplit -> CSplit(node.horizontal, node.children.map { shuffleOrder(it, rnd) }.let { if (rnd.nextBoolean()) it.reversed() else it })
        }
    }
}
