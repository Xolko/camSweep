package com.example.ptzsweep

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(store: SettingsStore, onSaved: () -> Unit) {
    val initial = remember { store.load() }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var user by remember { mutableStateOf(initial.user) }
    var pass by remember { mutableStateOf(initial.pass) }
    var speed by remember { mutableFloatStateOf(initial.speed) }
    var leg by remember { mutableStateOf(initial.legSeconds.toString()) }
    var pause by remember { mutableStateOf(initial.pauseSeconds.toString()) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun current() = CamSettings(
        host = host.trim(),
        port = port.toIntOrNull() ?: 80,
        user = user,
        pass = pass,
        speed = speed,
        legSeconds = leg.toFloatOrNull()?.takeIf { it > 0 } ?: 10f,
        pauseSeconds = pause.toFloatOrNull()?.takeIf { it >= 0 } ?: 0.3f,
    )

    val full = Modifier.fillMaxWidth()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Panel("Camera") {
            OutlinedTextField(host, { host = it }, label = { Text("Camera IP") },
                singleLine = true, modifier = full)
            OutlinedTextField(port, { port = it }, label = { Text("ONVIF port (e.g. 8000)") },
                singleLine = true, modifier = full,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(user, { user = it }, label = { Text("Username") },
                singleLine = true, modifier = full)
            OutlinedTextField(pass, { pass = it }, label = { Text("Password") },
                singleLine = true, modifier = full,
                visualTransformation = PasswordVisualTransformation())
            OutlinedButton(
                onClick = {
                    message = "Testing…"
                    val c = current()
                    scope.launch(Dispatchers.IO) {
                        message = try {
                            OnvifClient(c.host, c.port, c.user, c.pass).connect()
                            "Connected ✓ PTZ is available"
                        } catch (e: Exception) { "Failed: ${e.message}" }
                    }
                },
                modifier = full
            ) { Text("Test connection") }
        }

        Panel("Sweep") {
            Text("Pan speed  ${"%.2f".format(speed)}", style = MaterialTheme.typography.labelLarge)
            Slider(speed, { speed = it }, valueRange = 0.1f..1f)
            OutlinedTextField(leg, { leg = it },
                label = { Text("Seconds per sweep (set to time for 180°)") },
                singleLine = true, modifier = full,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(pause, { pause = it },
                label = { Text("Pause at each end (seconds)") },
                singleLine = true, modifier = full,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }

        Button(
            onClick = { store.save(current()); onSaved(); message = "Saved ✓" },
            modifier = full.height(52.dp)
        ) { Text("Save") }
        if (message.isNotEmpty()) Text(message)
    }
}
