@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package at.b05.holopix.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.b05.holopix.BuildConfig
import at.b05.holopix.SettingsVm
import at.b05.holopix.data.LocalPhotoSource
import at.b05.holopix.data.SmbSource
import at.b05.holopix.data.SmbStep
import at.b05.holopix.screensaver.weatherLine
import kotlinx.coroutines.delay

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Z.Card, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Z.Text)
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 17.sp, color = Z.Text)
        Switch(checked, onChange)
    }
}

@Composable
private fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMinus) { Icon(Icons.Rounded.Remove, "weniger") }
        Text(label, fontSize = 18.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = Z.Text)
        IconButton(onClick = onPlus) { Icon(Icons.Rounded.Add, "mehr") }
    }
}

@Composable
private fun Hint(text: String) = Text(text, color = Z.TextSoft, fontSize = 13.sp)

@Composable
private fun DisposableSave(save: () -> Unit) {
    val current = rememberUpdatedState(save)
    DisposableEffect(Unit) { onDispose { current.value() } }
}

@Composable
fun SettingsScreen(vm: SettingsVm) {
    val p = vm.prefs
    Box(Modifier.fillMaxSize().background(Z.Bg)) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 900.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Header(vm)
                vm.lastCrash?.let { CrashSection(vm, it) }
                ServiceSection(vm)
                VisitorSection(vm)
                DisplayScheduleSection(vm)
                LocalFolderSection(vm)
                SmbSection(vm)
                Section("Ablauf") {
                    var seconds by remember { mutableIntStateOf(p.collageSeconds) }
                    Stepper("Bildwechsel alle $seconds s",
                        onMinus = { seconds = (seconds - 5).coerceAtLeast(5); p.collageSeconds = seconds },
                        onPlus = { seconds += 5; p.collageSeconds = seconds })
                    Hint("Collagen: bis zu 5 Fotos aus demselben Ordner, passend zum Bildschirmformat; Serienbilder werden nicht nebeneinander gezeigt, " +
                        "Gesichter bleiben beim Zuschneiden im Bild. Passt kein Foto, wird es ganz mit unscharfem Hintergrund gezeigt.")
                }
                OverlaySection(vm)
                WeatherSection(vm)
                Section("Darstellung") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to "Wie System", "light" to "Hell", "dark" to "Dunkel").forEach { (k, l) ->
                            FilterChip(selected = vm.themeMode == k, onClick = { vm.setTheme(k) }, label = { Text(l) })
                        }
                    }
                }
                UpdateSection(vm)
                Spacer(Modifier.height(60.dp))
            }
        }
        vm.message?.let { msg ->
            LaunchedEffect(msg) { delay(5000); if (vm.message == msg) vm.message = null }
            Snackbar(
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                action = { TextButton(onClick = { vm.message = null }) { Text("OK") } },
            ) { Text(msg) }
        }
    }
}

@Composable
private fun Header(vm: SettingsVm) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        // kleines Logo: drei Collage-Kacheln
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.size(16.dp, 34.dp).background(Color(0xFF4C8DF6), RoundedCornerShape(4.dp)))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.size(14.dp, 15.5.dp).background(Color(0xFF26A69A), RoundedCornerShape(4.dp)))
                Box(Modifier.size(14.dp, 15.5.dp).background(Color(0xFFF5B942), RoundedCornerShape(4.dp)))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("HOLOPIX", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Z.Text)
            Text("Foto-Collage als Bildschirmschoner", fontSize = 14.sp, color = Z.TextSoft)
        }
        // Besuchermodus: nur ein Symbol, farbig hinterlegt wenn aktiv
        IconButton(
            onClick = { vm.toggleVisitor() },
            modifier = Modifier.clip(CircleShape).background(if (vm.visitorMode) Z.Primary else Color.Transparent),
        ) {
            Icon(Icons.Rounded.Celebration, if (vm.visitorMode) "Besuchermodus an – zum Ausschalten tippen" else "Besuchermodus einschalten",
                tint = if (vm.visitorMode) Z.OnPrimary else Z.TextSoft)
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { vm.startNow() }) { Text("Jetzt starten") }
    }
}

// ---------------- Hintergrunddienst & Berechtigungen ----------------

@Composable
private fun ServiceSection(vm: SettingsVm) {
    val p = vm.prefs
    val ctx = LocalContext.current
    Section("Automatisch starten") {
        SwitchRow("HOLOPIX im Hintergrund aktiv", vm.enabled) { vm.setServiceEnabled(it) }
        var minutes by remember { mutableIntStateOf(p.idleMinutes) }
        Stepper("Start nach $minutes Min ohne Bedienung",
            onMinus = { minutes = (minutes - 1).coerceAtLeast(1); p.idleMinutes = minutes; vm.serviceSettingsChanged() },
            onPlus = { minutes += 1; p.idleMinutes = minutes; vm.serviceSettingsChanged() })
        var keepOn by remember { mutableStateOf(p.keepScreenOn) }
        var media by remember { mutableStateOf(p.skipWhileMedia) }
        var charging by remember { mutableStateOf(p.onlyWhileCharging) }
        SwitchRow("Display nicht automatisch ausschalten", keepOn) { keepOn = it; p.keepScreenOn = it; vm.serviceSettingsChanged() }
        SwitchRow("Nicht starten, während Musik/Video läuft", media) { media = it; p.skipWhileMedia = it }
        SwitchRow("Nur starten, während das Gerät lädt", charging) { charging = it; p.onlyWhileCharging = it }

        // Android-Bildschirm-Zeitlimit kürzer als die Leerlaufzeit → Display geht vorher aus
        val tick = vm.permTick
        val sysTimeout = remember(tick) {
            try { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) } catch (e: Exception) { -1 }
        }
        if (!keepOn && sysTimeout in 1 until minutes * 60_000) {
            Text("Achtung: Android schaltet das Display schon nach ${sysTimeout / 1000} s aus – vorher kommt der Bildschirmschoner nicht. " +
                "„Display nicht automatisch ausschalten“ einschalten oder die Bildschirm-Zeitüberschreitung in den Android-Einstellungen verlängern.",
                color = Z.Warn, fontSize = 14.sp)
        }
        var skipLock by remember { mutableStateOf(p.skipKeyguard) }
        SwitchRow("Sperrbildschirm überspringen", skipLock) { skipLock = it; p.skipKeyguard = it }
        Hint("Der Bildschirmschoner erscheint über dem Sperrbildschirm; eine Wischsperre (ohne PIN/Muster) wird beim Einschalten und beim Beenden " +
            "automatisch weggeblendet. Mit PIN/Muster lässt Android das aus Sicherheitsgründen nicht zu.")
        Permissions(vm)
    }
}

@Composable
private fun VisitorSection(vm: SettingsVm) {
    Section("Besuchermodus") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (vm.visitorMode) "An – Display und Bildschirmschoner bleiben an, der Zeitplan ist pausiert."
                else "Aus – Display und Bildschirmschoner unbegrenzt an lassen, bis du ihn wieder ausschaltest. Danach greift wieder der Zeitplan.",
                Modifier.weight(1f), color = Z.Text, fontSize = 15.sp,
            )
            Switch(vm.visitorMode, { vm.toggleVisitor() })
        }
        Hint("Auch über das Konfetti-Symbol oben oder die Benachrichtigung „HOLOPIX aktiv“ schaltbar.")
    }
}

@Composable
private fun Permissions(vm: SettingsVm) {
    val ctx = LocalContext.current
    val back = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.permTick++ }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.permTick++ }
    val tick = vm.permTick
    fun open(action: String, withPkg: Boolean = true) {
        val i = Intent(action).apply { if (withPkg) data = Uri.parse("package:" + ctx.packageName) }
        try { back.launch(i) } catch (e: Exception) {
            back.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        }
    }
    val overlay = remember(tick) { Settings.canDrawOverlays(ctx) }
    val battery = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val notifOk = remember(tick) {
        android.os.Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    Text("Berechtigungen", color = Z.Text, fontWeight = FontWeight.Medium)
    PermRow("Über anderen Apps einblenden (nötig: erkennt Berührungen und startet den Bildschirmschoner)", overlay) {
        open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
    }
    PermRow("Akku-Optimierung aus (Dienst wird nicht beendet)", battery) {
        open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    }
    if (android.os.Build.VERSION.SDK_INT >= 33) PermRow("Benachrichtigungen (Hinweis „HOLOPIX aktiv“, Updates)", notifOk) {
        notif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "!", color = if (ok) Z.Primary else Z.Warn, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.width(26.dp))
        Text(label, Modifier.weight(1f), color = Z.Text, fontSize = 15.sp)
        if (!ok) OutlinedButton(onClick = onFix) { Text("Erlauben") }
    }
}

// ---------------- Fotoquellen ----------------

@Composable
private fun LocalFolderSection(vm: SettingsVm) {
    Section("Fotoquelle · Ordner am Gerät") {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.addLocalFolder(uri) }
        Hint("Ordner der Galerie wählen, z. B. DCIM/Camera, Pictures oder ein Album-Ordner – Unterordner werden mitgenommen. " +
            "Mehrere Ordner möglich; zusammen mit der SMB-Freigabe werden alle Fotos gemischt. Keine Speicher-Berechtigung nötig.")
        vm.localFolders.forEach { f ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("📁  " + LocalPhotoSource.folderLabel(f), modifier = Modifier.weight(1f), color = Z.Text)
                TextButton(onClick = { vm.removeLocalFolder(f) }) { Text("Entfernen") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { try { picker.launch(null) } catch (e: Exception) { } }) { Text("Ordner hinzufügen") }
            if (vm.localFolders.isNotEmpty()) {
                OutlinedButton(onClick = { vm.scan() }, enabled = !vm.scanning) { Text(if (vm.scanning) "Suche Fotos …" else "Fotos einlesen") }
            }
        }
        vm.photoCount?.let { Text("$it Fotos gesamt", color = Z.TextSoft) }
    }
}

@Composable
private fun SmbSection(vm: SettingsVm) {
    val p = vm.prefs
    val phone = LocalConfiguration.current.screenWidthDp < 600
    Section("Fotoquelle · SMB-Freigabe (NAS)") {
        var host by remember { mutableStateOf(p.smbHost) }
        var share by remember { mutableStateOf(p.smbShare) }
        var path by remember { mutableStateOf(p.smbPath) }
        var user by remember { mutableStateOf(p.smbUser) }
        var pw by remember { mutableStateOf(p.smbPassword) }
        var domain by remember { mutableStateOf(p.smbDomain) }
        var smb1 by remember { mutableStateOf(p.smbAllowSmb1) }
        var guest by remember { mutableStateOf(p.smbGuest) }
        fun save() {
            val changed = host.trim() != p.smbHost || share.trim() != p.smbShare || path.trim() != p.smbPath ||
                user.trim() != p.smbUser || pw != p.smbPassword || domain.trim() != p.smbDomain ||
                smb1 != p.smbAllowSmb1 || guest != p.smbGuest
            p.smbHost = host.trim(); p.smbShare = share.trim(); p.smbPath = path.trim()
            p.smbUser = user.trim(); p.smbPassword = pw; p.smbDomain = domain.trim()
            p.smbAllowSmb1 = smb1; p.smbGuest = guest
            if (changed) vm.onSourcesChanged()
        }
        FlowRow(maxItemsInEachRow = if (phone) 1 else 3, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(host, { host = it }, label = { Text("Server (IP/Name oder \\\\NAS\\Fotos)") }, singleLine = true, modifier = Modifier.weight(1f).keepVisibleWhenFocused())
            OutlinedTextField(share, { share = it }, label = { Text("Freigabe") }, singleLine = true, modifier = Modifier.weight(1f).keepVisibleWhenFocused())
            OutlinedTextField(path, { path = it }, label = { Text("Unterordner (optional)") }, singleLine = true, modifier = Modifier.weight(1f).keepVisibleWhenFocused())
        }
        FlowRow(maxItemsInEachRow = if (phone) 1 else 3, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(user, { user = it }, label = { Text("Benutzer (leer = Gast)") }, singleLine = true, modifier = Modifier.weight(1f).keepVisibleWhenFocused())
            PasswordField(pw, { pw = it }, "Passwort", Modifier.weight(1f))
            OutlinedTextField(domain, { domain = it }, label = { Text("Domäne (optional)") }, singleLine = true, modifier = Modifier.weight(1f).keepVisibleWhenFocused())
        }
        SwitchRow("Als Gast anmelden (wenn kein Benutzer)", guest) { guest = it }
        SwitchRow("Alte NAS erlauben (SMB1)", smb1) { smb1 = it }
        val parsed = SmbSource.parse(host, share, path)
        if (parsed.host.isNotBlank()) Text("Adresse: ${parsed.url}", color = Z.TextSoft, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save(); vm.testSmb() }, enabled = !vm.smbTesting) { Text(if (vm.smbTesting) "Teste …" else "Verbindung testen") }
            OutlinedButton(onClick = { save(); vm.scan() }, enabled = !vm.scanning) { Text(if (vm.scanning) "Suche Fotos …" else "Fotos einlesen") }
            OutlinedButton(onClick = { save(); vm.startNow() }) { Text("Jetzt starten") }
        }
        SmbTestResult(vm.smbSteps)
        DisposableSave { save() }
    }
}

@Composable
private fun SmbTestResult(steps: List<SmbStep>) {
    if (steps.isEmpty()) return
    androidx.compose.foundation.text.selection.SelectionContainer {
        Column(
            Modifier.fillMaxWidth().background(Z.Chip, RoundedCornerShape(12.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            steps.forEach { s ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        when (s.ok) { true -> "✓"; false -> "✗"; null -> "…" },
                        // kein Rot: Fehler in Orange, Erfolg in Blau
                        color = when (s.ok) { true -> Z.Primary; false -> Z.Warn; null -> Z.TextSoft },
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontWeight = FontWeight.Medium, color = Z.Text, fontSize = 15.sp)
                        Text(s.detail, color = if (s.ok == false) Z.Warn else Z.TextSoft, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// ---------------- Overlay ----------------

private val OVERLAY_POSITIONS = listOf(
    "tl" to "Oben links", "tr" to "Oben rechts",
    "bl" to "Unten links", "bc" to "Unten Mitte", "br" to "Unten rechts",
    "split" to "Geteilt: Uhr links, Infos rechts",
)

@Composable
private fun OverlaySection(vm: SettingsVm) {
    val p = vm.prefs
    val st = vm.overlayStyle
    Section("Overlay (Uhr & Infos)") {
        Hint("Position antippen – in der Vorschau oder unten in der Liste.")
        OverlayPreview(st.position, st.size) { vm.updateOverlayStyle(st.copy(position = it)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OVERLAY_POSITIONS.forEach { (k, l) ->
                FilterChip(selected = st.position == k, onClick = { vm.updateOverlayStyle(st.copy(position = k)) }, label = { Text(l) })
            }
        }
        Text("Größe", color = Z.Text, fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Klein", 1 to "Mittel", 2 to "Groß").forEach { (k, l) ->
                FilterChip(selected = st.size == k, onClick = { vm.updateOverlayStyle(st.copy(size = k)) }, label = { Text(l) })
            }
        }
        Text("Inhalt", color = Z.Text, fontWeight = FontWeight.Medium)
        var clock by remember { mutableStateOf(p.ssShowClock) }
        var date by remember { mutableStateOf(p.ssShowDate) }
        SwitchRow("Uhr", clock) { clock = it; p.ssShowClock = it }
        SwitchRow("Datum", date) { date = it; p.ssShowDate = it }
        SwitchRow("Bildunterschrift (Ordner & Aufnahmedatum)", st.showCaption) { vm.updateOverlayStyle(st.copy(showCaption = it)) }
        SwitchRow("Einbrennschutz (Overlay wandert minimal)", st.shift) { vm.updateOverlayStyle(st.copy(shift = it)) }
        Hint("Das Wetter lässt sich unten als Widget dazuschalten.")
    }
}

/** Kleine Bildschirm-Vorschau: Zielpunkte antippen, gewählte Position zeigt eine Mini-Uhr. */
@Composable
private fun OverlayPreview(position: String, size: Int, onPick: (String) -> Unit) {
    val spots = listOf(
        "tl" to Alignment.TopStart, "tr" to Alignment.TopEnd,
        "bl" to Alignment.BottomStart, "bc" to Alignment.BottomCenter, "br" to Alignment.BottomEnd,
    )
    Box(
        Modifier.width(260.dp).height(162.dp)
            .background(Brush.linearGradient(listOf(Color(0xFF2B3F6B), Color(0xFF5E7290))), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        val clockSize = listOf(14, 18, 23)[size.coerceIn(0, 2)].sp
        spots.forEach { (k, a) ->
            val selected = position == k || (position == "split" && k == "bl")
            Box(Modifier.align(a).clickable { onPick(k) }.padding(4.dp)) {
                if (selected) {
                    Column(horizontalAlignment = when (k) { "tr", "br" -> Alignment.End; "bc" -> Alignment.CenterHorizontally; else -> Alignment.Start }) {
                        Text("12:34", color = Color.White, fontSize = clockSize, fontWeight = FontWeight.Light)
                        if (position != "split") Text("☀ 6° · bewölkt", color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp)
                    }
                } else {
                    Box(Modifier.size(18.dp).background(Color.White.copy(alpha = 0.35f), CircleShape))
                }
            }
        }
        if (position == "split") {
            Text("☀ 6° · bewölkt", color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
        }
    }
}

// ---------------- Widgets ----------------

@Composable
private fun WeatherSection(vm: SettingsVm) {
    val p = vm.prefs
    Section("Widget · Wetter") {
        var on by remember { mutableStateOf(p.ssShowWeather) }
        SwitchRow("Im Bildschirmschoner anzeigen", on) { on = it; p.ssShowWeather = it; if (it) vm.refreshWeather() }
        if (on) {
            var style by remember { mutableStateOf(p.ssWeatherStyle) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = style == "full", onClick = { style = "full"; p.ssWeatherStyle = "full" },
                    label = { Text("Ausführlich (Zustand, Min/Max, Regen)") })
                FilterChip(selected = style == "compact", onClick = { style = "compact"; p.ssWeatherStyle = "compact" },
                    label = { Text("Kompakt (Symbol + aktuelle Temperatur)") })
            }
            var city by remember { mutableStateOf(p.weatherCity) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(city, { city = it }, label = { Text("Ort") }, singleLine = true, modifier = Modifier.weight(1f).keepVisibleWhenFocused())
                Spacer(Modifier.width(10.dp))
                Button(onClick = { vm.setWeatherCity(city) }) { Text("Übernehmen") }
            }
            LaunchedEffect(Unit) { if (vm.weather == null) vm.refreshWeather() }
            vm.weather?.let { w ->
                val (icon, line) = weatherLine(w, compact = style == "compact")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = Z.Primary)
                    Spacer(Modifier.width(8.dp))
                    Text("${w.city}: $line", color = Z.Text)
                }
            }
            Hint("Wetterdaten von Open-Meteo (kostenlos, ohne Schlüssel).")
        }
    }
}

// ---------------- Updates & Absturz ----------------

@Composable
private fun UpdateSection(vm: SettingsVm) {
    val p = vm.prefs
    Section("Updates") {
        var auto by remember { mutableStateOf(p.autoUpdate) }
        var token by remember { mutableStateOf(p.githubToken) }
        Hint("Installierte Version: ${BuildConfig.VERSION_NAME} · Quelle: github.com/${BuildConfig.UPDATE_REPO}/releases")
        SwitchRow("Automatisch nach Updates suchen", auto) { auto = it; p.autoUpdate = it }
        PasswordField(token, { token = it; p.githubToken = it.trim() }, "GitHub-Token (nur bei privatem Repo nötig)", Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.checkUpdate() }) { Text("Jetzt prüfen") }
            vm.update?.let { u -> Button(onClick = { vm.installUpdate() }) { Text("Version ${u.version} installieren") } }
        }
        vm.updateProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun CrashSection(vm: SettingsVm, crash: String) {
    Section("Letzter Absturz") {
        Hint("HOLOPIX ist abgestürzt und wurde neu gestartet. Diese Angaben helfen bei der Fehlersuche:")
        val clip = LocalClipboardManager.current
        Text(crash.lines().take(14).joinToString("\n"), fontSize = 11.sp, color = Z.Text, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clip.setText(AnnotatedString(crash)) }) { Text("Kopieren") }
            TextButton(onClick = { vm.clearCrash() }) { Text("Ausblenden") }
        }
    }
}

// ---------------- Display-Zeitplan ----------------

private val WEEKDAYS = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")

@Composable
private fun DisplayScheduleSection(vm: SettingsVm) {
    val ctx = LocalContext.current
    val ds = at.b05.holopix.display.DisplaySchedule
    var pick by remember { mutableStateOf<Triple<Int, Int, Boolean>?>(null) } // (Tag, Zeitraum, true = An-Zeit)

    Section("Display-Zeitplan") {
        SwitchRow("Display automatisch ein- und ausschalten", vm.displayScheduleEnabled) { vm.setDisplaySchedule(it) }
        if (vm.displayScheduleEnabled) {
            Hint("Pro Wochentag einen oder mehrere Zeiträume, in denen das Display an ist (z. B. 06:00–08:30 und 16:00–22:00). " +
                "Zu Beginn eines Zeitraums weckt HOLOPIX das Display und startet den Bildschirmschoner. Außerhalb eines Zeitraums " +
                "startet kein Bildschirmschoner und Android schaltet das Display nach seinem eingestellten Zeitlimit aus " +
                "(Einstellungen → Display → Bildschirm-Zeitlimit). Ohne Zeitraum bleibt das Display an diesem Tag aus, " +
                "Tag nicht angehakt = ganztägig an. Der Besuchermodus hebt den Zeitplan auf.")
            vm.displayPlans.forEachIndexed { i, plan ->
                Column(Modifier.fillMaxWidth().background(Z.Chip, RoundedCornerShape(12.dp)).padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = plan.active, onCheckedChange = { vm.setDayPlan(i, plan.copy(active = it)) })
                        Text(WEEKDAYS[i], color = Z.Text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        if (!plan.active) Text("ganztägig an", color = Z.TextSoft)
                        else {
                            TextButton(onClick = {
                                val last = plan.windows.lastOrNull()
                                val start = last?.off?.plusHours(1) ?: java.time.LocalTime.of(16, 0)
                                vm.setDayPlan(i, plan.copy(windows = plan.windows + at.b05.holopix.display.OnWindow(start, start.plusHours(2))))
                            }) { Text("+ Zeitraum") }
                            if (i == 0) TextButton(onClick = { vm.copyDayPlanToAll(0) }) { Text("Für alle Tage") }
                        }
                    }
                    if (plan.active) {
                        if (plan.windows.isEmpty()) Text("Kein Zeitraum – Display bleibt aus", color = Z.TextSoft, fontSize = 13.sp, modifier = Modifier.padding(start = 48.dp))
                        plan.windows.forEachIndexed { wi, w ->
                            FlowRow(Modifier.padding(start = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { pick = Triple(i, wi, true) }) { Text("An ${w.on}") }
                                Text("–", color = Z.TextSoft, modifier = Modifier.align(Alignment.CenterVertically))
                                OutlinedButton(onClick = { pick = Triple(i, wi, false) }) { Text("Aus ${w.off}") }
                                IconButton(onClick = { vm.setDayPlan(i, plan.copy(windows = plan.windows.filterIndexed { k, _ -> k != wi })) }) {
                                    Icon(Icons.Rounded.Close, "Zeitraum entfernen", tint = Z.TextSoft)
                                }
                            }
                        }
                    }
                }
            }
            vm.nextDisplayChangeText()?.let { Text(it, color = Z.TextSoft, fontSize = 14.sp) }
            WakeChecklist(vm)
        }
    }

    pick?.let { (day, wi, isOn) ->
        val plan = vm.displayPlans[day]
        val w = plan.windows.getOrNull(wi)
        if (w == null) { pick = null } else TimePickDialog(
            title = "${WEEKDAYS[day]}: Display ${if (isOn) "an" else "aus"}",
            initial = if (isOn) w.on else w.off,
            onDismiss = { pick = null },
        ) { t ->
            val nw = if (isOn) w.copy(on = t) else w.copy(off = t)
            vm.setDayPlan(day, plan.copy(windows = plan.windows.mapIndexed { k, x -> if (k == wi) nw else x }.sortedBy { it.on })); pick = null
        }
    }
}

/** Uhrzeit-Auswahl (24 h) als Dialog. */
@Composable
private fun TimePickDialog(title: String, initial: java.time.LocalTime, onDismiss: () -> Unit, onPick: (java.time.LocalTime) -> Unit) {
    val state = androidx.compose.material3.rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    val cfg = LocalConfiguration.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.TimePicker(
                state,
                layoutType = if (cfg.screenWidthDp > cfg.screenHeightDp) androidx.compose.material3.TimePickerLayoutType.Horizontal
                else androidx.compose.material3.TimePickerLayoutType.Vertical,
            )
        },
        confirmButton = { TextButton(onClick = { onPick(java.time.LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Was Android braucht, damit sich das Display zuverlässig selbst einschaltet. */
@Composable
private fun WakeChecklist(vm: SettingsVm) {
    val ctx = LocalContext.current
    val ds = at.b05.holopix.display.DisplaySchedule
    val back = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.permTick++ }
    fun open(action: String) {
        try { back.launch(Intent(action, Uri.parse("package:" + ctx.packageName))) } catch (e: Exception) {
            back.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        }
    }
    val tick = vm.permTick
    val exact = remember(tick) { ds.exactAlarmsAllowed(ctx) }
    val notif = remember(tick) { ds.notificationsAllowed(ctx) }
    val full = remember(tick) { ds.fullScreenAllowed(ctx) }
    Column(
        Modifier.fillMaxWidth().background(Z.Chip, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Damit sich das Display selbst einschaltet", color = Z.Text, fontWeight = FontWeight.Medium)
        PermRow("Exakte Wecker (pünktliches Ein-/Ausschalten)", exact) {
            if (android.os.Build.VERSION.SDK_INT >= 31) open(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        }
        PermRow("Benachrichtigungen (zum Einschalten nötig)", notif) { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }
        PermRow("Vollbild-Benachrichtigungen (Android 14+)", full) {
            if (android.os.Build.VERSION.SDK_INT >= 34) open(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
        }
        OutlinedButton(onClick = {
            ds.scheduleTestWake(ctx)
            android.widget.Toast.makeText(ctx,
                "Jetzt den Ein-/Aus-Knopf drücken – in 15 Sekunden schaltet HOLOPIX das Display wieder ein.", android.widget.Toast.LENGTH_LONG).show()
        }) { Text("Testen: aus und nach 15 s wieder an") }
    }
}
