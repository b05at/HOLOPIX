package at.b05.holopix

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import at.b05.holopix.data.LocalPhotoSource
import at.b05.holopix.data.MultiPhotoSource
import at.b05.holopix.data.Prefs
import at.b05.holopix.data.SmbSource
import at.b05.holopix.data.Weather
import at.b05.holopix.data.WeatherApi
import at.b05.holopix.screensaver.CollageEngine
import at.b05.holopix.service.IdleService

class HolopixApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Holo.init(this)
        if (Holo.serviceNeeded()) IdleService.start(this)
        at.b05.holopix.display.DisplaySchedule.reschedule(this)
    }
}

/**
 * Gemeinsamer Zustand für Einstellungen, Hintergrunddienst und Bildschirmschoner
 * (alle laufen im selben Prozess).
 */
object Holo {
    lateinit var app: Application
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var smb: SmbSource
        private set
    lateinit var photos: MultiPhotoSource
        private set
    lateinit var collage: CollageEngine
        private set
    val weatherApi = WeatherApi()

    fun init(a: Application) {
        if (::app.isInitialized) return
        app = a
        prefs = Prefs(a)
        smb = SmbSource(a, prefs)
        photos = MultiPhotoSource(smb, LocalPhotoSource(a, prefs, smb), prefs)
        collage = CollageEngine(photos)
        visitorMode = prefs.visitorMode
    }

    /** Letzte Bedienung (Berührung irgendwo, Bildschirm eingeschaltet, Bildschirmschoner beendet). */
    @Volatile var lastActivity = System.currentTimeMillis()
    fun onUserActivity() { lastActivity = System.currentTimeMillis() }

    /** Was der Hintergrunddienst gerade macht / worauf er wartet (Anzeige in den Einstellungen). */
    var serviceStatus by mutableStateOf<String?>(null)
    /** Letzter Fehler beim Starten des Bildschirmschoners. */
    @Volatile var lastStartError: String? = null

    /** Bildschirmschoner ist gerade sichtbar. */
    @Volatile var screensaverShowing = false

    // ---------- Wetter-Widget (zwischengespeichert, damit der Bildschirmschoner sofort etwas zeigt) ----------

    var weather by mutableStateOf<Weather?>(null)
    var weatherTime = 0L

    suspend fun refreshWeather(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - weatherTime < 30 * 60_000L && weather != null) return
        try {
            weather = weatherApi.forecast(prefs.weatherCity, prefs.weatherLat.toDouble(), prefs.weatherLon.toDouble())
            weatherTime = System.currentTimeMillis()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
    }

    fun startScreensaver(c: Context, wake: Boolean = false) {
        try {
            c.startActivity(
                Intent(c, ScreensaverActivity::class.java).putExtra(ScreensaverActivity.EXTRA_WAKE, wake)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Exception) { }
    }

    /** Vom sichtbaren Bildschirmschoner gesetzt, damit Zeitplan/Dienst ihn beenden können. */
    @Volatile var screensaverFinisher: (() -> Unit)? = null
    fun finishScreensaver() { screensaverFinisher?.invoke() }

    // ---------- Besuchermodus & Zeitplan ----------

    var visitorMode by mutableStateOf(false)
        private set

    fun setVisitor(c: Context, on: Boolean) {
        visitorMode = on; prefs.visitorMode = on
        onUserActivity()
        IdleService.refresh(c)
    }

    /** Hintergrunddienst nötig: automatischer Bildschirmschoner, Zeitplan oder Besuchermodus. */
    fun serviceNeeded(): Boolean = prefs.enabled || prefs.displayScheduleEnabled || prefs.visitorMode

    /** Laut Zeitplan (oder Besuchermodus) soll das Display jetzt an sein. */
    fun scheduleOn(): Boolean = prefs.visitorMode || !prefs.displayScheduleEnabled ||
        at.b05.holopix.display.DisplaySchedule.isOn(
            at.b05.holopix.display.DisplaySchedule.fromJson(prefs.displayPlan), java.time.LocalDateTime.now())
}
