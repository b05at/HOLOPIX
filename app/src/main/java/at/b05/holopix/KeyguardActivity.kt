package at.b05.holopix

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle

/**
 * Unsichtbare Activity, die nach dem Einschalten des Displays die Wischsperre wegblendet
 * (Android erlaubt das nur einer sichtbaren Activity). Mit PIN/Muster bleibt der Sperrbildschirm.
 */
class KeyguardActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) { finish(); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { finish() }
            override fun onDismissCancelled() { finish() }
            override fun onDismissError() { finish() }
        })
        window.decorView.postDelayed({ if (!isFinishing) finish() }, 3000)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
