package at.b05.holopix.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import at.b05.holopix.BuildConfig
import at.b05.holopix.Holo
import at.b05.holopix.data.MqttLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sendet den Akkustand per MQTT:
 *   <Thema>/battery   → 0–100
 *   <Thema>/charging  → ON / OFF
 *   <Thema>/state     → {"battery":87,"charging":true,"temperature":28.5}
 * optional mit Home-Assistant-Discovery (Sensoren erscheinen automatisch unter MQTT).
 */
object BatteryMqtt {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastSent = 0L
    @Volatile private var lastValue: String? = null

    data class Battery(val level: Int, val charging: Boolean, val temperature: Double?)

    fun read(c: Context): Battery {
        val i: Intent? = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) l * 100 / s else -1
        } ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val charging = plugged || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
        return Battery(level, charging, temp)
    }

    /** Eindeutige Geräte-ID (für Client-ID, Thema und Home Assistant). */
    fun deviceId(c: Context): String =
        (Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: "tablet").take(8)

    fun defaultTopic(c: Context) = "holopix/" + deviceId(c)

    private fun topic(c: Context) = Holo.prefs.mqttTopic.trim().trimEnd('/').ifBlank { defaultTopic(c) }

    /** Im Takt des Dienstes aufrufen: sendet nach Ablauf des Intervalls oder bei Änderung des Ladezustands. */
    fun tick(c: Context) {
        val p = Holo.prefs
        if (!p.mqttEnabled || p.mqttHost.isBlank()) return
        val b = read(c)
        val value = "${b.level}/${b.charging}"
        val due = System.currentTimeMillis() - lastSent >= p.mqttIntervalMinutes.coerceAtLeast(1) * 60_000L
        val changedCharging = lastValue?.substringAfter('/') != b.charging.toString()
        if (!due && !changedCharging) return
        lastSent = System.currentTimeMillis(); lastValue = value
        scope.launch { try { send(c, b) } catch (e: Exception) { } }
    }

    /** Sofort senden (Test-Knopf). Gibt eine Meldung für die Oberfläche zurück. */
    suspend fun sendNow(c: Context): String = withContext(Dispatchers.IO) {
        val b = read(c)
        try {
            send(c, b)
            lastSent = System.currentTimeMillis(); lastValue = "${b.level}/${b.charging}"
            "Gesendet: Akku ${b.level} %${if (b.charging) ", lädt" else ""} → ${topic(c)}/battery"
        } catch (e: Exception) {
            "MQTT-Fehler: " + explain(e)
        }
    }

    private fun send(c: Context, b: Battery) {
        val p = Holo.prefs
        val (host, port) = parseHost(p.mqttHost, p.mqttPort)
        val t = topic(c)
        val id = deviceId(c)
        val msgs = mutableListOf(
            MqttLite.Message("$t/battery", b.level.toString()),
            MqttLite.Message("$t/charging", if (b.charging) "ON" else "OFF"),
            MqttLite.Message("$t/state", JSONObject().put("battery", b.level).put("charging", b.charging)
                .apply { b.temperature?.let { put("temperature", it) } }.toString()),
        )
        if (p.mqttDiscovery) msgs += discovery(c, t, id)
        try {
            MqttLite(host, port, p.mqttUser, p.mqttPassword, "holopix-$id").publish(msgs)
            p.mqttLastResult = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM. HH:mm")) +
                " · Akku ${b.level} %${if (b.charging) ", lädt" else ""} gesendet ✓"
        } catch (e: Exception) {
            p.mqttLastResult = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM. HH:mm")) +
                " · Fehler: " + explain(e)
            throw e
        }
    }

    /** Home-Assistant-Discovery: Akku (%), lädt (an/aus), Akkutemperatur. */
    private fun discovery(c: Context, t: String, id: String): List<MqttLite.Message> {
        val name = Holo.prefs.mqttDeviceName.ifBlank { "HOLOPIX " + android.os.Build.MODEL }
        val device = JSONObject()
            .put("identifiers", JSONArray().put("holopix_$id"))
            .put("name", name)
            .put("manufacturer", android.os.Build.MANUFACTURER)
            .put("model", android.os.Build.MODEL)
            .put("sw_version", "HOLOPIX " + BuildConfig.VERSION_NAME)
        fun cfg(component: String, key: String, o: JSONObject) =
            MqttLite.Message("homeassistant/$component/holopix_$id/$key/config", o.put("device", device).toString())
        return listOf(
            cfg("sensor", "battery", JSONObject().put("name", "Akku").put("unique_id", "holopix_${id}_battery")
                .put("state_topic", "$t/battery").put("unit_of_measurement", "%").put("device_class", "battery")
                .put("state_class", "measurement")),
            cfg("binary_sensor", "charging", JSONObject().put("name", "Lädt").put("unique_id", "holopix_${id}_charging")
                .put("state_topic", "$t/charging").put("payload_on", "ON").put("payload_off", "OFF")
                .put("device_class", "battery_charging")),
            cfg("sensor", "battery_temperature", JSONObject().put("name", "Akkutemperatur").put("unique_id", "holopix_${id}_battery_temp")
                .put("state_topic", "$t/state").put("value_template", "{{ value_json.temperature }}")
                .put("unit_of_measurement", "°C").put("device_class", "temperature").put("state_class", "measurement")
                .put("entity_category", "diagnostic")),
        )
    }

    /** „192.168.1.10“, „192.168.1.10:1884“ oder „mqtt://broker“ → Host + Port. */
    fun parseHost(raw: String, defPort: Int): Pair<String, Int> {
        val h = raw.trim().removePrefix("mqtt://").removePrefix("tcp://").trimEnd('/')
        val port = h.substringAfterLast(':', "").toIntOrNull()
        return if (port != null && h.count { it == ':' } == 1) h.substringBeforeLast(':') to port else h to defPort
    }

    private fun explain(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "Broker-Adresse unbekannt"
        is java.net.ConnectException -> "Broker nicht erreichbar (IP/Port prüfen)"
        is java.net.SocketTimeoutException -> "Zeitüberschreitung – Broker antwortet nicht"
        is java.io.EOFException -> "Broker hat die Verbindung geschlossen (Benutzer/Passwort oder Client-ID prüfen)"
        else -> e.message ?: e.javaClass.simpleName
    }
}
