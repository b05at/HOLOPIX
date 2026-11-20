package at.b05.holopix

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle

/**
 * Unsichtbare Seite für Zeiträume "nur Display": schaltet das Display ein (auch über dem Sperrbildschirm),
 * blendet eine Wischsperre weg (wenn "Sperrbildschirm überspringen" an ist) und schließt sich sofort wieder –
 * danach ist zu sehen, was vorher offen war (z. B. Startbildschirm oder Dashboard).
 */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Holo.init(application)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        at.b05.holopix.display.DisplaySchedule.cancelWakeNotification(this)
        Holo.onUserActivity()
        val km = getSystemService(KeyguardManager::class.java)
        if (Holo.prefs.skipKeyguard && km != null && km.isKeyguardLocked && !km.isKeyguardSecure) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { done() }
                override fun onDismissCancelled() { done() }
                override fun onDismissError() { done() }
            })
            window.decorView.postDelayed({ done() }, 3000)
        } else {
            // kurz sichtbar bleiben, damit das Einschalten sicher greift
            window.decorView.postDelayed({ done() }, 1200)
        }
    }

    private fun done() { if (!isFinishing) finish() }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
