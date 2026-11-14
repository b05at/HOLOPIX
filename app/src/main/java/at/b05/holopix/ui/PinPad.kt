package at.b05.holopix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Zahlenfeld für die Kiosk-PIN – eigene Tasten statt Bildschirmtastatur (funktioniert auch im Overlay).
 * onSubmit gibt true zurück, wenn die PIN stimmt; sonst wird „Falsche PIN“ gezeigt.
 * Ohne Eingabe schließt es sich nach 30 s von selbst.
 */
@Composable
fun PinPad(title: String, onSubmit: (String) -> Boolean, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var lastInput by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastInput) { delay(30_000); onCancel() }
    fun press(k: String) {
        lastInput = System.currentTimeMillis()
        error = false
        when (k) {
            "⌫" -> pin = pin.dropLast(1)
            "OK" -> if (pin.isNotEmpty()) { if (!onSubmit(pin)) { error = true; pin = "" } }
            else -> if (pin.length < 8) pin += k
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.background(Color(0xFF1E1F20), RoundedCornerShape(24.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(18.dp)) {
                repeat(maxOf(pin.length, 4)) { i ->
                    Box(Modifier.size(14.dp).then(
                        if (i < pin.length) Modifier.background(Color.White, CircleShape)
                        else Modifier.border(2.dp, Color(0xFF8E9399), CircleShape)
                    ))
                }
            }
            Spacer(Modifier.height(8.dp))
            // kein Rot (Rot-Grün-Schwäche): Hinweis in Orange
            Text(if (error) "Falsche PIN" else " ", color = Color(0xFFFFB95C), fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 7.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.size(68.dp).background(if (k == "OK") Color(0xFFA8C7FA) else Color(0xFF2D2F31), CircleShape)
                                .clickable { press(k) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(k, color = if (k == "OK") Color(0xFF062E6F) else Color.White, fontSize = if (k.length > 1) 18.sp else 26.sp,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Abbrechen", color = Color(0xFFD0D4D9), fontSize = 16.sp,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onCancel() }.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}
