package remy.atvremote

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Grey = Color(0xFF303030)
val Red = Color(0xFFC62828)
val Green = Color(0xFF2E7D32)

// label doubles as the icon's content description
private data class Key(val label: String, val code: Int, val icon: Int? = null)

// rows of 3, null = empty cell, empty row = gap
private val LAYOUT = listOf(
    listOf(Key("POWER", KeyEvent.KEYCODE_POWER, R.drawable.ic_power_settings_new), null, Key("HOME", KeyEvent.KEYCODE_HOME, R.drawable.ic_home)),
    listOf(),
    listOf(null, Key("UP", KeyEvent.KEYCODE_DPAD_UP, R.drawable.ic_keyboard_arrow_up), null),
    listOf(Key("LEFT", KeyEvent.KEYCODE_DPAD_LEFT, R.drawable.ic_keyboard_arrow_left), Key("OK", KeyEvent.KEYCODE_DPAD_CENTER), Key("RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT, R.drawable.ic_keyboard_arrow_right)),
    listOf(Key("BACK", KeyEvent.KEYCODE_BACK, R.drawable.ic_arrow_back), Key("DOWN", KeyEvent.KEYCODE_DPAD_DOWN, R.drawable.ic_keyboard_arrow_down), null),
    listOf(),
    listOf(Key("VOL+", KeyEvent.KEYCODE_VOLUME_UP, R.drawable.ic_volume_up), Key("MUTE", KeyEvent.KEYCODE_VOLUME_MUTE, R.drawable.ic_volume_off), Key("NEXT", KeyEvent.KEYCODE_MEDIA_NEXT, R.drawable.ic_skip_next)),
    listOf(Key("VOL-", KeyEvent.KEYCODE_VOLUME_DOWN, R.drawable.ic_volume_down), null, Key("PREV", KeyEvent.KEYCODE_MEDIA_PREVIOUS, R.drawable.ic_skip_previous)),
    listOf(Key("REW", KeyEvent.KEYCODE_MEDIA_REWIND, R.drawable.ic_fast_rewind), Key("PLAY/PAUSE", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, R.drawable.ic_play_pause), Key("FWD", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, R.drawable.ic_fast_forward)),
    listOf(null, Key("STOP", KeyEvent.KEYCODE_MEDIA_STOP, R.drawable.ic_stop), null),
)

@Composable
fun Screen(content: @Composable ColumnScope.() -> Unit) = MaterialTheme(darkColorScheme(background = Color.Black)) {
    Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
        Column(Modifier.safeDrawingPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
fun RemoteButton(label: String, modifier: Modifier = Modifier, color: Color = Grey, highlighted: Boolean = false, icon: Int? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(56.dp).clip(shape).background(color)
            .then(if (highlighted) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(painterResource(icon), label, Modifier.size(28.dp), tint = Color.White)
        else Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
fun RemotePad(hidePower: Boolean, onKey: (Int) -> Unit) = LAYOUT.forEach { row ->
    if (row.isEmpty()) Spacer(Modifier.height(16.dp))
    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { k ->
            if (k == null || (hidePower && k.code == KeyEvent.KEYCODE_POWER)) Spacer(Modifier.weight(1f))
            else RemoteButton(k.label, Modifier.weight(1f), color = if (k.code == KeyEvent.KEYCODE_POWER) Red else Grey, icon = k.icon) { onKey(k.code) }
        }
    }
}
