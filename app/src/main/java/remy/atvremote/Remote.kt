package remy.atvremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// same RemoteMessage protocol over TLS (6466) or bluetooth RFCOMM. RFCOMM still answers while the box
// sleeps with its network down, TLS doesn't
class Remote(
    private val ctx: Context,
    private val onStatus: (String) -> Unit,
    private val onKeyboard: (String, String) -> Unit,
    private val onPowered: (Boolean) -> Unit,
) {
    companion object {
        // "Android TV Remote" SDP record, found in an HCI capture of the google TV app
        val RFCOMM_UUID: UUID = UUID.fromString("cbbfe0e2-f7f3-4206-84e0-84cbb3d09dfc")
        private const val FEATURES = 622  // same feature bitmask as the google TV app
        // RemoteKeyInject direction: a hold is START_LONG then END_LONG on release, the box times it itself
        const val START_LONG = 1
        const val END_LONG = 2
        const val SHORT = 3
    }

    val prefs = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var conn: Closeable? = null
    @Volatile private var out: OutputStream? = null

    // the box's own msg 21 is just an ack, the counters to echo come from msg 20 (editor 1.1, field 2.1) and 22 (2.1).
    // wrong counters and the box silently drops every edit
    @Volatile private var imeCounter = 0L
    @Volatile private var fieldCounter = 0L
    @Volatile private var imeAction = 0L

    fun key(code: Int, direction: Int = SHORT) = send(Pb().msg(10, Pb().int(1, code).int(2, direction)))

    // one replace-range edit {start, end in old, inserted text}, like the google TV app's RemoteTextEdit
    fun text(old: String, new: String) {
        var p = 0
        while (p < old.length && p < new.length && old[p] == new[p]) p++
        var s = 0
        while (s < old.length - p && s < new.length - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        edit(Pb().msg(2, Pb().int(1, p).int(2, old.length - s).str(3, new.substring(p, new.length - s))))
    }

    // the field's editor action (done/search/go), not KEYCODE_ENTER
    fun submit() = edit(Pb().msg(5, Pb().int(1, imeAction)))

    private fun edit(info: Pb) = send(Pb().msg(21, Pb().int(1, imeCounter).int(2, fieldCounter).msg(3, info)))

    private fun textField(f: Map<Int, Any>?) = (f?.get(2) as? ByteArray)?.decodeToString().orEmpty()

    fun open() = worker.execute {
        if (out != null) return@execute
        try { connect() } catch (e: Exception) { drop(); onStatus("Disconnected") }
    }

    private fun send(msg: Pb) = worker.execute {
        try {
            Pb.write(out ?: connect(), msg)
        } catch (e: Exception) {
            drop(); onStatus("Send failed: ${e.message}")
        }
    }

    fun close() = worker.execute { drop(); onStatus("Disconnected") }

    private fun drop() {
        runCatching { conn?.close() }
        conn = null; out = null
        onPowered(false)
    }

    // bluetooth last, it's the only way to wake the box once its network is down
    private fun connect(): OutputStream {
        prefs.getString("host", null)?.takeIf { prefs.getBoolean("paired", false) }?.let { host ->
            onStatus("Connecting over network...")
            runCatching { return open(Tls.connect(host, Tls.REMOTE_PORT), "Network") }
        }
        prefs.getString("bt", null)?.let { addr ->
            onStatus("Connecting over Bluetooth...")
            runCatching { return open(bluetooth(addr), "Bluetooth") }
        }
        error("box unreachable")
    }

    @SuppressLint("MissingPermission")  // BLUETOOTH_CONNECT is requested by MainActivity
    private fun bluetooth(addr: String): Closeable {
        val adapter = ctx.getSystemService(BluetoothManager::class.java).adapter
        return adapter.getRemoteDevice(addr).createRfcommSocketToServiceRecord(RFCOMM_UUID).apply { connect() }
    }

    private fun open(c: Closeable, name: String): OutputStream {
        val (i, o) = when (c) {
            is android.bluetooth.BluetoothSocket -> c.inputStream to c.outputStream
            is java.net.Socket -> c.inputStream to c.outputStream
            else -> error("unknown transport")
        }
        conn = c; out = o
        val ready = CountDownLatch(1)
        Thread { readLoop(c, i, o, ready, name) }.start()
        if (!ready.await(5, TimeUnit.SECONDS)) { drop(); error("no handshake") }
        return o
    }

    private fun readLoop(c: Closeable, i: InputStream, o: OutputStream, ready: CountDownLatch, name: String) {
        try {
            while (true) {
                val m = Pb.read(i)
                when {
                    m.containsKey(1) -> Pb.write(o, Pb().msg(1, Pb().int(1, FEATURES).msg(2, Pb()
                        .str(1, Build.MODEL).str(2, Build.MANUFACTURER).int(3, 1).str(4, "1")
                        .str(5, "remy.atvremote").str(6, "1.0.0"))))
                    m.containsKey(2) -> { Pb.write(o, Pb().msg(2, Pb().int(1, FEATURES))); ready.countDown(); onStatus("$name connected") }
                    m.containsKey(40) -> onPowered(m.sub(40)?.long(1) == 1L)
                    m.containsKey(8) -> Pb.write(o, Pb().msg(9, Pb().int(1, m.sub(8)?.long(1) ?: 0)))
                    // input started on the TV: 1 = EditorInfo (1 counter, 2 inputType, 3 imeOptions, 10 hint), 2 = text status
                    m.containsKey(20) -> m.sub(20)?.let { s ->
                        val editor = s.sub(1) ?: return@let
                        imeCounter = editor.long(1) ?: 0
                        imeAction = (editor.long(3) ?: 0) and 0xFF  // EditorInfo.IME_MASK_ACTION
                        fieldCounter = s.sub(2)?.long(1) ?: 0
                        if ((editor.long(2) ?: 0) != 0L) onKeyboard((editor[10] as? ByteArray)?.decodeToString().orEmpty(), textField(s.sub(2)))
                    }
                    m.containsKey(22) -> m.sub(22)?.sub(2)?.let { f -> fieldCounter = f.long(1) ?: fieldCounter }
                }
            }
        } catch (_: Exception) {
            if (conn === c) { drop(); onStatus("Disconnected") }
        }
    }
}
