package at.b05.holopix.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import at.b05.holopix.BuildConfig
import at.b05.holopix.data.Http
import at.b05.holopix.data.Prefs
import at.b05.holopix.data.fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class UpdateInfo(
    val version: String,
    val notes: String,
    val downloadUrl: String,
    val assetApiUrl: String,
    val size: Long,
)

/** Prüft GitHub-Releases auf eine neuere Version, lädt die APK und startet die Installation. */
class Updater(private val context: Context, private val prefs: Prefs) {

    private val repo = BuildConfig.UPDATE_REPO

    private fun Request.Builder.gh(): Request.Builder = apply {
        header("X-GitHub-Api-Version", "2022-11-28")
        header("User-Agent", "HOLOPIX-Updater")
        if (prefs.githubToken.isNotBlank()) header("Authorization", "Bearer ${prefs.githubToken}")
    }

    suspend fun check(): UpdateInfo? {
        val json = try {
            Request.Builder().url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json").gh().build().fetch()
        } catch (e: at.b05.holopix.data.HttpException) {
            if (e.code == 404) throw IOException("Kein Release gefunden (bei privatem Repo GitHub-Token eintragen)") else throw e
        }
        val o = JSONObject(json)
        val tag = o.optString("tag_name").removePrefix("v").removePrefix("V")
        val assets = o.optJSONArray("assets") ?: return null
        var asset: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) { asset = a; break }
        }
        asset ?: return null
        if (!isNewer(tag, BuildConfig.VERSION_NAME)) return null
        return UpdateInfo(
            version = tag,
            notes = o.optString("body"),
            downloadUrl = asset.optString("browser_download_url"),
            assetApiUrl = asset.optString("url"),
            size = asset.optLong("size"),
        )
    }

    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "HOLOPIX-${info.version}.apk")
        val req = if (prefs.githubToken.isNotBlank()) {
            Request.Builder().url(info.assetApiUrl).header("Accept", "application/octet-stream").gh().build()
        } else {
            Request.Builder().url(info.downloadUrl).header("User-Agent", "HOLOPIX-Updater").build()
        }
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download fehlgeschlagen: HTTP ${r.code}")
            val body = r.body ?: throw IOException("Leere Antwort")
            val total = body.contentLength().takeIf { it > 0 } ?: info.size
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        target
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    companion object {
        fun isNewer(remote: String, local: String): Boolean {
            fun parts(v: String) = v.split('.', '-', '+').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val a = parts(remote)
            val b = parts(local)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
