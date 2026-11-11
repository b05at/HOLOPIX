package at.b05.holopix

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import at.b05.holopix.screensaver.OverlayStyle
import at.b05.holopix.screensaver.ScreensaverView
import at.b05.holopix.screensaver.SlideShow
import at.b05.holopix.screensaver.weatherLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Vollbild-Bildschirmschoner. Antippen oder eine Taste beendet ihn – zurück zur vorherigen App. */
class ScreensaverActivity : ComponentActivity() {

    private lateinit var show: SlideShow
    private var widgetJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Holo.init(application)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyWake(intent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        show = SlideShow(lifecycleScope)
        val p = Holo.prefs
        setContent {
            val now by produceState(LocalDateTime.now()) { while (true) { value = LocalDateTime.now(); delay(1000) } }
            val info = buildList<Pair<androidx.compose.ui.graphics.vector.ImageVector, String>> {
                if (p.ssShowWeather) Holo.weather?.let { add(weatherLine(it)) }
            }
            ScreensaverView(
                slide = show.slide,
                clock = if (p.ssShowClock) now.format(DateTimeFormatter.ofPattern("HH:mm")) else null,
                date = if (p.ssShowDate) now.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)) else null,
                info = info,
                status = show.status,
                style = OverlayStyle(p.ssOverlayPos, p.ssOverlaySize, p.ssShowCaption, p.ssPixelShift),
                onDismiss = { finishScreensaver() },
            )
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        applyWake(intent)
    }

    /** Vom Zeitplan gestartet: Display einschalten (über dem Sperrbildschirm). */
    private fun applyWake(i: android.content.Intent?) {
        if (i?.getBooleanExtra(EXTRA_WAKE, false) == true) setTurnScreenOn(true)
        at.b05.holopix.display.DisplaySchedule.cancelWakeNotification(this)
    }

    override fun onStart() {
        super.onStart()
        Holo.screensaverShowing = true
        Holo.screensaverFinisher = { runOnUiThread { if (!isFinishing) finish() } }
        val dm = resources.displayMetrics
        show.screenAspect = dm.widthPixels.toFloat() / dm.heightPixels.coerceAtLeast(1)
        show.maxEdge = maxOf(dm.widthPixels, dm.heightPixels).coerceIn(800, 2560)
        show.start()
        widgetJob = lifecycleScope.launch {
            while (isActive) {
                if (Holo.prefs.ssShowWeather) Holo.refreshWeather()
                delay(60_000)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val dm = resources.displayMetrics
        show.screenAspect = dm.widthPixels.toFloat() / dm.heightPixels.coerceAtLeast(1)
    }

    override fun onStop() {
        super.onStop()
        widgetJob?.cancel()
        show.stop()
        Holo.screensaverShowing = false
        Holo.screensaverFinisher = null
        Holo.onUserActivity()
        // nicht im Hintergrund liegen lassen – beim nächsten Leerlauf neu starten
        if (!isChangingConfigurations) finish()
    }

    companion object {
        const val EXTRA_WAKE = "wake"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        finishScreensaver(); return true
    }

    private fun finishScreensaver() {
        Holo.onUserActivity()
        // Sperrbildschirm überspringen: Wischsperre beim Beenden wegblenden
        if (Holo.prefs.skipKeyguard) {
            val km = getSystemService(android.app.KeyguardManager::class.java)
            if (km != null && km.isKeyguardLocked && !km.isKeyguardSecure) try { km.requestDismissKeyguard(this, null) } catch (e: Exception) { }
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
    }
}
