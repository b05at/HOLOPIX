package at.b05.holopix.data

/** Eine Fotoquelle für den Bildschirmschoner (SMB-Freigabe, lokale Ordner). */
interface PhotoSource {
    suspend fun listPhotos(maxFiles: Int = 20000, maxDepth: Int = 8, onBatch: (List<Photo>) -> Unit = {}): List<Photo>
    suspend fun probeAspect(photo: Photo): Double?
    suspend fun load(photo: Photo, maxEdge: Int, onProgress: ((Long, Long) -> Unit)? = null): LoadedPhoto?
    fun loadCachedIndex(): List<Photo>
    val cacheAgeMs: Long
    var lastError: String?
}

/**
 * Fotos aus lokalen Ordnern des Tablets (z. B. Kamera, Galerie-Alben, Download), ausgewählt über die
 * Android-Ordnerauswahl. Braucht keine Speicher-Berechtigung – Android merkt sich die Freigabe je Ordner.
 * Photo.url = content://-Dokument-URI, Photo.folder = "local/<Ordner-ID>/<Ordnername>" (für Gruppierung/Bildunterschrift).
 */
class LocalPhotoSource(private val context: android.content.Context, private val prefs: Prefs, private val decoder: SmbSource) : PhotoSource {
    private val exts = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    private val cacheFile get() = java.io.File(context.filesDir, "local_index.json")
    private val resolver get() = context.contentResolver

    val ready: Boolean get() = prefs.localFolders.isNotEmpty()

    @Volatile override var lastError: String? = null

    override val cacheAgeMs: Long get() = if (cacheFile.exists()) System.currentTimeMillis() - cacheFile.lastModified() else Long.MAX_VALUE

    private fun key() = prefs.localFolders.joinToString("|")

    override fun loadCachedIndex(): List<Photo> = try {
        val o = org.json.JSONObject(cacheFile.readText())
        if (o.optString("root") != key()) emptyList() else {
            val a = o.getJSONArray("p")
            (0 until a.length()).map { val p = a.getJSONArray(it); Photo(p.getString(0), p.getString(1), p.getString(2)) }
        }
    } catch (e: Exception) { emptyList() }

    private fun saveIndex(list: List<Photo>) {
        try {
            val a = org.json.JSONArray()
            list.forEach { a.put(org.json.JSONArray().put(it.url).put(it.folder).put(it.name)) }
            cacheFile.writeText(org.json.JSONObject().put("root", key()).put("p", a).toString())
        } catch (e: Exception) { }
    }

    override suspend fun listPhotos(maxFiles: Int, maxDepth: Int, onBatch: (List<Photo>) -> Unit): List<Photo> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val out = ArrayList<Photo>()
            var lastReport = 0
            var errors = 0
            fun walk(tree: android.net.Uri, docId: String, dirName: String, depth: Int) {
                if (depth > maxDepth || out.size >= maxFiles) return
                val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                val dirs = ArrayList<Pair<String, String>>()
                val folder = "local/" + docId + "/" + java.net.URLEncoder.encode(dirName, "UTF-8")
                resolver.query(
                    children,
                    arrayOf(
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ), null, null, null,
                )?.use { c ->
                    while (c.moveToNext() && out.size < maxFiles) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: ""
                        if (name.startsWith(".")) continue
                        if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) dirs += id to name
                        else if (mime.startsWith("image/") || name.substringAfterLast('.', "").lowercase() in exts)
                            out += Photo(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), folder, name)
                    }
                }
                if (out.size - lastReport >= 40 || (lastReport == 0 && out.isNotEmpty())) { lastReport = out.size; onBatch(ArrayList(out)) }
                dirs.shuffled().forEach { (id, n) -> walk(tree, id, n, depth + 1) }
            }
            for (s in prefs.localFolders) {
                try {
                    val tree = android.net.Uri.parse(s)
                    val root = android.provider.DocumentsContract.getTreeDocumentId(tree)
                    walk(tree, root, folderLabel(s).substringAfterLast('/'), 0)
                } catch (e: Exception) {
                    errors++
                    lastError = "Ordner ${folderLabel(s)}: kein Zugriff mehr – bitte neu auswählen"
                }
            }
            if (out.isEmpty() && errors > 0) throw java.io.IOException(lastError)
            saveIndex(out)
            out
        }

    override suspend fun probeAspect(photo: Photo): Double? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val uri = android.net.Uri.parse(photo.url)
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            var w = o.outWidth; var h = o.outHeight
            if (w <= 0 || h <= 0) {
                val full = load(photo, 400, null) ?: return@withContext null
                return@withContext full.aspect.toDouble().also { full.bitmap.recycle() }
            }
            val orientation = try {
                resolver.openInputStream(uri)?.use {
                    androidx.exifinterface.media.ExifInterface(it).getAttributeInt(
                        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) { androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL }
            if (orientation in setOf(
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90, androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE, androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE,
                )
            ) { val t = w; w = h; h = t }
            w.toDouble() / h
        } catch (e: Throwable) {
            lastError = "${photo.name}: ${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    override suspend fun load(photo: Photo, maxEdge: Int, onProgress: ((Long, Long) -> Unit)?): LoadedPhoto? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bytes = resolver.openInputStream(android.net.Uri.parse(photo.url))?.use { it.readBytes() }
                    ?: run { lastError = "${photo.name}: nicht lesbar"; return@withContext null }
                decoder.decode(bytes, photo, maxEdge) ?: run { lastError = "${photo.name}: Bildformat nicht lesbar"; null }
            } catch (e: OutOfMemoryError) {
                lastError = "${photo.name}: zu groß"; null
            } catch (e: Exception) {
                lastError = "${photo.name}: ${e.message ?: e.javaClass.simpleName}"; null
            }
        }

    companion object {
        /** "content://…/tree/primary%3ADCIM%2FCamera" → "DCIM/Camera" (bzw. "SD-Karte: Bilder"). */
        fun folderLabel(treeUri: String): String = try {
            val id = android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(treeUri))
            val vol = id.substringBefore(':')
            val path = id.substringAfter(':', "").ifBlank { "Hauptspeicher" }
            if (vol == "primary" || "downloads" in treeUri) path else "Speicherkarte: $path"
        } catch (e: Exception) { treeUri }
    }
}

/** SMB-Freigabe und lokale Ordner zusammen – jeweils nur, wenn eingerichtet. */
class MultiPhotoSource(private val smb: SmbSource, val local: LocalPhotoSource, private val prefs: Prefs) : PhotoSource {
    private fun isLocal(p: Photo) = p.url.startsWith("content://")
    val ready: Boolean get() = prefs.smbConfigured || local.ready

    override var lastError: String?
        get() = local.lastError ?: smb.lastError
        set(v) { smb.lastError = v; local.lastError = v }

    override val cacheAgeMs: Long get() = maxOf(
        if (prefs.smbConfigured) smb.cacheAgeMs else 0L,
        if (local.ready) local.cacheAgeMs else 0L,
    )

    override fun loadCachedIndex(): List<Photo> =
        (if (local.ready) local.loadCachedIndex() else emptyList()) + (if (prefs.smbConfigured) smb.loadCachedIndex() else emptyList())

    override suspend fun listPhotos(maxFiles: Int, maxDepth: Int, onBatch: (List<Photo>) -> Unit): List<Photo> {
        var localList = emptyList<Photo>()
        var firstError: Exception? = null
        // lokal zuerst (schnell), danach die Freigabe
        if (local.ready) try {
            localList = local.listPhotos(maxFiles, maxDepth, onBatch)
            if (localList.isNotEmpty()) onBatch(localList)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { firstError = e }
        var smbList = emptyList<Photo>()
        if (prefs.smbConfigured) try {
            smbList = smb.listPhotos(maxFiles, maxDepth) { b -> onBatch(localList + b) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { if (localList.isEmpty()) throw e }
        if (localList.isEmpty() && smbList.isEmpty()) firstError?.let { throw it }
        return localList + smbList
    }

    override suspend fun probeAspect(photo: Photo): Double? = if (isLocal(photo)) local.probeAspect(photo) else smb.probeAspect(photo)
    override suspend fun load(photo: Photo, maxEdge: Int, onProgress: ((Long, Long) -> Unit)?): LoadedPhoto? =
        if (isLocal(photo)) local.load(photo, maxEdge, onProgress) else smb.load(photo, maxEdge, onProgress)
}
