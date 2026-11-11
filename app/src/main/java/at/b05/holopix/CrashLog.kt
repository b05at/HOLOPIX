package at.b05.holopix

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Absturzprotokoll: speichert den letzten Absturz (Stacktrace + Speicherstand) und startet den
 * Hintergrunddienst nach ein paar Sekunden neu – wichtig für ein Wandtablet ohne Bedienung.
 */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")

    fun install(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val rt = Runtime.getRuntime()
                val mem = "Speicher: ${(rt.totalMemory() - rt.freeMemory()) / 1048576} von ${rt.maxMemory() / 1048576} MB belegt"
                file(app).writeText(
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")) +
                        " · Version ${BuildConfig.VERSION_NAME} · Thread ${t.name}\n$mem\n\n" + sw.toString().take(12000)
                )
                restartSoon(app)
            } catch (x: Throwable) { }
            previous?.uncaughtException(t, e)
        }
    }

    /** Hintergrunddienst nach ein paar Sekunden neu starten (der Bildschirmschoner kommt dann von selbst wieder). */
    private fun restartSoon(c: Context) {
        if (!c.getSharedPreferences("holopix", Context.MODE_PRIVATE).getBoolean("enabled", false)) return
        val i = Intent(c, at.b05.holopix.service.IdleService::class.java)
        val pi = PendingIntent.getForegroundService(c, 4711, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        val am = c.getSystemService(AlarmManager::class.java)
        am.set(AlarmManager.RTC, System.currentTimeMillis() + 3000, pi)
    }

    fun read(c: Context): String? = file(c).takeIf { it.exists() }?.readText()
    fun clear(c: Context) { file(c).delete() }
}
