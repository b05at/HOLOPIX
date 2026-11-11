@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package at.b05.holopix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.b05.holopix.SettingsVm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hält ein Eingabefeld sichtbar, wenn die Tastatur aufgeht: nach dem Fokussieren (und nochmal,
 * sobald die Tastatur ganz offen ist) wird das Feld in den sichtbaren Bereich gescrollt.
 */
fun Modifier.keepVisibleWhenFocused(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    this.bringIntoViewRequester(requester).onFocusEvent { st ->
        if (st.isFocused) scope.launch {
            delay(250); requester.bringIntoView()
            delay(350); requester.bringIntoView()
        }
    }
}

/** Passwortfeld mit Auge zum Anzeigen/Verbergen. */
@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = if (show) KeyboardType.Text else KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Passwort verbergen" else "Passwort anzeigen")
            }
        },
        modifier = modifier.keepVisibleWhenFocused(),
    )
}

/** Bring!: anmelden bzw. verbundenes Konto + Auswahl der Einkaufsliste. */
@Composable
fun BringSetup(vm: SettingsVm) {
    if (!vm.bringConfigured) {
        var email by remember { mutableStateOf(vm.prefs.bringEmail) }
        var pw by remember { mutableStateOf(vm.prefs.bringPassword) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Mit deinem Bring!-Konto anmelden (E-Mail + Passwort).", color = Z.TextSoft)
            OutlinedTextField(
                email, { email = it }, label = { Text("E-Mail") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth().keepVisibleWhenFocused(),
            )
            PasswordField(pw, { pw = it }, "Passwort", Modifier.fillMaxWidth())
            Button(onClick = { vm.bringLogin(email, pw) }, enabled = email.isNotBlank() && pw.isNotBlank()) { Text("Verbinden") }
        }
        return
    }
    LaunchedEffect(Unit) { if (vm.bringLists.isEmpty()) vm.loadBringLists() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Verbunden: ${vm.prefs.bringEmail}", Modifier.weight(1f), color = Z.Text)
            OutlinedButton(onClick = { vm.bringLogout() }) { Text("Trennen") }
        }
        Text("Einkaufsliste", color = Z.Text, fontWeight = FontWeight.Medium)
        if (vm.bringLists.isEmpty()) {
            Text("Listen werden geladen …", color = Z.TextSoft, fontSize = 14.sp)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.bringLists.forEach { l ->
                    FilterChip(selected = l.uuid == vm.bringListUuid, onClick = { vm.selectBringList(l) }, label = { Text(l.name) })
                }
            }
        }
    }
}
