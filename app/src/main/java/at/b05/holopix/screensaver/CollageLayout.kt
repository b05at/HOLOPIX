package at.b05.holopix.screensaver

import at.b05.holopix.data.FaceBox
import at.b05.holopix.data.Photo
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

/** faces = Bereich der Gesichter, sobald das Foto geladen wurde (vorher unbekannt = null). */
class CLeaf(val photo: Photo, aspect: Double, val faces: FaceBox? = null) : CNode() {
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

    companion object
}

/**
 * Sucht die beste Anordnung. Reine Rechnung ohne Android – dieselben Formeln nutzt die Anzeige
 * (Kachelgrößen, Ausschnitt, Zoom), damit die Bewertung genau das misst, was man später sieht.
 */
object CollageLayout {
    /** Ab dieser Abweichung (ln-Verhältnis Collage/Bildschirm) zeigt die Anzeige ganze Fotos mit unscharfem Rand statt Beschnitt. */
    const val FIT_THRESHOLD = 0.25
    /** Endwert des langsamen Zooms in der Anzeige – so viel Rand geht zusätzlich verloren. */
    const val ZOOM = 1.10
    /**
     * Beschnitt bis ca. 7 % fällt nicht auf (der langsame Zoom nimmt ohnehin mehr weg) – er zählt deshalb nicht.
     * Dadurch gewinnt nicht immer dieselbe "perfekte" Anordnung, sondern Abwechslung entscheidet.
     */
    const val CROP_FREE = 0.07
    /** Ein einzelnes Foto darf als "Hingucker" den Bildschirm füllen, wenn dabei höchstens so viel wegfällt (ln-Verhältnis ≈ 18 %). */
    const val HERO_MAX_MISMATCH = 0.20
    /** Strafpunkte, wenn eine der letzten Anordnungen wiederholt würde (zuletzt gezeigte zuerst). */
    private val REPEAT_PENALTY = doubleArrayOf(0.30, 0.18, 0.10)

    fun usesFit(root: CNode, screenAspect: Double): Boolean = abs(ln(root.aspect / screenAspect)) > FIT_THRESHOLD

    /** Sichtbarer Anteil (Breite, Höhe) eines Fotos mit Format a in einer Kachel mit Format r beim Füllen. */
    fun visible(a: Double, r: Double): Pair<Double, Double> = if (r >= a) 1.0 to a / r else r / a to 1.0

    /**
     * Wie viel vom Gesichterbereich abgeschnitten würde (0 = nichts, 1 = alles), wenn das Foto die Kachel füllt
     * und der Ausschnitt auf die Gesichter zentriert wird (inkl. Zoom).
     */
    fun faceCut(a: Double, r: Double, faces: FaceBox): Double {
        val (vw, vh) = visible(a, r)
        fun cut(visible: Double, size: Float): Double {
            if (visible >= 1.0) return 0.0 // in dieser Richtung wird nichts beschnitten
            val need = minOf(size * ZOOM, 1.0)
            return if (need > visible) (need - visible) / need else 0.0
        }
        return maxOf(cut(vw, faces.width), cut(vh, faces.height))
    }

    /**
     * Beste Anordnung für Anker + Kandidaten auf diesem Bildschirm.
     * Bis 5 Fotos werden alle Guillotine-Bäume durchprobiert, darüber zufällige Bäume mit Nachbesserung.
     *
     * @param recent         Aufbau der zuletzt gezeigten Collagen (neueste zuerst) – wird gemieden
     * @param preferredCount gewünschte Fotoanzahl (null = jedes Mal zufällig, für Abwechslung)
     */
    fun best(
        anchor: CLeaf, candidates: List<CLeaf>, screen: ScreenProfile,
        rnd: Random = Random, maxOthers: Int = screen.maxPhotos - 1,
        recent: List<String> = emptyList(), preferredCount: Int? = null,
    ): CNode {
        val maxLeaves = (maxOthers + 1).coerceAtLeast(1)
        val preferred = preferredCount ?: if (maxLeaves <= 2) maxLeaves else 2 + rnd.nextInt(maxLeaves - 1)
        var best: CNode = anchor
        var bestCost = cost(anchor, screen, preferred, recent)
        // Vergleich mit der bisher besten Bewertung als Schranke (spart die teure Wiederholungs-Prüfung bei schlechten Bäumen)
        fun score(t: CNode) = cost(t, screen, preferred, recent, bound = bestCost) + rnd.nextDouble() * 0.06
        // Einzelbild, das den Bildschirm gut füllt, gelegentlich bewusst als großer "Hingucker"
        val heroFit = abs(ln(anchor.aspect / screen.aspect))
        if (preferredCount == null && heroFit < HERO_MAX_MISMATCH && rnd.nextFloat() < (if (heroFit < 0.12) 0.25f else 0.12f)) bestCost -= 1.5

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
        // 2) große Bildschirme: 6–8 Fotos über zufällige Bäume, die besten werden nachgebessert
        if (maxOthers >= 5 && n >= 5) {
            var bigBest: CNode? = null
            var bigCost = Double.MAX_VALUE
            repeat(3000) {
                val k = 5 + rnd.nextInt(minOf(maxOthers, n) - 4) // 5 … maxOthers Partner
                val set = ArrayList<CNode>(k + 1).apply { add(anchor); addAll(others.shuffled(rnd).take(k)) }
                val t = randomTree(set, rnd)
                val c = cost(t, screen, preferred, recent)
                if (c < bigCost) { bigCost = c; bigBest = t }
            }
            bigBest?.let { start ->
                val t = improve(start) { cost(it, screen, preferred, recent) }
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

    /** Nachbessern: zwei Fotos tauschen oder eine Teilung drehen, solange die Bewertung besser wird. */
    private fun improve(start: CNode, score: (CNode) -> Double): CNode {
        var best = start
        var bestCost = score(start)
        var rounds = 0
        var improved = true
        while (improved && rounds++ < 8) {
            improved = false
            val leaves = best.leaves
            for (i in leaves.indices) for (j in i + 1 until leaves.size) {
                val t = swapLeaves(best, leaves[i], leaves[j])
                val c = score(t)
                if (c < bestCost - 1e-9) { best = t; bestCost = c; improved = true }
            }
            for (s in splits(best)) {
                val t = flipSplit(best, s)
                val c = score(t)
                if (c < bestCost - 1e-9) { best = t; bestCost = c; improved = true }
            }
        }
        return best
    }

    private fun swapLeaves(node: CNode, a: CLeaf, b: CLeaf): CNode = when (node) {
        is CLeaf -> if (node === a) b else if (node === b) a else node
        is CSplit -> CSplit(node.horizontal, node.children.map { swapLeaves(it, a, b) })
    }

    private fun splits(node: CNode, out: MutableList<CSplit> = ArrayList()): List<CSplit> {
        if (node is CSplit) { out += node; node.children.forEach { splits(it, out) } }
        return out
    }

    private fun flipSplit(node: CNode, target: CSplit): CNode = when (node) {
        is CLeaf -> node
        is CSplit -> if (node === target) CSplit(!node.horizontal, node.children)
        else CSplit(node.horizontal, node.children.map { flipSplit(it, target) })
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

    /** Kachel eines Blatts: Breite, Höhe und das Blatt selbst. */
    class Tile(val w: Double, val h: Double, val leaf: CLeaf)

    fun tiles(node: CNode, w: Double, h: Double, out: MutableList<Tile> = ArrayList()): List<Tile> {
        when (node) {
            is CLeaf -> out += Tile(w, h, node)
            is CSplit -> node.children.forEach { c ->
                val f = node.share(c)
                if (node.horizontal) tiles(c, w * f, h, out) else tiles(c, w, h * f, out)
            }
        }
        return out
    }

    /**
     * Aufbau als Text (Teilungsrichtungen + Quer/Hoch je Foto), um gleiche Collagen hintereinander zu vermeiden.
     * Die Reihenfolge der Kinder ist sortiert – eine gespiegelte Collage gilt als dieselbe Anordnung.
     */
    fun signature(node: CNode): String = when (node) {
        is CLeaf -> if (node.aspect >= 1) "L" else "P"
        is CSplit -> (if (node.horizontal) "H(" else "V(") + node.children.map { signature(it) }.sorted().joinToString(",") + ")"
    }

    /**
     * Bewertung – kleiner ist besser. Gerechnet wird mit den echten Kachelgrößen auf dem Bildschirm:
     * - Beschnitt je Foto (Flächen-gewichtet): Foto-Format gegen Kachel-Format,
     * - kein einzelnes Foto darf mehr als ca. 30 % verlieren (auch kein kleines),
     * - abgeschnittene Gesichter (sobald bekannt) wiegen am schwersten,
     * - zu kleine Kacheln (unter der Mindestgröße in dp),
     * - zu ungleiche Größen (ein Riesenfoto neben Briefmarken),
     * - Abweichung von der gewünschten Fotoanzahl, Wiederholung einer der letzten Anordnungen.
     * Passt die Collage insgesamt nicht (→ Anzeige mit unscharfen Rändern), zählen stattdessen die Ränder.
     */
    fun cost(node: CNode, screen: ScreenProfile, preferred: Int, recent: List<String> = emptyList(), bound: Double = Double.MAX_VALUE): Double {
        val tiles = tiles(node, screen.widthDp, screen.heightDp)
        val total = screen.widthDp * screen.heightDp
        val fit = usesFit(node, screen.aspect)
        var c = if (fit) 0.5 else 0.0
        var minArea = Double.MAX_VALUE
        var maxArea = 0.0
        for (t in tiles) {
            val area = t.w * t.h
            minArea = minOf(minArea, area); maxArea = maxOf(maxArea, area)
            val a = t.leaf.aspect
            val r = t.w / t.h
            val (vw, vh) = visible(a, r)
            val vis = vw * vh // sichtbarer Flächenanteil des Fotos (bzw. vom Foto bedeckter Anteil der Kachel)
            if (fit) {
                c += (area / total) * (1.0 - vis) * 2.0                                   // unscharfe Ränder
            } else {
                c += (area / total) * (-ln(vis) - CROP_FREE).coerceAtLeast(0.0) * 4.0     // Beschnitt (kleiner zählt nicht)
                if (vis < 0.70) c += (0.70 - vis) * 2.5                                   // kein Foto stark beschneiden
                t.leaf.faces?.let { c += faceCut(a, r, it) * 3.0 }                        // Gesichter bleiben ganz
            }
            val short = minOf(t.w, t.h)
            if (short < screen.minTileDp) c += (screen.minTileDp - short) / screen.minTileDp * 2.0 // Briefmarken
        }
        val n = tiles.size
        if (n > 1 && maxArea / minArea > 4.0) c += (ln(maxArea / minArea) - ln(4.0)) * 0.6        // Gleichgewicht
        c += if (n == 1) 0.35 else abs(n - preferred) * 0.12
        if (c >= bound) return c // ohnehin schlechter als die bisher beste Anordnung
        if (n > 1 && recent.isNotEmpty()) {
            val i = recent.indexOf(signature(node))
            if (i in REPEAT_PENALTY.indices) c += REPEAT_PENALTY[i]
        }
        return c
    }

    /** Reihenfolge der Kinder zufällig drehen – gleiche Passform, andere Optik. */
    private fun shuffleOrder(node: CNode, rnd: Random): CNode = when (node) {
        is CLeaf -> node
        is CSplit -> CSplit(node.horizontal, node.children.map { shuffleOrder(it, rnd) }.let { if (rnd.nextBoolean()) it.reversed() else it })
    }
}
