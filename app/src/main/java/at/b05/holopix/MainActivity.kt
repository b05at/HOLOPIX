package at.b05.holopix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import at.b05.holopix.ui.HolopixTheme
import at.b05.holopix.ui.SettingsScreen
import at.b05.holopix.ui.Z

/** Einzige Seite: die Konfiguration. */
class MainActivity : ComponentActivity() {
    private val vm: SettingsVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (vm.prefs.autoUpdate) vm.checkUpdate(silent = true)
        setContent {
            val sysDark = isSystemInDarkTheme()
            Z.dark = when (vm.themeMode) { "dark" -> true; "light" -> false; else -> sysDark }
            HolopixTheme { SettingsScreen(vm) }
        }
    }

    override fun onResume() {
        super.onResume()
        Holo.onUserActivity()
        vm.permTick++
    }
}
