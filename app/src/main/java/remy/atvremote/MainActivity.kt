package remy.atvremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val status = mutableStateOf("Disconnected")
    private val keyboard = mutableStateOf<Pair<String, String>?>(null)  // label, initial text of the focused TV field
    private val remote by lazy {
        Remote(this, { status.value = it }, { label, text -> keyboard.value = label to text })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 0)
        setContent {
            var setup by remember { mutableStateOf(remote.prefs.getString("bt", null) == null && remote.prefs.getString("host", null) == null) }
            Screen {
                if (setup) {
                    Text(status.value)
                    Setup { setup = false }
                } else {
                    val haptic = LocalHapticFeedback.current
                    keyboard.value?.let { (label, initial) -> TvKeyboard(label, initial) }
                    // combinedClickable already buzzes when a hold starts
                    RemotePad { code, direction ->
                        if (direction == Remote.SHORT) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        remote.key(code, direction)
                    }
                    val s = status.value
                    val connected = s.endsWith(" connected")
                    RemoteButton(
                        if (connected) "CONNECTED" else if (s == "Disconnected") "SETUP" else s.uppercase(),
                        Modifier.fillMaxWidth(), color = if (connected) Green else Grey,
                    ) { setup = true }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        remote.open()
    }

    override fun onStop() {
        super.onStop()
        remote.close()
    }

    @SuppressLint("MissingPermission")
    private fun bondedTvs() = runCatching {
        val all = getSystemService(BluetoothManager::class.java).adapter.bondedDevices.toList()
        all.filter { d -> d.uuids?.contains(ParcelUuid(Remote.RFCOMM_UUID)) == true }.ifEmpty { all }.map { (it.name ?: it.address) to it.address }
    }.getOrDefault(emptyList())

    @Composable
    private fun TvKeyboard(label: String, initial: String) {
        var text by remember(label, initial) { mutableStateOf(initial) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(label, initial) { focus.requestFocus() }
        Dialog({ keyboard.value = null }) {
            Surface(shape = RoundedCornerShape(24.dp), color = Color.Black, contentColor = Color.White) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        text, { remote.text(text, it); text = it },
                        Modifier.fillMaxWidth().focusRequester(focus),
                        label = { Text(label.ifEmpty { "TV text field" }) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { remote.submit(); keyboard.value = null }),
                    )
                    RemoteButton("CLOSE", Modifier.fillMaxWidth()) { keyboard.value = null }
                }
            }
        }
    }

    @Composable
    private fun Setup(onDone: () -> Unit) {
        val prefs = remote.prefs
        var bt by remember { mutableStateOf(prefs.getString("bt", null)) }
        var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
        var code by remember { mutableStateOf("") }
        var pairing by remember { mutableStateOf<SSLSocket?>(null) }

        Text("Bluetooth (paired devices)")
        bondedTvs().forEach { (name, addr) ->
            RemoteButton(name, Modifier.fillMaxWidth(), highlighted = addr == bt) { bt = addr }
        }
        Text("Network fallback")
        OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Box IP") }, singleLine = true)
        if (pairing == null) {
            RemoteButton(if (prefs.getBoolean("paired", false)) "PAIR AGAIN" else "PAIR OVER NETWORK", Modifier.fillMaxWidth()) {
                status.value = "Pairing..."
                thread {
                    runCatching { Tls.startPairing(host) }
                        .onSuccess { pairing = it; status.value = "Enter the code shown on the TV" }
                        .onFailure { status.value = "Pairing failed: ${it.message}" }
                }
            }
        } else {
            OutlinedTextField(code, { code = it.trim().uppercase() }, Modifier.fillMaxWidth(), label = { Text("Code on TV") }, singleLine = true)
            RemoteButton("CONFIRM CODE", Modifier.fillMaxWidth()) {
                val s = pairing!!
                pairing = null
                thread {
                    runCatching { Tls.finishPairing(s, code) }
                        .onSuccess { prefs.edit().putBoolean("paired", true).apply(); status.value = "Paired" }
                        .onFailure { status.value = "Pairing failed: ${it.message}" }
                }
            }
        }
        RemoteButton("DONE", Modifier.fillMaxWidth()) {
            prefs.edit().putString("bt", bt).putString("host", host.ifEmpty { null }).apply()
            remote.close()
            remote.open()
            onDone()
        }
    }
}
