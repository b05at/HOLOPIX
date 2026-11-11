package at.b05.holopix.screensaver

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import at.b05.holopix.Holo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bildschirmschoner als Vollbild-Fenster „über anderen Apps“. Braucht nur die Overlay-Berechtigung –
 * anders als eine Activity kann Android den Start aus dem Hintergrund hier nicht blockieren.
 */
class ScreensaverOverlay(private val context: Context) {

    private class Owner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore = ViewModelStore()
        fun start() {
            saved.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        fun stop() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            viewModelStore.clear()
        }
    }

    private var view: ComposeView? = null
    private var owner: Owner? = null
    private var scope: CoroutineScope? = null
    private var params: WindowManager.LayoutParams? = null
    private var keepOn = true

    val showing get() = view != null

    @Suppress("DEPRECATION")
    fun show(): Boolean {
        if (view != null) return true
        val wm = context.getSystemService(WindowManager::class.java) ?: return false
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val slides = SlideShow(sc)
        val dm = context.resources.displayMetrics
        slides.screenAspect = dm.widthPixels.toFloat() / dm.heightPixels.coerceAtLeast(1)
        slides.maxEdge = maxOf(dm.widthPixels, dm.heightPixels).coerceIn(800, 2560)
        val o = Owner()
        val v = ComposeView(context)
        v.setViewTreeLifecycleOwner(o)
        v.setViewTreeSavedStateRegistryOwner(o)
        v.setViewTreeViewModelStoreOwner(o)
        // ganze Fensterfläche schwarz (auch neben/unter der Navigationsleiste – sonst bleiben dort Pixelreste sichtbar)
        v.setBackgroundColor(android.graphics.Color.BLACK)
        v.setContent { ScreensaverContent(slides, onTick = { updateKeepOn() }, onDismiss = { hide() }) }
        v.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE,
        ).apply {
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            windowAnimations = android.R.style.Animation_Dialog
        }
        return try {
            o.start()
            wm.addView(v, lp)
            hideBars(v)
            view = v; owner = o; scope = sc; params = lp; keepOn = true
            Holo.screensaverShowing = true
            Holo.screensaverFinisher = { hide() }
            slides.start()
            sc.launch {
                while (isActive) {
                    if (Holo.prefs.ssShowWeather) Holo.refreshWeather()
                    delay(60_000)
                }
            }
            true
        } catch (e: Exception) {
            o.stop(); sc.cancel()
            Holo.lastStartError = "Overlay: ${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    /** System- und Navigationsleiste ausblenden (Wischen vom Rand holt sie kurz zurück). */
    private fun hideBars(v: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            v.post {
                v.windowInsetsController?.let {
                    it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    it.hide(android.view.WindowInsets.Type.systemBars())
                }
            }
        }
    }

    /** Außerhalb des Zeitplans das Display freigeben (Android-Zeitlimit greift). */
    private fun updateKeepOn() {
        val v = view ?: return
        val lp = params ?: return
        val want = Holo.scheduleOn()
        if (want == keepOn) return
        keepOn = want
        lp.flags = if (want) lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        try { context.getSystemService(WindowManager::class.java).updateViewLayout(v, lp) } catch (e: Exception) { }
    }

    fun hide() {
        val v = view ?: return
        view = null
        try { context.getSystemService(WindowManager::class.java).removeView(v) } catch (e: Exception) { }
        owner?.stop(); owner = null
        scope?.cancel(); scope = null
        Holo.screensaverShowing = false
        Holo.screensaverFinisher = null
        Holo.onUserActivity()
    }
}
