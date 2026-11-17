package at.b05.holopix.screensaver

import at.b05.holopix.data.Folders
import at.b05.holopix.data.LoadedPhoto
import at.b05.holopix.data.Photo
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/** Ganze Bildschirmfläche (inkl. Bereich der Systemleisten – der Bildschirmschoner läuft im Vollbild). */
@Suppress("DEPRECATION")
fun ScreenProfile.Companion.of(context: android.content.Context): ScreenProfile {
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

/** Eine fertige Collage: Layout-Baum + geladene Bilder (nach URL). */
data class Slide(
    val root: CNode,
    val images: Map<String, LoadedPhoto>,
    /** z. B. "Urlaub Kroatien · 7. Juni 2024" (Ordnername + Aufnahmedatum aus dem Dateinamen). */
    val caption: String? = null,
    /** Ganze Fotos mit unscharfem Rand zeigen statt zuschneiden (passt nicht bzw. Gesicht würde abgeschnitten). */
    val fit: Boolean = false,
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

/**
 * Wählt Fotos aus und baut daraus Collagen.
 *
 * @param excluded liefert die Schlüssel der ausgeschlossenen Ordner (siehe [Folders]) – deren Fotos
 *                 (und die aller Unterordner) werden nie gezeigt.
 */
class CollageEngine(
    private val source: at.b05.holopix.data.PhotoSource,
    private val excluded: () -> Collection<String> = { emptyList() },
) {
    /** Alles, was die Quellen geliefert haben (inkl. ausgeschlossener Ordner – für die Ordnerliste). */
    @Volatile private var index: List<Photo> = emptyList()
    /** Die Fotos, die gezeigt werden dürfen. */
    @Volatile private var all: List<Photo> = emptyList()
    @Volatile private var byFolder: Map<String, List<Photo>> = emptyMap()
    @Volatile private var folderNames: List<String> = emptyList()
    /** Aufsummierte Gewichte (Wurzel der Fotoanzahl) für die Ordnerwahl. */
    @Volatile private var folderWeights: DoubleArray = DoubleArray(0)
    /** Ordner mit demselben Oberordner. */
    @Volatile private var siblings: Map<String, List<String>> = emptyMap()

    private val shown: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    /** Seitenverhältnisse (nach EXIF-Drehung), damit Dateiköpfe nur einmal gelesen werden. */
    private val aspects = ConcurrentHashMap<String, Double>()
    /** Zuletzt gezeigte Ordner bzw. Anordnungen – für Abwechslung. */
    private val recentFolders = ArrayList<String>()
    private val recentLayouts = ArrayList<String>()

    val photoCount get() = all.size
    /** Fotos in ausgeschlossenen Ordnern. */
    val excludedCount get() = index.size - all.size

    private fun setPhotos(list: List<Photo>) {
        index = list
        rebuild()
    }

    /** Ausnahmen neu anwenden (nach Änderung in den Einstellungen) – wirkt sofort, ohne neu einzulesen. */
    fun applyExclusions() = rebuild()

    private fun rebuild() {
        val ex = excluded().toList()
        val grouped = index.groupBy { it.folder }
        val kept = if (ex.isEmpty()) grouped else grouped.filterKeys { !Folders.isExcluded(Folders.key(it), ex) }
        val sorted = kept.mapValues { (_, v) -> v.sortedBy { it.name } }
        val names = sorted.keys.toList()
        var sum = 0.0
        val weights = DoubleArray(names.size) { i -> sum += sqrt(sorted[names[i]]!!.size.toDouble()); sum }
        byFolder = sorted
        all = sorted.values.flatten()
        folderNames = names
        folderWeights = weights
        siblings = names.groupBy { Folders.parentKey(Folders.key(it)) ?: "" }
    }

    /** Ordner-Schlüssel → Anzahl Fotos direkt im Ordner (alle eingelesenen, auch ausgeschlossene). */
    fun folderCounts(): Map<String, Int> = index.groupingBy { Folders.key(it.folder) }.eachCount()

    /** Bei Speichermangel Zwischenspeicher leeren. */
    fun trimMemory() { if (aspects.size > 5000) aspects.clear() }

    /** Nach geänderten Quellen: Liste verwerfen. */
    fun clear() { setPhotos(emptyList()); shown.clear(); aspects.clear() }

    /** Zwischengespeicherte Liste sofort nutzen (Start ohne Wartezeit). */
    fun loadCache(): Boolean {
        val cached = source.loadCachedIndex()
        if (cached.isNotEmpty()) setPhotos(cached)
        return cached.isNotEmpty()
    }

    val indexLoaded: Boolean get() = index.isNotEmpty()

    val cacheStale: Boolean get() = source.cacheAgeMs > 12 * 3600_000L

    /** Quellen neu durchsuchen; Zwischenstände werden sofort verwendet, damit die Diashow früh startet. */
    suspend fun refresh(onProgress: (Int) -> Unit = {}) {
        val hadPhotos = index.isNotEmpty()
        val list = source.listPhotos(onBatch = { batch ->
            if (!hadPhotos) setPhotos(batch)
            onProgress(batch.size)
        })
        setPhotos(list)
        shown.retainAll(list.map { it.url }.toSet())
    }

    /**
     * Nächstes Hauptfoto. Erst wird ein Ordner gewählt – gewichtet mit der Wurzel seiner Fotoanzahl, damit
     * ein riesiger Ordner (z. B. Kamera-Upload) kleine Alben nicht verdrängt – und nicht derselbe wie bei den
     * letzten beiden Collagen. Darin dann ein noch nicht gezeigtes Foto.
     */
    private fun pickAnchor(): Photo? {
        val list = all
        val names = folderNames
        val weights = folderWeights
        val folders = byFolder
        if (list.isEmpty() || names.isEmpty() || weights.size != names.size) return null
        if (shown.size >= list.size) shown.clear()
        val avoid = if (names.size > 2) synchronized(recentFolders) { recentFolders.toSet() } else emptySet()
        repeat(24) {
            val x = Random.nextDouble() * weights[weights.size - 1]
            var i = java.util.Arrays.binarySearch(weights, x)
            if (i < 0) i = -i - 1
            val name = names[i.coerceIn(0, names.size - 1)]
            if (name in avoid) return@repeat
            val photos = folders[name] ?: return@repeat
            repeat(6) {
                val p = photos[Random.nextInt(photos.size)]
                if (p.url !in shown) return p
            }
            photos.firstOrNull { it.url !in shown }?.let { return it }
        }
        return list.firstOrNull { it.url !in shown } ?: list.random()
    }

    /**
     * Partner für das Hauptfoto – möglichst aus demselben Ordner (dasselbe Ereignis), aber keine Serienbilder
     * (fast gleiche Fotos kurz hintereinander): mindestens 45 s Abstand laut Dateiname, sonst mindestens
     * 2 Dateien Abstand. Noch nicht gezeigte Fotos kommen zuerst; reicht der Ordner nicht für zwei Partner,
     * wird aus Nachbarordnern (gleicher Oberordner) und erst zuletzt von irgendwo aufgefüllt.
     */
    private fun neighbours(anchor: Photo, count: Int): List<Photo> {
        if (count <= 0) return emptyList()
        val folder = byFolder[anchor.folder].orEmpty()
        val idx = folder.indexOfFirst { it.url == anchor.url }
        val picked = ArrayList<IndexedValue<Photo>>()
        picked += IndexedValue(idx, anchor)
        fun tooClose(c: IndexedValue<Photo>): Boolean = picked.any { p ->
            val a = photoTime(p.value.name); val b = photoTime(c.value.name)
            if (a != null && b != null) abs(java.time.Duration.between(a, b).seconds) < 45
            else abs(p.index - c.index) < 2
        }
        folder.withIndex()
            .filter { it.value.url != anchor.url }
            .sortedBy { (if (it.value.url in shown) 100_000 else 0) + abs(it.index - idx) + Random.nextInt(6) }
            .forEach { c -> if (picked.size <= count && !tooClose(c)) picked += c }
        val near = picked.drop(1).map { it.value }.take(count)
        if (near.size >= 2 || near.size >= count) return near

        val want = minOf(count, 3)
        val extra = ArrayList<Photo>()
        fun tryAdd(p: Photo) { if (p.url != anchor.url && p !in near && p !in extra && extra.size < want - near.size) extra += p }
        val sib = siblings[Folders.parentKey(Folders.key(anchor.folder)) ?: ""].orEmpty().filter { it != anchor.folder }
        if (sib.isNotEmpty()) repeat(want * 6) {
            val photos = byFolder[sib[Random.nextInt(sib.size)]].orEmpty()
            if (photos.isNotEmpty()) tryAdd(photos[Random.nextInt(photos.size)])
        }
        val list = all
        if (list.isNotEmpty()) repeat(want * 4) { tryAdd(list[Random.nextInt(list.size)]) }
        return near + extra
    }

    private suspend fun aspectOf(p: Photo): Double? =
        aspects[p.url] ?: source.probeAspect(p)?.also { aspects[p.url] = it }

    /**
     * Stellt die nächste Collage zusammen.
     *
     * 1. Hauptfoto wählen, dazu Kandidaten aus demselben Ordner (keine Serienbilder).
     * 2. Von allen nur den Dateikopf lesen (Seitenverhältnis) – schnell, wenige KB pro Foto.
     * 3. Beste Anordnung für genau diesen Bildschirm berechnen, nur die verwendeten Fotos laden.
     * 4. Jetzt sind die Gesichter bekannt: Anordnung noch einmal so wählen, dass niemand abgeschnitten wird.
     */
    suspend fun nextSlide(
        screen: ScreenProfile,
        maxPhotos: Int = screen.maxPhotos,
        onProgress: (String) -> Unit = {},
    ): Slide? = coroutineScope {
        val anchor = pickAnchor() ?: return@coroutineScope null
        shown += anchor.url
        synchronized(recentFolders) { recentFolders.add(0, anchor.folder); while (recentFolders.size > 2) recentFolders.removeAt(recentFolders.size - 1) }
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

        val recent = synchronized(recentLayouts) { ArrayList(recentLayouts) }
        var best = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            CollageLayout.best(CLeaf(anchor, anchorAspect), candLeaves, screen, maxOthers = others, recent = recent)
        }
        val leaves = best.leaves
        // Auflösung je Kachel: so groß wie die Kachel auf dem Bildschirm (+ Reserve für Zoom und Umordnen)
        val rects = CollageLayout.leafRects(best, screen.widthPx.toDouble(), screen.heightPx.toDouble())
        val loaded = leaves.mapIndexed { i, l ->
            onProgress("Lade Foto ${i + 1}/${leaves.size}: ${l.photo.name}")
            val r = rects[l.photo.url]
            val edge = if (leaves.size == 1 || r == null) screen.maxEdge
            else maxOf(maxOf(r.first, r.second) * 1.25, screen.maxEdge * 0.5).toInt().coerceIn(480, screen.maxEdge)
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

        // 2. Durchgang mit den geladenen Fotos: echte Formate und Gesichter sind jetzt bekannt
        val known = leaves.mapNotNull { l -> images[l.photo.url]?.let { CLeaf(l.photo, it.aspect.toDouble(), it.faces) } }
        if (known.size < leaves.size || known.any { it.faces != null }) {
            val main = known.firstOrNull { it.photo.url == anchor.url } ?: known.first()
            best = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                CollageLayout.best(main, known.filter { it !== main }, screen, maxOthers = known.size - 1, recent = recent, preferredCount = known.size)
            }
            // Fotos, die jetzt doch nicht in die Collage passen, kommen später wieder dran
            val used = best.leaves.map { it.photo.url }.toSet()
            images.keys.filter { it !in used }.forEach { shown -= it }
            images.keys.retainAll(used)
        }
        // Ein einzelnes Foto, bei dem das Zuschneiden Gesichter kosten würde, lieber ganz zeigen
        val single = best as? CLeaf
        val fit = CollageLayout.usesFit(best, screen.aspect) ||
            (single?.faces != null && CollageLayout.faceCut(single.aspect, screen.aspect, single.faces) > 0.08)
        synchronized(recentLayouts) { recentLayouts.add(0, CollageLayout.signature(best)); while (recentLayouts.size > 3) recentLayouts.removeAt(recentLayouts.size - 1) }
        Slide(best, images, captionFor(anchor), fit)
    }
}
