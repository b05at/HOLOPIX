package at.b05.holopix.data

/**
 * Ordner-Schlüssel für die Ausnahmen ("diesen Ordner nicht zeigen").
 *
 * Ein Schlüssel ist so gebaut, dass er als Präfix auch alle Unterordner trifft:
 *  - Netzwerk (SMB):  "smb://nas/fotos/2024/Urlaub/"          (Ordner-URL mit / am Ende)
 *  - Gerät (lokal):   "local/primary:DCIM/Camera/"            (aus der Android-Ordner-ID)
 *                     "local/primary:"                        (ganzer Speicher – Unterordner heißen "primary:DCIM")
 */
object Folders {
    fun isLocal(s: String) = s.startsWith("local/")

    /** Schlüssel aus Photo.folder ("smb://…/" bzw. "local/<Ordner-ID>/<Ordnername>"). */
    fun key(folder: String): String =
        if (isLocal(folder)) localKey(folder.removePrefix("local/").substringBeforeLast('/'))
        else if (folder.endsWith("/")) folder else "$folder/"

    /** Schlüssel aus einer Android-Ordner-ID (z. B. "primary:DCIM/Camera"). */
    fun localKey(docId: String): String = if (docId.endsWith(":")) "local/$docId" else "local/$docId/"

    fun isExcluded(key: String, excluded: Collection<String>): Boolean = excluded.any { key.startsWith(it) }

    /** Übergeordneter Ordner oder null (oberste Ebene). */
    fun parentKey(key: String): String? {
        if (isLocal(key)) {
            val doc = key.removePrefix("local/").trimEnd('/')
            if (doc.endsWith(":") || ':' !in doc) return null
            val vol = doc.substringBefore(':')
            val path = doc.substringAfter(':')
            return if ('/' in path) "local/$vol:${path.substringBeforeLast('/')}/" else "local/$vol:"
        }
        val t = key.trimEnd('/')
        val i = t.lastIndexOf('/')
        if (i <= "smb://".length) return null
        return t.substring(0, i + 1)
    }

    /** Lesbarer Pfad: "DCIM/Camera", "Speicherkarte: Bilder", "nas/fotos/2024/Urlaub". */
    fun label(key: String): String {
        if (!isLocal(key)) return key.substringAfter("://").trimEnd('/')
        val doc = key.removePrefix("local/").trimEnd('/')
        if (':' !in doc) return doc
        val vol = doc.substringBefore(':')
        val path = doc.substringAfter(':')
        val primary = vol == "primary"
        return when {
            path.isBlank() -> if (primary) "Interner Speicher" else "Speicherkarte"
            primary -> path
            else -> "Speicherkarte: $path"
        }
    }

    /** Eine Zeile der Ordnerliste. direct = Fotos direkt im Ordner, total = inkl. Unterordner. */
    data class Row(val key: String, val name: String, val path: String, val depth: Int, val direct: Int, val total: Int, val local: Boolean)

    /**
     * Ordnerbaum für die Auswahl: alle Ordner mit Fotos, die ausgeschlossenen Ordner (auch wenn sie
     * deshalb gar nicht mehr eingelesen wurden) und die Zwischenordner bis zur jeweiligen Quelle –
     * sortiert, sodass Unterordner direkt unter ihrem Oberordner stehen.
     *
     * @param counts Schlüssel → Anzahl Fotos direkt im Ordner
     * @param roots  Schlüssel der eingerichteten Quellen (SMB-Adresse, gewählte Geräteordner)
     */
    fun tree(counts: Map<String, Int>, excluded: Collection<String>, roots: Collection<String>): List<Row> {
        fun rootOf(k: String): String? = roots.filter { k.startsWith(it) }.maxByOrNull { it.length }
        val keys = HashSet<String>()
        fun addWithAncestors(k: String) {
            val root = rootOf(k)
            var cur: String? = k
            while (cur != null && keys.add(cur)) {
                if (root == null || cur == root) break
                cur = parentKey(cur)?.takeIf { it.startsWith(root) }
            }
        }
        counts.keys.forEach(::addWithAncestors)
        excluded.forEach(::addWithAncestors)
        val sorted = keys.sortedBy { it.lowercase() }
        val out = ArrayList<Row>(sorted.size)
        for ((i, k) in sorted.withIndex()) {
            var total = 0
            var j = i
            while (j < sorted.size && sorted[j].startsWith(k)) { total += counts[sorted[j]] ?: 0; j++ }
            var depth = 0
            var p = parentKey(k)
            while (p != null && p in keys) { depth++; p = parentKey(p) }
            val path = label(k)
            val name = if (depth == 0) path else path.substringAfterLast('/')
            out += Row(k, name, path, depth, counts[k] ?: 0, total, isLocal(k))
        }
        return out
    }
}
