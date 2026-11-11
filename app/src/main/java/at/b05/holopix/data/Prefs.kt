package at.b05.holopix.data

import android.content.Context
import org.json.JSONArray
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("holopix", Context.MODE_PRIVATE)

    private inner class S(val key: String, val def: String = "") : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { sp.edit().putString(key, value).apply() }
    }
    private inner class I(val key: String, val def: Int) : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { sp.edit().putInt(key, value).apply() }
    }
    private inner class B(val key: String, val def: Boolean) : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) { sp.edit().putBoolean(key, value).apply() }
    }

    // Hintergrunddienst
    /** Bildschirmschoner automatisch starten (Hintergrunddienst läuft). */
    var enabled by B("enabled", false)
    /** Leerlaufzeit bis zum Start (Minuten). */
    var idleMinutes by I("idle_min", 5)
    /** Bildschirm nicht von Android abschalten lassen, solange HOLOPIX aktiv ist. */
    var keepScreenOn by B("keep_on", true)
    /** Nicht starten, während Musik/Video läuft. */
    var skipWhileMedia by B("skip_media", true)
    /** Nur starten, während das Gerät lädt. */
    var onlyWhileCharging by B("only_charging", false)

    // SMB
    var smbHost by S("smb_host")
    var smbShare by S("smb_share")
    var smbPath by S("smb_path")
    var smbUser by S("smb_user")
    var smbPassword by S("smb_pw")
    var smbDomain by S("smb_domain")
    var smbAllowSmb1 by B("smb_v1", false)
    var smbGuest by B("smb_guest", false)

    /** Lokale Foto-Ordner (Android-Ordnerauswahl, content://…/tree/…). */
    var localFolders: List<String>
        get() = try { JSONArray(sp.getString("local_dirs", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (e: Exception) { emptyList() }
        set(v) { sp.edit().putString("local_dirs", JSONArray(v).toString()).apply() }

    // Ablauf & Overlay
    var collageSeconds by I("ss_seconds", 15)
    var ssShowClock by B("ss_clock", true)
    var ssShowDate by B("ss_date", true)
    var ssShowWeather by B("ss_weather", false)
    var ssOverlayPos by S("ss_pos", "split")   // bl | bc | br | tl | tr | split
    var ssOverlaySize by I("ss_size", 1)       // 0 klein, 1 mittel, 2 groß
    var ssShowCaption by B("ss_caption", true)
    var ssPixelShift by B("ss_shift", true)

    // Wetter-Widget (Open-Meteo)
    var weatherCity by S("w_city", "Oberpullendorf")
    var weatherLat by S("w_lat", "47.5034")
    var weatherLon by S("w_lon", "16.5034")

    // Darstellung & Updates
    var themeMode by S("theme", "system")
    var autoUpdate by B("auto_update", true)
    var githubToken by S("gh_token")

    val smbConfigured: Boolean get() = SmbSource.parse(smbHost, smbShare, smbPath).let { it.host.isNotBlank() && it.share.isNotBlank() }
}
