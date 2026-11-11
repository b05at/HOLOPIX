package at.b05.holopix

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import at.b05.holopix.data.BringApi
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
        if (Holo.prefs.enabled) IdleService.start(this)
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
    lateinit var bring: BringApi
        private set

    fun init(a: Application) {
        if (::app.isInitialized) return
        app = a
        prefs = Prefs(a)
        smb = SmbSource(a, prefs)
        photos = MultiPhotoSource(smb, LocalPhotoSource(a, prefs, smb), prefs)
        collage = CollageEngine(photos)
        bring = BringApi(prefs)
    }

    /** Letzte Bedienung (Berührung irgendwo, Bildschirm eingeschaltet, Bildschirmschoner beendet). */
    @Volatile var lastActivity = System.currentTimeMillis()
    fun onUserActivity() { lastActivity = System.currentTimeMillis() }

    /** Bildschirmschoner ist gerade sichtbar. */
    @Volatile var screensaverShowing = false

    // ---------- Widgets (zwischengespeichert, damit der Bildschirmschoner sofort etwas zeigt) ----------

    var weather by mutableStateOf<Weather?>(null)
    var weatherTime = 0L
    /** Einkaufsliste (Bring!) – Artikelnamen. */
    var shopping by mutableStateOf<List<String>?>(null)
    var shoppingTime = 0L

    suspend fun refreshWeather(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - weatherTime < 30 * 60_000L && weather != null) return
        try {
            weather = weatherApi.forecast(prefs.weatherCity, prefs.weatherLat.toDouble(), prefs.weatherLon.toDouble())
            weatherTime = System.currentTimeMillis()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
    }

    suspend fun refreshShopping(force: Boolean = false) {
        if (!prefs.bringConfigured) { shopping = null; return }
        if (!force && System.currentTimeMillis() - shoppingTime < 5 * 60_000L && shopping != null) return
        try {
            if (prefs.bringToken.isBlank()) bring.login()
            if (prefs.bringListUuid.isBlank()) {
                val l = bring.lists().firstOrNull() ?: return
                prefs.bringListUuid = l.uuid; prefs.bringListName = l.name
            }
            shopping = bring.items().purchase.map { if (it.spec.isNotBlank()) "${it.name} (${it.spec})" else it.name }
            shoppingTime = System.currentTimeMillis()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
    }

    fun startScreensaver(c: Context) {
        c.startActivity(Intent(c, ScreensaverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }
}
