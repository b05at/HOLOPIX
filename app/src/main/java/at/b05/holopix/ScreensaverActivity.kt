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
import at.b05.holopix.screensaver.ScreensaverContent
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
        if (Holo.scheduleOn()) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyWake(intent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        show = SlideShow(lifecycleScope)
        setContent {
            ScreensaverContent(show, onTick = {
                // außerhalb eines Zeitraums: Display freigeben, Android schaltet nach seinem Zeitlimit ab
                if (Holo.scheduleOn()) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }, onDismiss = { finishScreensaver() })
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
        show.screen = { at.b05.holopix.screensaver.ScreenProfile.of(this) }
        show.start()
        widgetJob = lifecycleScope.launch {
            while (isActive) {
                if (Holo.prefs.ssShowWeather) Holo.refreshWeather()
                delay(60_000)
            }
        }
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
        if (!Holo.kioskMode) finishScreensaver()
        return true
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
