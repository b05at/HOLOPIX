package at.b05.holopix.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import at.b05.holopix.Holo
import at.b05.holopix.MainActivity
import at.b05.holopix.R

/**
 * Läuft im Hintergrund (Vordergrunddienst mit Benachrichtigung) und erkennt Leerlauf:
 * Ein winziges, unsichtbares Overlay bekommt bei jeder Berührung irgendwo auf dem Bildschirm
 * ein ACTION_OUTSIDE-Ereignis. Nach der eingestellten Zeit ohne Berührung startet der Bildschirmschoner.
 */
class IdleService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var overlayKeepOn: Boolean? = null
    private var lastUpdateCheck = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Bildschirm an / entsperrt zählt als Bedienung
            Holo.onUserActivity()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            try { check() } catch (e: Exception) { }
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Holo.init(application)
        goForeground()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        })
        Holo.onUserActivity()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Holo.prefs.enabled) { stopSelf(); return START_NOT_STICKY }
        goForeground()
        updateOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) { }
        removeOverlay()
        super.onDestroy()
    }

    private fun check() {
        val p = Holo.prefs
        if (!p.enabled) { stopSelf(); return }
        updateOverlay()
        if (System.currentTimeMillis() - lastUpdateCheck > 6 * 3600_000L) { lastUpdateCheck = System.currentTimeMillis(); UpdateNotifier.check(this) }
        if (Holo.screensaverShowing) return
        val idle = System.currentTimeMillis() - Holo.lastActivity
        if (idle < p.idleMinutes.coerceAtLeast(1) * 60_000L) return
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isInteractive) return
        if (p.skipWhileMedia && getSystemService(AudioManager::class.java).isMusicActive) return
        if (p.onlyWhileCharging && !isCharging()) return
        if (!Holo.photos.ready) return
        if (!Settings.canDrawOverlays(this)) return // ohne diese Berechtigung darf Android die Activity nicht aus dem Hintergrund starten
        Holo.onUserActivity() // nicht sofort erneut auslösen, falls der Start scheitert
        Holo.startScreensaver(this)
    }

    private fun isCharging(): Boolean {
        val bm = getSystemService(BatteryManager::class.java)
        return bm.isCharging
    }

    // ---------- Overlay zur Berührungserkennung ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun updateOverlay() {
        if (!Settings.canDrawOverlays(this)) { removeOverlay(); return }
        val keepOn = Holo.prefs.keepScreenOn
        if (overlay != null && overlayKeepOn == keepOn) return
        removeOverlay()
        val wm = getSystemService(WindowManager::class.java)
        val v = View(this)
        v.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE || e.actionMasked == MotionEvent.ACTION_DOWN) Holo.onUserActivity()
            false
        }
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (keepOn) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            alpha = 0f
        }
        try { wm.addView(v, lp); overlay = v; overlayKeepOn = keepOn } catch (e: Exception) { overlay = null }
    }

    private fun removeOverlay() {
        overlay?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch (e: Exception) { } }
        overlay = null; overlayKeepOn = null
    }

    // ---------- Benachrichtigung ----------

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "HOLOPIX aktiv", NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false); description = "Hintergrunddienst für den Bildschirmschoner"
        })
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val now = PendingIntent.getActivity(this, 2, Intent(this, at.b05.holopix.ScreensaverActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("HOLOPIX aktiv")
            .setContentText("Bildschirmschoner nach ${Holo.prefs.idleMinutes} Min ohne Bedienung")
            .setContentIntent(open)
            .addAction(0, "Jetzt starten", now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        ServiceCompat.startForeground(
            this, 1, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    companion object {
        private const val CHANNEL = "holopix_service"

        fun start(c: Context) {
            try { c.startForegroundService(Intent(c, IdleService::class.java)) } catch (e: Exception) { }
        }

        fun stop(c: Context) { c.stopService(Intent(c, IdleService::class.java)) }

        /** Einstellungen geändert (Zeit, Display an lassen …). */
        fun refresh(c: Context) { if (Holo.prefs.enabled) start(c) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Holo.init(c.applicationContext as android.app.Application)
        if (Holo.prefs.enabled) IdleService.start(c)
    }
}
