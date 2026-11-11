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

    /**
     * Stellt die nächste Collage zusammen.
     * screenAspect = Breite / Höhe des Bildschirms, maxEdge = maximale Kantenlänge beim Dekodieren.
     *
     * 1. Ein noch nicht gezeigtes Foto als Anker, dazu bis zu 6 aus demselben Ordner.
     * 2. Von allen nur den Dateikopf lesen (Seitenverhältnis) – schnell, wenige KB pro Foto.
     * 3. Beste Anordnung berechnen, dann nur die tatsächlich verwendeten Fotos ganz laden.
     */
    suspend fun nextSlide(
        screenAspect: Float,
        maxEdge: Int,
        maxOthers: Int = 4,
        onProgress: (String) -> Unit = {},
    ): Slide? = coroutineScope {
        val anchor = pickAnchor() ?: return@coroutineScope null
        shown += anchor.url
        onProgress("Prüfe Foto „${anchor.name}“")
        val anchorAspect = aspectOf(anchor) ?: return@coroutineScope null

        // Kandidaten nacheinander prüfen (nur Dateikopf)
        val cands = neighbours(anchor, if (maxOthers == 0) 0 else 6)
        val candLeaves = ArrayList<CLeaf>()
        cands.forEachIndexed { i, p ->
            onProgress("Prüfe Fotos ${i + 1}/${cands.size}")
            aspectOf(p)?.let { candLeaves += CLeaf(p, it) }
        }

        var best = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            bestLayout(CLeaf(anchor, anchorAspect), candLeaves, screenAspect.toDouble(), maxOthers = maxOthers)
        }
        val leaves = best.leaves
        // Bildgröße je nach Flächenanteil – kleine Kacheln brauchen keine volle Auflösung
        val edge = if (leaves.size == 1) maxEdge else maxEdge * 2 / 3
        val loaded = leaves.mapIndexed { i, l ->
            onProgress("Lade Foto ${i + 1}/${leaves.size}: ${l.photo.name}")
            var lastPct = -1
            source.load(l.photo, edge) { done, total ->
                val pct = (done * 100 / total).toInt()
                if (pct / 5 != lastPct / 5) {
                    lastPct = pct
                    onProgress("Lade Foto ${i + 1}/${leaves.size}: ${l.photo.name}\n$pct % von ${"%.1f".format(total / 1048576.0)} MB")
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
            best = bestLayout(ok.first(), ok.drop(1), screenAspect.toDouble())
            best.leaves.map { it.photo.url }.toSet().let { used -> images.keys.retainAll(used) }
        }
        Slide(best, images, captionFor(anchor))
    }

    companion object {
        /** Bester Baum für Anker + Kandidaten. */
        fun bestLayout(anchor: CLeaf, candidates: List<CLeaf>, screenAspect: Double, rnd: Random = Random, maxOthers: Int = 4): CNode {
            val target = screenAspect
            // Einzelbild nur, wenn es (fast) perfekt passt – dann gelegentlich als "Hingucker"
            val singleFits = abs(ln(anchor.aspect / target)) < 0.12
            var best: CNode = anchor
            var bestCost = if (singleFits && rnd.nextFloat() < 0.3f) -1.0 else cost(anchor, target, rnd)

            val others = candidates.take(6)
            val n = others.size
            for (mask in 1 until (1 shl n)) {
                if (Integer.bitCount(mask) > maxOthers) continue
                val set = ArrayList<CNode>().apply {
                    add(anchor)
                    for (i in 0 until n) if (mask and (1 shl i) != 0) add(others[i])
                }
                for (t in trees(set)) {
                    val c = cost(t, target, rnd)
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

        private fun leafShares(node: CNode, share: Double, out: MutableList<Double>) {
            when (node) {
                is CLeaf -> out += share
                is CSplit -> node.children.forEach { leafShares(it, share * node.share(it), out) }
            }
        }

        /** Kleiner ist besser. */
        private fun cost(node: CNode, target: Double, rnd: Random): Double {
            var c = abs(ln(node.aspect / target)) * 3.0          // Beschnitt / Ränder
            val shares = ArrayList<Double>().also { leafShares(node, 1.0, it) }
            val minShare = shares.minOrNull() ?: 1.0
            if (minShare < 0.12) c += (0.12 - minShare) * 12.0  // keine Briefmarken
            val maxShare = shares.maxOrNull() ?: 1.0
            if (shares.size > 1 && maxShare > 0.75) c += (maxShare - 0.75) * 3.0
            c += when (shares.size) { 1 -> 0.35; 2 -> 0.0; 3 -> 0.02; 4 -> 0.06; else -> 0.12 }
            // Abwechslung: manchmal bewusst eine größere Collage bevorzugen
            if (shares.size >= 3 && rnd.nextFloat() < 0.35f) c -= 0.08
            return c + rnd.nextDouble() * 0.08
        }

        /** Reihenfolge der Kinder zufällig drehen – gleiche Passform, andere Optik. */
        private fun shuffleOrder(node: CNode, rnd: Random): CNode = when (node) {
            is CLeaf -> node
            is CSplit -> CSplit(node.horizontal, node.children.map { shuffleOrder(it, rnd) }.let { if (rnd.nextBoolean()) it.reversed() else it })
        }
    }
}
