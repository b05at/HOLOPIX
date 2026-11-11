package at.b05.holopix.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties

/** Ein Foto auf der SMB-Freigabe. folder = übergeordneter Ordner (für "smarte" Gruppierung). */
data class Photo(val url: String, val folder: String, val name: String)

/** Ein geladenes Foto inkl. Seitenverhältnis (nach EXIF-Drehung). */
/**
 * focusX/focusY (0..1) = wichtigster Bildbereich (Gesichter), damit beim Zuschneiden
 * und beim Zoom niemand "abgeschnitten" wird. Ohne Gesichter leicht oberhalb der Mitte.
 */
data class LoadedPhoto(val photo: Photo, val bitmap: Bitmap, val focusX: Float = 0.5f, val focusY: Float = 0.42f) {
    val aspect: Float get() = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
}

/** Ein Schritt im Verbindungstest. ok = null → läuft gerade. */
data class SmbStep(val title: String, val ok: Boolean?, val detail: String)

/** Aus den Eingabefeldern ermittelte Adresse. */
data class SmbTarget(val host: String, val share: String, val path: String) {
    val url: String
        get() = buildString {
            append("smb://").append(host).append('/')
            if (share.isNotEmpty()) append(share).append('/')
            if (path.isNotEmpty()) append(path).append('/')
        }
}

class SmbSource(private val context: Context, private val prefs: Prefs) : PhotoSource {

    private val exts = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    private var cachedCtx: CIFSContext? = null
    private var ctxKey = ""
    private val cacheFile get() = File(context.filesDir, "smb_index.json")

    companion object {
        /**
         * Erlaubt auch Eingaben wie "\\NAS\Fotos\Urlaub", "smb://nas/Fotos" oder "nas/Fotos/Urlaub"
         * im Server-Feld; Freigabe/Ordner-Felder werden dann ergänzt.
         */
        fun parse(hostField: String, shareField: String, pathField: String): SmbTarget {
            val parts = hostField.trim()
                .removePrefix("smb://").removePrefix("SMB://")
                .replace('\\', '/').trim('/')
                .split('/').filter { it.isNotBlank() }
            val host = parts.firstOrNull() ?: ""
            val extra = parts.drop(1) +
                shareField.replace('\\', '/').split('/').filter { it.isNotBlank() } +
                pathField.replace('\\', '/').split('/').filter { it.isNotBlank() }
            return SmbTarget(host, extra.firstOrNull() ?: "", extra.drop(1).joinToString("/"))
        }

        /** Verständliche Texte für typische SMB-Fehler. */
        fun explain(e: Throwable): String {
            val chain = generateSequence(e) { it.cause }.take(4).toList()
            val raw = chain.mapNotNull { it.message }.joinToString(" → ").ifBlank { e.javaClass.simpleName }
            val lower = raw.lowercase()
            val hint = when {
                "logon failure" in lower || "bad password" in lower || "status_logon_failure" in lower ->
                    "Benutzername oder Passwort falsch."
                "account" in lower && ("disabled" in lower || "restriction" in lower) -> "Konto gesperrt oder Anmeldung eingeschränkt."
                "network name cannot be found" in lower || "bad_network_name" in lower ->
                    "Freigabe nicht gefunden – Freigabename prüfen (siehe Liste der Freigaben)."
                "access is denied" in lower || "access_denied" in lower -> "Zugriff verweigert – Rechte des Benutzers auf die Freigabe prüfen."
                "cannot find the file" in lower || "cannot find the path" in lower || "object_name_not_found" in lower ->
                    "Ordner nicht gefunden – Unterordner prüfen."
                "unknownhost" in lower || "unable to resolve" in lower || e is java.net.UnknownHostException ->
                    "Servername nicht auflösbar – besser die IP-Adresse eintragen."
                "timed out" in lower || "timeout" in lower -> "Zeitüberschreitung – Server nicht erreichbar oder Firewall."
                "connection refused" in lower -> "Verbindung abgelehnt – SMB ist am Server nicht aktiv."
                "smb1" in lower || "dialect" in lower || "protocol" in lower && "negotiat" in lower ->
                    "SMB-Version passt nicht – ggf. „Alte NAS (SMB1) erlauben“ einschalten."
                "md4" in lower -> "Verschlüsselung (MD4) nicht verfügbar – bitte Fehler melden."
                else -> null
            }
            return if (hint != null) "$hint\n($raw)" else raw
        }
    }

    private fun target() = parse(prefs.smbHost, prefs.smbShare, prefs.smbPath)

    /**
     * Alle Netzwerkzugriffe nacheinander: jcifs-ng kann bei vielen gleichzeitigen Zugriffen
     * über dieselbe Verbindung hängen bleiben (Ordnersuche + Fotos laden gleichzeitig).
     */
    private val net = java.util.concurrent.Semaphore(1, true)
    private inline fun <T> serial(block: () -> T): T {
        net.acquire()
        try { return block() } finally { net.release() }
    }

    private fun buildContext(guest: Boolean = false): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", if (prefs.smbAllowSmb1) "SMB1" else "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", "15000")
            setProperty("jcifs.smb.client.connTimeout", "8000")
            setProperty("jcifs.smb.client.soTimeout", "20000")
            setProperty("jcifs.resolveOrder", "DNS,BCAST")
            setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            setProperty("jcifs.smb.client.rcv_buf_size", "1048576")
            setProperty("jcifs.smb.client.snd_buf_size", "1048576")
        }
        val base = BaseContext(PropertyConfiguration(props))
        return when {
            prefs.smbUser.isNotBlank() ->
                base.withCredentials(NtlmPasswordAuthenticator(prefs.smbDomain.ifBlank { null }, prefs.smbUser, prefs.smbPassword))
            guest -> base.withGuestCrendentials()
            else -> base.withAnonymousCredentials()
        }
    }

    private fun context(): CIFSContext {
        val key = listOf(prefs.smbHost, prefs.smbShare, prefs.smbUser, prefs.smbPassword, prefs.smbDomain, prefs.smbAllowSmb1, prefs.smbGuest).joinToString("|")
        cachedCtx?.let { if (key == ctxKey) return it }
        val ctx = buildContext(guest = prefs.smbGuest)
        cachedCtx = ctx
        ctxKey = key
        return ctx
    }

    // ---------- Fotoliste (mit Zwischenspeicher) ----------

    override fun loadCachedIndex(): List<Photo> = try {
        val o = JSONObject(cacheFile.readText())
        if (o.optString("root") != target().url) emptyList() else {
            val a = o.getJSONArray("p")
            (0 until a.length()).map { val p = a.getJSONArray(it); Photo(p.getString(0), p.getString(1), p.getString(2)) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    override val cacheAgeMs: Long get() = if (cacheFile.exists()) System.currentTimeMillis() - cacheFile.lastModified() else Long.MAX_VALUE

    private fun saveIndex(list: List<Photo>) {
        try {
            val a = JSONArray()
            list.forEach { a.put(JSONArray().put(it.url).put(it.folder).put(it.name)) }
            cacheFile.writeText(JSONObject().put("root", target().url).put("p", a).toString())
        } catch (e: Exception) { /* egal */ }
    }

    /** Durchsucht die Freigabe rekursiv nach Fotos; onBatch liefert Zwischenstände (für schnellen Start). */
    override suspend fun listPhotos(
        maxFiles: Int,
        maxDepth: Int,
        onBatch: (List<Photo>) -> Unit,
    ): List<Photo> = withContext(Dispatchers.IO) {
        val ctx = context()
        val out = ArrayList<Photo>()
        var lastReport = 0
        fun walk(url: String, depth: Int) {
            if (depth > maxDepth || out.size >= maxFiles) return
            val dir = SmbFile(url, ctx)
            val children = try { serial { dir.listFiles() } } catch (e: Exception) { if (depth == 0) throw e else emptyArray() }
            // erst Dateien dieses Ordners, dann Unterordner → schneller erste Treffer
            val dirs = ArrayList<String>()
            for (c in children) {
                val name = c.name.trimEnd('/')
                if (name.startsWith(".") || name.startsWith("@") || name == "#recycle" || name == "\$RECYCLE.BIN") { c.close(); continue }
                if (c.name.endsWith("/")) dirs += c.path
                else if (name.substringAfterLast('.', "").lowercase() in exts) out += Photo(c.path, url, name)
                c.close()
                if (out.size >= maxFiles) break
            }
            dir.close()
            if (out.size - lastReport >= 40 || (lastReport == 0 && out.isNotEmpty())) {
                lastReport = out.size; onBatch(ArrayList(out))
            }
            dirs.shuffled().forEach { walk(it, depth + 1) }
        }
        walk(target().url, 0)
        saveIndex(out)
        out
    }

    /** Letzter Fehler beim Laden eines Fotos (für die Anzeige im Bildschirmschoner). */
    @Volatile override var lastError: String? = null

    /** Datei über Ordner + Name öffnen – robuster bei Sonderzeichen (#, %, Leerzeichen) im Dateinamen. */
    private fun file(photo: Photo, ctx: CIFSContext): SmbFile =
        if (photo.folder.isNotBlank()) SmbFile(SmbFile(photo.folder, ctx), photo.name) else SmbFile(photo.url, ctx)

    /** Lädt und skaliert ein Foto (inkl. EXIF-Drehung). */
    override suspend fun load(photo: Photo, maxEdge: Int, onProgress: ((Long, Long) -> Unit)?): LoadedPhoto? = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        try {
            val bytes = serial { readBytes(photo, context(), onProgress) } ?: run { lastError = "${photo.name}: Datei größer als 40 MB"; return@runInterruptible null }
            decode(bytes, photo, maxEdge) ?: run { lastError = "${photo.name}: Bildformat nicht lesbar"; null }
        } catch (e: InterruptedException) {
            null
        } catch (e: Throwable) {
            lastError = "${photo.name}: " + explain(e)
            null
        }
    }

    /**
     * Seitenverhältnis (nach EXIF-Drehung) aus dem Dateikopf – liest nur die ersten ~192 KB.
     * Damit kann die Collage geplant werden, ohne alle Kandidaten komplett zu laden.
     */
    override suspend fun probeAspect(photo: Photo): Double? {
        val head = try {
            kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                serial { file(photo, context()).use { f -> f.inputStream.use { readUpTo(it, 192 * 1024) } } }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastError = "${photo.name}: " + explain(e)
            return null
        }
        return aspectFromHead(photo, head)
    }

    private suspend fun aspectFromHead(photo: Photo, head: ByteArray): Double? {
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(head, 0, head.size, o)
            var w = o.outWidth
            var h = o.outHeight
            if (w <= 0 || h <= 0) {
                // Kopf reicht nicht (z. B. manche HEIC) → ganz laden
                val full = load(photo, 400, null) ?: return null
                return full.aspect.toDouble().also { full.bitmap.recycle() }
            }
            val orientation = try {
                ExifInterface(ByteArrayInputStream(head)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
            if (orientation in setOf(
                    ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE,
                )
            ) { val t = w; w = h; h = t }
            return w.toDouble() / h
        } catch (e: Throwable) {
            lastError = "${photo.name}: " + explain(e)
            return null
        }
    }

    private fun readUpTo(input: java.io.InputStream, max: Int): ByteArray {
        val buf = ByteArray(max)
        var n = 0
        while (n < max) {
            val r = input.read(buf, n, max - n)
            if (r < 0) break
            n += r
        }
        return buf.copyOf(n)
    }

    /**
     * Liest die ganze Datei in großen Blöcken (1 MB pro Aufruf). Früher 8-KB-Häppchen = je eine
     * SMB-Anfrage – über VPN/WLAN mit Verzögerung dauerte ein 5-MB-Foto so mehrere Minuten.
     */
    private fun readBytes(photo: Photo, ctx: CIFSContext, onProgress: ((Long, Long) -> Unit)? = null): ByteArray? =
        file(photo, ctx).use { f ->
            val len = f.length()
            if (len > 40L * 1024 * 1024) return@use null
            if (len <= 0) return@use f.inputStream.use { it.readBytes() }
            val out = ByteArray(len.toInt())
            var n = 0
            f.inputStream.use { input ->
                while (n < out.size) {
                    if (Thread.interrupted()) throw InterruptedException()
                    val r = input.read(out, n, minOf(out.size - n, 1 shl 20))
                    if (r < 0) break
                    n += r
                    onProgress?.invoke(n.toLong(), len)
                }
            }
            if (n < out.size) out.copyOf(n) else out
        }

    /** Gesichtserkennung (Android-intern, offline) auf einer kleinen Kopie → Schwerpunkt der Gesichter. */
    private fun faceFocus(bmp: Bitmap): Pair<Float, Float>? = try {
        val w = 320
        val h = ((bmp.height * w) / bmp.width.toFloat()).toInt().let { it - it % 2 }.coerceAtLeast(2)
        var small = Bitmap.createScaledBitmap(bmp, w, h, true)
        if (small.config != Bitmap.Config.RGB_565) small = small.copy(Bitmap.Config.RGB_565, false)
        val faces = arrayOfNulls<android.media.FaceDetector.Face>(8)
        val n = android.media.FaceDetector(small.width, small.height, faces.size).findFaces(small, faces)
        var sx = 0f; var sy = 0f; var sw = 0f
        val p = android.graphics.PointF()
        for (i in 0 until n) {
            val f = faces[i] ?: continue
            if (f.confidence() < 0.4f) continue
            f.getMidPoint(p)
            val wt = f.eyesDistance()
            sx += p.x * wt; sy += (p.y + wt * 0.6f) * wt; sw += wt // Augenmitte → Gesichtsmitte
        }
        val res = if (sw == 0f) null else (sx / sw / small.width).coerceIn(0f, 1f) to (sy / sw / small.height).coerceIn(0f, 1f)
        if (small != bmp) small.recycle()
        res
    } catch (e: Throwable) {
        null
    }

    /** Auch für Fotos aus lokalen Ordnern genutzt. */
    internal fun decode(bytes: ByteArray, photo: Photo, maxEdge: Int): LoadedPhoto? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270f
            else -> 0f
        }
        if (deg != 0f) {
            val m = Matrix().apply { postRotate(deg) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            bmp = rotated
        }
        val focus = faceFocus(bmp)
        return LoadedPhoto(photo, bmp, focus?.first ?: 0.5f, focus?.second ?: 0.42f)
    }

    // ---------- Verbindungstest ----------

    /** Prüft Schritt für Schritt Netzwerk, Anmeldung, Freigabe, Ordner und ein Testbild. */
    suspend fun diagnose(report: (List<SmbStep>) -> Unit) = withContext(Dispatchers.IO) {
        val steps = ArrayList<SmbStep>()
        fun push(s: SmbStep) {
            if (steps.isNotEmpty() && steps.last().ok == null) steps[steps.lastIndex] = s else steps += s
            report(ArrayList(steps))
        }
        fun running(title: String) = push(SmbStep(title, null, "…"))

        val t = target()
        if (t.host.isBlank()) { push(SmbStep("Eingaben", false, "Kein Server eingetragen.")); return@withContext }
        push(SmbStep("Eingaben", true, "Adresse: ${t.url}" + if (t.share.isBlank()) "\nKeine Freigabe eingetragen." else ""))

        // 1) Name auflösen
        running("Server finden")
        val ip = try {
            InetAddress.getByName(t.host).hostAddress
        } catch (e: Exception) {
            push(SmbStep("Server finden", false, "„${t.host}“ nicht gefunden – IP-Adresse statt Namen verwenden.\n(${e.message})")); return@withContext
        }
        push(SmbStep("Server finden", true, "${t.host} → $ip"))

        // 2) Port 445
        running("SMB-Port 445")
        val portOk = try {
            Socket().use { it.connect(InetSocketAddress(ip, 445), 4000) }; true
        } catch (e: Exception) {
            push(SmbStep("SMB-Port 445", false, "Port 445 nicht erreichbar (${e.message}). SMB am Server aktiv? Gleiches Netzwerk/VLAN? Firewall?"))
            false
        }
        if (!portOk) return@withContext
        push(SmbStep("SMB-Port 445", true, "erreichbar"))

        // 3) Anmeldung + Freigaben auflisten
        running("Anmeldung")
        var ctx = buildContext(guest = prefs.smbGuest)
        var loginReported = false
        val shares: List<String>? = try {
            SmbFile("smb://${t.host}/", ctx).use { root -> root.listFiles().map { it.name.trimEnd('/') }.filterNot { it.endsWith("$") } }
        } catch (e: Exception) {
            if (prefs.smbUser.isBlank() && !prefs.smbGuest) {
                // anonym abgelehnt → als Gast probieren
                try {
                    val g = buildContext(guest = true)
                    val l = SmbFile("smb://${t.host}/", g).use { r -> r.listFiles().map { it.name.trimEnd('/') }.filterNot { it.endsWith("$") } }
                    ctx = g
                    prefs.smbGuest = true
                    cachedCtx = null
                    push(SmbStep("Anmeldung", true, "Anonym abgelehnt, als Gast ok – „Als Gast anmelden“ wurde automatisch eingeschaltet."))
                    loginReported = true
                    l
                } catch (e2: Exception) {
                    push(SmbStep("Anmeldung", false, explain(e))); return@withContext
                }
            } else {
                // Auflisten ist bei manchen Servern verboten – Anmeldung trotzdem über die Freigabe prüfen
                push(SmbStep("Anmeldung", true, "Freigaben-Liste nicht abrufbar (${e.message}) – Anmeldung wird direkt an der Freigabe geprüft."))
                loginReported = true
                null
            }
        }
        if (shares != null) {
            if (!loginReported) push(SmbStep("Anmeldung", true, "ok" + if (prefs.smbUser.isBlank()) " (ohne Benutzer)" else " als ${prefs.smbUser}"))
            push(SmbStep("Freigaben am Server", true, shares.joinToString(", ").ifBlank { "(keine sichtbar)" }))
        }
        if (t.share.isBlank()) { push(SmbStep("Freigabe", false, "Bitte eine der Freigaben oben eintragen.")); return@withContext }

        // 4) Freigabe + Ordner
        running("Ordner öffnen")
        val entries = try {
            SmbFile(t.url, ctx).use { it.listFiles().toList() }
        } catch (e: Exception) {
            val hint = if (shares != null && shares.none { it.equals(t.share, true) }) "\nVerfügbare Freigaben: ${shares.joinToString(", ")}" else ""
            push(SmbStep("Ordner öffnen", false, explain(e) + hint)); return@withContext
        }
        val dirs = entries.filter { it.name.endsWith("/") }
        val imgs = entries.filter { !it.name.endsWith("/") && it.name.substringAfterLast('.', "").lowercase() in exts }
        push(SmbStep("Ordner öffnen", true,
            "${entries.size} Einträge: ${imgs.size} Fotos, ${dirs.size} Unterordner" +
                if (dirs.isNotEmpty()) "\n" + dirs.take(8).joinToString(", ") { it.name.trimEnd('/') } + if (dirs.size > 8) " …" else "" else ""))

        // 5) Testbild laden – genau wie der Bildschirmschoner (gleiche Anmeldung, gleicher Dateizugriff)
        running("Testbild laden")
        var test: Photo? = imgs.firstOrNull()?.let { Photo(it.path, t.url, it.name.trimEnd('/')) }
        if (test == null) {
            for (d in dirs.take(10)) {
                test = try {
                    SmbFile(d.path, ctx).use { f ->
                        f.listFiles().firstOrNull { !it.name.endsWith("/") && it.name.substringAfterLast('.', "").lowercase() in exts }
                            ?.let { Photo(it.path, d.path, it.name) }
                    }
                } catch (e: Exception) { null }
                if (test != null) break
            }
        }
        entries.forEach { it.close() }
        if (test == null) { push(SmbStep("Testbild laden", false, "Keine Fotos (jpg, png, webp, heic) im Ordner oder den ersten Unterordnern gefunden.")); return@withContext }
        val start = System.currentTimeMillis()
        val aspect = probeAspect(test)
        val lp = load(test, 1280, null)
        if (lp == null || aspect == null) {
            push(SmbStep("Testbild laden", false, (lastError ?: "Unbekannter Fehler") +
                "\nHinweis: Der Bildschirmschoner nutzt die gespeicherte Anmeldung – Benutzer/Gast-Einstellung prüfen."))
        } else {
            push(SmbStep("Testbild laden", true,
                "${test.name}: ${lp.bitmap.width}×${lp.bitmap.height} in ${System.currentTimeMillis() - start} ms – Bildschirmschoner bereit"))
            lp.bitmap.recycle()
        }
    }
}
