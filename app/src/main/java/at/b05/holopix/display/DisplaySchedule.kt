package at.b05.holopix.display

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import at.b05.holopix.Holo
import at.b05.holopix.ScreensaverActivity
import at.b05.holopix.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Ein Zeitraum, in dem das Display an ist (über Mitternacht möglich, z. B. 22:00–01:00). */
data class OnWindow(val on: LocalTime, val off: LocalTime) {
    fun contains(t: LocalTime): Boolean = when {
        on == off -> true
        on.isBefore(off) -> !t.isBefore(on) && t.isBefore(off)
        else -> !t.isBefore(on) || t.isBefore(off)
    }
}

/**
 * Plan für einen Wochentag: ein oder mehrere Zeiträume, in denen das Display an ist
 * (z. B. 06:00–08:30 und 16:00–22:00). Tag nicht angehakt oder ohne Zeitraum = Display bleibt aus.
 */
data class DayPlan(val active: Boolean, val windows: List<OnWindow>) {
    // Kompatibilität mit älterem Code / Einstellungen mit nur einem Zeitraum
    constructor(active: Boolean, on: LocalTime, off: LocalTime) : this(active, listOf(OnWindow(on, off)))
}

object DisplaySchedule {

    val DEFAULT: List<DayPlan> = List(7) { i ->
        // Mo–Fr 06:00–22:00, Sa–So 07:00–22:30
        if (i < 5) DayPlan(true, LocalTime.of(6, 0), LocalTime.of(22, 0))
        else DayPlan(true, LocalTime.of(7, 0), LocalTime.of(22, 30))
    }

    fun toJson(plans: List<DayPlan>): String = JSONArray().apply {
        plans.forEach { p ->
            val w = JSONArray()
            p.windows.forEach { w.put(JSONObject().put("on", it.on.toString()).put("off", it.off.toString())) }
            put(JSONObject().put("a", p.active).put("w", w))
        }
    }.toString()

    fun fromJson(raw: String?): List<DayPlan> = try {
        val a = JSONArray(raw ?: "")
        if (a.length() != 7) DEFAULT else (0 until 7).map {
            val o = a.getJSONObject(it)
            val wins = o.optJSONArray("w")?.let { w ->
                (0 until w.length()).map { i -> w.getJSONObject(i).let { x -> OnWindow(LocalTime.parse(x.getString("on")), LocalTime.parse(x.getString("off"))) } }
            } ?: listOf(OnWindow(LocalTime.parse(o.getString("on")), LocalTime.parse(o.getString("off")))) // altes Format
            DayPlan(o.getBoolean("a"), wins)
        }
    } catch (e: Exception) {
        DEFAULT
    }

    /** Soll das Display zu diesem Zeitpunkt laut Plan an sein? (Index 0 = Montag) */
    fun isOn(plans: List<DayPlan>, at: LocalDateTime): Boolean {
        val p = plans[at.dayOfWeek.value - 1]
        // Tag nicht angehakt oder ohne Zeitraum: Display bleibt aus (ganztägig an = Zeitraum 00:00–00:00)
        if (!p.active || p.windows.isEmpty()) return false
        val t = at.toLocalTime()
        return p.windows.any { it.contains(t) }
    }

    /** Nächster Zeitpunkt, an dem sich der Soll-Zustand ändert (innerhalb von 8 Tagen). */
    fun nextChange(plans: List<DayPlan>, from: LocalDateTime): LocalDateTime? {
        val now = isOn(plans, from)
        val candidates = ArrayList<LocalDateTime>()
        for (d in 0..8) {
            val day: LocalDate = from.toLocalDate().plusDays(d.toLong())
            val p = plans[day.dayOfWeek.value - 1]
            candidates += day.atStartOfDay()
            p.windows.forEach { w -> candidates += day.atTime(w.on); candidates += day.atTime(w.off) }
        }
        return candidates.filter { it.isAfter(from) }.sorted().firstOrNull { isOn(plans, it) != now }
    }

    // ---------- Wecker für das Ein-/Ausschalten (auch wenn das Display aus ist) ----------

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 4711, Intent(context, DisplayAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun reschedule(context: Context) {
        val prefs = Prefs(context)
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(context))
        if (!prefs.displayScheduleEnabled) return
        val next = nextChange(fromJson(prefs.displayPlan), LocalDateTime.now()) ?: return
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + 1000
        if (exactAlarmsAllowed(context)) {
            // Wecker-Modus: wird auch im Tiefschlaf (Doze) pünktlich ausgelöst
            val show = PendingIntent.getActivity(context, 4713, Intent(context, ScreensaverActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending(context))
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context))
        }
    }

    fun exactAlarmsAllowed(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    fun notificationsAllowed(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun fullScreenAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()

    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    const val WAKE_NOTIFICATION_ID = 4712
    private const val WAKE_CHANNEL = "display_wake"

    /**
     * Bildschirm einschalten – zwei Wege, weil Android je nach Version/Hersteller unterschiedlich streng ist:
     * 1. Wake-Lock mit ACQUIRE_CAUSES_WAKEUP (ältere Android-Versionen)
     * 2. Vollbild-Benachrichtigung wie bei Wecker-Apps: startet den Bildschirmschoner und schaltet das Display ein (Android 10–15)
     */
    @Suppress("DEPRECATION")
    fun wakeScreen(context: Context) {
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        try {
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "holopix:display-on",
            ).acquire(10_000)
        } catch (e: Exception) { }
        if (pm.isInteractive) return // Display ist schon an
        try {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            if (nm.getNotificationChannel(WAKE_CHANNEL) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(WAKE_CHANNEL, "Display einschalten", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Schaltet das Display laut Zeitplan ein"
                        setSound(null, null); enableVibration(false); setShowBadge(false)
                    }
                )
            }
            val pi = PendingIntent.getActivity(
                context, 4714,
                Intent(context, ScreensaverActivity::class.java).putExtra(ScreensaverActivity.EXTRA_WAKE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = androidx.core.app.NotificationCompat.Builder(context, WAKE_CHANNEL)
                .setSmallIcon(at.b05.holopix.R.drawable.ic_notification)
                .setContentTitle("HOLOPIX")
                .setContentText("Display wird eingeschaltet")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(20_000)
                .build()
            nm.notify(WAKE_NOTIFICATION_ID, n)
        } catch (e: Exception) { }
    }

    fun cancelWakeNotification(context: Context) {
        try { context.getSystemService(android.app.NotificationManager::class.java).cancel(WAKE_NOTIFICATION_ID) } catch (e: Exception) { }
    }

    private const val ACTION_TEST = "at.b05.holopix.WAKE_TEST"

    /** Test: in 15 Sekunden einschalten (vorher Display ausschalten). */
    fun scheduleTestWake(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, 4715, Intent(context, DisplayAlarmReceiver::class.java).setAction(ACTION_TEST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = System.currentTimeMillis() + 15_000
        if (exactAlarmsAllowed(context)) {
            val show = PendingIntent.getActivity(context, 4713, Intent(context, ScreensaverActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun isTest(intent: Intent) = intent.action == ACTION_TEST
}

/** Wird zum Umschaltzeitpunkt ausgelöst: weckt das Display (Bildschirmschoner) bzw. schaltet es aus und plant den nächsten Wecker. */
class DisplayAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Holo.init(context.applicationContext as android.app.Application)
        val prefs = Holo.prefs
        if (DisplaySchedule.isTest(intent)) {
            DisplaySchedule.wakeScreen(context)
            Holo.startScreensaver(context, wake = true)
            return
        }
        if (prefs.displayScheduleEnabled) {
            if (DisplaySchedule.isOn(DisplaySchedule.fromJson(prefs.displayPlan), LocalDateTime.now())) {
                Holo.onUserActivity()
                DisplaySchedule.wakeScreen(context)
                if (Holo.photos.ready) Holo.startScreensaver(context, wake = true)
            }
            // Ende eines Zeitraums: nichts erzwingen – der Bildschirmschoner gibt das Display frei
            // und Android schaltet es nach dem eingestellten Zeitlimit aus.
        }
        DisplaySchedule.reschedule(context)
    }
}
