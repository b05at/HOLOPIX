package at.b05.holopix

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.b05.holopix.data.SmbSource
import at.b05.holopix.data.SmbStep
import at.b05.holopix.display.DayPlan
import at.b05.holopix.display.DisplaySchedule
import at.b05.holopix.screensaver.OverlayStyle
import at.b05.holopix.service.IdleService
import at.b05.holopix.update.UpdateInfo
import at.b05.holopix.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Zustand der Konfigurationsoberfläche. */
class SettingsVm(app: Application) : AndroidViewModel(app) {
    init { Holo.init(app) }
    val prefs = Holo.prefs
    private val ctx get() = getApplication<Application>()

    var message by mutableStateOf<String?>(null)

    private fun safe(block: suspend () -> Unit): Job = viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { message = e.message ?: e.javaClass.simpleName }
    }

    // ---------- Dienst ----------

    var enabled by mutableStateOf(prefs.enabled)
        private set

    fun setServiceEnabled(on: Boolean) {
        enabled = on; prefs.enabled = on
        IdleService.refresh(ctx)
    }

    // ---------- Besuchermodus ----------

    val visitorMode get() = Holo.visitorMode
    fun toggleVisitor() {
        Holo.setVisitor(ctx, !Holo.visitorMode)
        message = if (Holo.visitorMode) "Besuchermodus an – Display und Bildschirmschoner bleiben an, bis du ihn ausschaltest"
        else "Besuchermodus aus – Display-Zeitplan gilt wieder"
    }

    // ---------- Kiosk-Modus ----------

    val kioskMode get() = Holo.kioskMode
    fun toggleKiosk() {
        val on = !Holo.kioskMode
        if (on && !Holo.photos.ready) { message = "Zuerst eine Fotoquelle einrichten"; return }
        Holo.setKiosk(ctx, on)
        message = if (on) "Kiosk-Modus an – zum Beenden 4× schnell auf den Bildschirm tippen" else "Kiosk-Modus aus"
    }

    // ---------- Display-Zeitplan ----------

    var displayScheduleEnabled by mutableStateOf(prefs.displayScheduleEnabled)
        private set
    var displayPlans by mutableStateOf(DisplaySchedule.fromJson(prefs.displayPlan))
        private set

    fun setDisplaySchedule(on: Boolean) {
        displayScheduleEnabled = on; prefs.displayScheduleEnabled = on
        if (on && prefs.displayPlan.isBlank()) prefs.displayPlan = DisplaySchedule.toJson(displayPlans)
        DisplaySchedule.reschedule(ctx); IdleService.refresh(ctx)
    }

    fun setDayPlan(index: Int, plan: DayPlan) {
        displayPlans = displayPlans.toMutableList().also { it[index] = plan }
        prefs.displayPlan = DisplaySchedule.toJson(displayPlans)
        DisplaySchedule.reschedule(ctx)
    }

    fun copyDayPlanToAll(index: Int) {
        val src = displayPlans[index]
        displayPlans = List(7) { src }
        prefs.displayPlan = DisplaySchedule.toJson(displayPlans)
        DisplaySchedule.reschedule(ctx)
    }

    fun nextDisplayChangeText(): String? {
        if (!displayScheduleEnabled) return null
        val n = DisplaySchedule.nextChange(displayPlans, java.time.LocalDateTime.now()) ?: return null
        val on = DisplaySchedule.isOn(displayPlans, n)
        return "Nächste Änderung: Display ${if (on) "an" else "aus"} am " +
            n.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, HH:mm", java.util.Locale.GERMAN))
    }

    fun serviceSettingsChanged() = IdleService.refresh(ctx)

    fun startNow() = Holo.startScreensaver(ctx)

    /** Wird nach Rückkehr aus den Android-Einstellungen erhöht → Berechtigungen neu prüfen. */
    var permTick by mutableStateOf(0)

    // ---------- Fotoquellen ----------

    var photoCount by mutableStateOf<Int?>(Holo.collage.photoCount.takeIf { it > 0 })
    var smbSteps by mutableStateOf<List<SmbStep>>(emptyList())
    var smbTesting by mutableStateOf(false)
    var scanning by mutableStateOf(false)

    fun onSourcesChanged() { Holo.collage.clear(); photoCount = null }

    fun testSmb() {
        if (smbTesting) return
        smbTesting = true; smbSteps = emptyList()
        viewModelScope.launch {
            try { Holo.smb.diagnose { steps -> smbSteps = steps } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { smbSteps = smbSteps + SmbStep("Fehler", false, SmbSource.explain(e)) }
            finally { smbTesting = false }
        }
    }

    /** Alle Fotos einlesen und zählen. */
    fun scan() {
        if (scanning) return
        scanning = true
        viewModelScope.launch {
            try {
                Holo.collage.refresh { n -> photoCount = n }
                photoCount = Holo.collage.photoCount
                message = "${Holo.collage.photoCount} Fotos gefunden"
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                message = "Fotos: " + SmbSource.explain(e)
            } finally { scanning = false }
        }
    }

    var localFolders by mutableStateOf(prefs.localFolders)
        private set

    fun addLocalFolder(uri: Uri) {
        try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        val s = uri.toString()
        if (s in localFolders) return
        localFolders = localFolders + s; prefs.localFolders = localFolders
        onSourcesChanged(); scan()
    }

    fun removeLocalFolder(s: String) {
        try { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(s), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        localFolders = localFolders - s; prefs.localFolders = localFolders
        onSourcesChanged()
    }

    // ---------- Overlay ----------

    var overlayStyle by mutableStateOf(OverlayStyle(prefs.ssOverlayPos, prefs.ssOverlaySize, prefs.ssShowCaption, prefs.ssPixelShift))
        private set
    fun updateOverlayStyle(s: OverlayStyle) {
        overlayStyle = s
        prefs.ssOverlayPos = s.position; prefs.ssOverlaySize = s.size; prefs.ssShowCaption = s.showCaption; prefs.ssPixelShift = s.shift
    }

    // ---------- Wetter-Widget ----------

    val weather get() = Holo.weather
    fun setWeatherCity(name: String) = safe {
        val p = Holo.weatherApi.geocode(name)
        prefs.weatherCity = p.name; prefs.weatherLat = p.lat.toString(); prefs.weatherLon = p.lon.toString()
        Holo.refreshWeather(force = true)
        message = "Wetterort: ${p.name}"
    }
    fun refreshWeather() = safe { Holo.refreshWeather(force = true) }

    // ---------- Darstellung ----------

    var themeMode by mutableStateOf(prefs.themeMode)
        private set
    fun setTheme(mode: String) { themeMode = mode; prefs.themeMode = mode }

    // ---------- Updates ----------

    private val updater = Updater(app, prefs)
    var update by mutableStateOf<UpdateInfo?>(null)
    var updateProgress by mutableStateOf<Float?>(null)

    fun checkUpdate(silent: Boolean = false) = viewModelScope.launch {
        try {
            update = updater.check()
            if (update == null && !silent) message = "HOLOPIX ist aktuell (${BuildConfig.VERSION_NAME})"
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (!silent) message = e.message
        }
    }

    fun installUpdate() = safe {
        val u = update ?: return@safe
        if (!updater.canInstall()) {
            message = "Bitte „Unbekannte Apps installieren“ für HOLOPIX erlauben und erneut tippen"
            updater.openInstallPermissionSettings()
            return@safe
        }
        updateProgress = 0f
        try { updater.install(updater.download(u) { p -> updateProgress = p }) } finally { updateProgress = null }
    }

    // ---------- Absturz ----------

    var lastCrash by mutableStateOf(CrashLog.read(app))
        private set
    fun clearCrash() { CrashLog.clear(ctx); lastCrash = null }
}
