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
            if (i.action == Intent.ACTION_SCREEN_ON) {
                skipKeyguard()
                if (Holo.prefs.kioskMode) handler.postDelayed({ try { check() } catch (e: Exception) { } }, 300)
            }
        }
    }

    /** Wischsperre (ohne PIN/Muster) automatisch wegblenden – über eine kurz sichtbare, unsichtbare Activity. */
    private fun skipKeyguard() {
        if (!Holo.prefs.skipKeyguard || Holo.screensaverShowing) return
        val km = getSystemService(android.app.KeyguardManager::class.java) ?: return
        if (!km.isKeyguardLocked || km.isKeyguardSecure) return
        if (!Settings.canDrawOverlays(this)) return
        try {
            startActivity(Intent(this, at.b05.holopix.KeyguardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        } catch (e: Exception) { }
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
        if (intent?.action == ACTION_VISITOR) Holo.setVisitor(this, !Holo.prefs.visitorMode)
        if (!Holo.serviceNeeded()) { stopSelf(); return START_NOT_STICKY }
        goForeground()
        updateOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) { }
        removeOverlay()
        overlayShow.hide()
        updateWakeLock(false)
        Holo.serviceStatus = null
        super.onDestroy()
    }

    private fun check() {
        val p = Holo.prefs
        if (!Holo.serviceNeeded()) { stopSelf(); return }
        updateOverlay()
        if (System.currentTimeMillis() - lastUpdateCheck > 6 * 3600_000L) { lastUpdateCheck = System.currentTimeMillis(); UpdateNotifier.check(this) }
        val pm = getSystemService(PowerManager::class.java)
        val idle = System.currentTimeMillis() - Holo.lastActivity
        val need = p.idleMinutes.coerceAtLeast(1) * 60_000L
        fun status(s: String) { Holo.serviceStatus = s }
        // außerhalb eines Zeitraums (und ohne Besuchermodus): Bildschirmschoner startet trotzdem nach der Leerlaufzeit,
        // das Display wird aber nicht wach gehalten → Android schaltet nach seinem Zeitlimit ab
        val inSchedule = Holo.scheduleOn()
        updateWakeLock(inSchedule && (p.visitorMode || p.keepScreenOn))
        val outside = if (inSchedule) "" else {
            val ds = at.b05.holopix.display.DisplaySchedule
            " · außerhalb des Zeitplans (heute ${ds.todayText(ds.fromJson(p.displayPlan))}): Display geht nach dem Android-Zeitlimit aus"
        }
        if (!p.enabled && !p.visitorMode && !p.kioskMode) { status("Automatischer Start ist aus"); return }
        val kiosk = if (p.kioskMode) "Kiosk-Modus · " else ""
        if (Holo.screensaverShowing) { status("${kiosk}Bildschirmschoner läuft$outside"); return }
        if (!Settings.canDrawOverlays(this)) { status("Berechtigung „Über anderen Apps einblenden“ fehlt"); return }
        if (!Holo.photos.ready) { status("Keine Fotoquelle eingerichtet"); return }
        if (!pm.isInteractive) { status("${kiosk}Display ist aus"); return }
        if (p.kioskMode) {
            // Kiosk: sofort (ohne Leerlaufzeit) wieder anzeigen
            if (!overlayShow.show()) Holo.startScreensaver(this)
            return
        }
        if (idle < need) {
            status("Start in ${fmt(need - idle)} (ohne Bedienung seit ${fmt(idle)})" + outside + (Holo.lastStartError?.let { " · Letzter Fehler: $it" } ?: ""))
            return
        }
        if (!p.visitorMode && p.skipWhileMedia && getSystemService(AudioManager::class.java).isMusicActive) {
            status("Wartet: es wird gerade Musik/Ton abgespielt (Option „Nicht starten, während Musik/Video läuft“)"); return
        }
        if (!p.visitorMode && p.onlyWhileCharging && !isCharging()) { status("Wartet: Gerät lädt nicht (Option „Nur beim Laden“)"); return }
        Holo.onUserActivity() // nicht sofort erneut auslösen, falls der Start scheitert
        // Vollbild-Overlay: kann Android nicht als Hintergrundstart blockieren; Rückfall: Activity
        if (!overlayShow.show()) Holo.startScreensaver(this)
    }

    private val overlayShow by lazy { at.b05.holopix.screensaver.ScreensaverOverlay(this) }

    private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    // Display wach halten (zusätzlich zum Overlay-Flag – das ignorieren manche Geräte bei unsichtbaren Fenstern)
    private var wakeLock: PowerManager.WakeLock? = null
    @Suppress("DEPRECATION")
    private fun updateWakeLock(on: Boolean) {
        if (on && wakeLock == null) {
            wakeLock = try {
                getSystemService(PowerManager::class.java).newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "holopix:keep-on").apply { setReferenceCounted(false); acquire() }
            } catch (e: Exception) { null }
        } else if (!on && wakeLock != null) {
            try { wakeLock?.release() } catch (e: Exception) { }
            wakeLock = null
        }
    }

    private fun isCharging(): Boolean {
        val bm = getSystemService(BatteryManager::class.java)
        return bm.isCharging
    }

    // ---------- Overlay zur Berührungserkennung ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun updateOverlay() {
        if (!Settings.canDrawOverlays(this)) { removeOverlay(); return }
        // Display an lassen: Besuchermodus immer, sonst nur innerhalb des Zeitplans
        val keepOn = Holo.prefs.visitorMode || (Holo.prefs.keepScreenOn && Holo.scheduleOn())
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
        val visitor = PendingIntent.getService(this, 3, Intent(this, IdleService::class.java).setAction(ACTION_VISITOR), PendingIntent.FLAG_IMMUTABLE)
        val p = Holo.prefs
        val text = when {
            p.visitorMode -> "Besuchermodus an – Display bleibt an"
            p.enabled -> "Bildschirmschoner nach ${p.idleMinutes} Min ohne Bedienung"
            else -> "Display-Zeitplan aktiv"
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("HOLOPIX aktiv")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Jetzt starten", now)
            .addAction(0, if (p.visitorMode) "Besuchermodus aus" else "Besuchermodus an", visitor)
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
        const val ACTION_VISITOR = "at.b05.holopix.VISITOR"

        fun start(c: Context) {
            try { c.startForegroundService(Intent(c, IdleService::class.java)) } catch (e: Exception) { }
        }

        fun stop(c: Context) { c.stopService(Intent(c, IdleService::class.java)) }

        /** Einstellungen geändert (Zeit, Display an lassen …). */
        fun refresh(c: Context) { if (Holo.serviceNeeded()) start(c) else stop(c) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Holo.init(c.applicationContext as android.app.Application)
        if (Holo.serviceNeeded()) IdleService.start(c)
        at.b05.holopix.display.DisplaySchedule.reschedule(c)
    }
}
