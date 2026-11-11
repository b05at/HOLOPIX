package at.b05.holopix.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import at.b05.holopix.Holo
import at.b05.holopix.MainActivity
import at.b05.holopix.R
import at.b05.holopix.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Prüft im Hintergrund auf neue Versionen und meldet sie per Benachrichtigung (Installation in den Einstellungen). */
object UpdateNotifier {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun check(c: Context) {
        if (!Holo.prefs.autoUpdate) return
        scope.launch {
            try {
                val info = Updater(c, Holo.prefs).check() ?: return@launch
                val nm = c.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel("holopix_update", "Updates", NotificationManager.IMPORTANCE_DEFAULT))
                val pi = PendingIntent.getActivity(c, 3, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                nm.notify(2, NotificationCompat.Builder(c, "holopix_update")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("HOLOPIX ${info.version} verfügbar")
                    .setContentText("Antippen zum Installieren")
                    .setContentIntent(pi).setAutoCancel(true).build())
            } catch (e: Exception) { }
        }
    }
}
