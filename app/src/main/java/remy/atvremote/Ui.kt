package remy.atvremote

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import kotlin.math.atan2

val Grey = Color(0xFF303030)
val Red = Color(0xFFC62828)
val Green = Color(0xFF2E7D32)
val Pressed = Color(0xFF555555)

// label doubles as the icon's content description
// iconScale evens out glyphs that come out small next to the others
private data class Key(val label: String, val code: Int, val icon: Int? = null, val iconScale: Float = 1f)

// rows of 3, null = empty cell
private val TOP = listOf(
    listOf(Key("POWER", KeyEvent.KEYCODE_POWER, R.drawable.ic_power_settings_new), Key("HOME", KeyEvent.KEYCODE_HOME, R.drawable.ic_home), null),
)
// ring zones clockwise from the top, DPad's angle math relies on this order
private val RING = listOf(
    Key("UP", KeyEvent.KEYCODE_DPAD_UP, R.drawable.ic_arrow_drop_up),
    Key("RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT, R.drawable.ic_arrow_right),
    Key("DOWN", KeyEvent.KEYCODE_DPAD_DOWN, R.drawable.ic_arrow_drop_down),
    Key("LEFT", KeyEvent.KEYCODE_DPAD_LEFT, R.drawable.ic_arrow_left),
)
private val OK = Key("OK", KeyEvent.KEYCODE_DPAD_CENTER)
private val BACK = listOf(listOf(null, Key("BACK", KeyEvent.KEYCODE_BACK, R.drawable.ic_u_turn_left), null))
private const val OK_RATIO = 0.4f  // OK radius / ring radius
private val MEDIA = listOf(
    listOf(Key("VOL-", KeyEvent.KEYCODE_VOLUME_DOWN, R.drawable.ic_volume_down), Key("MUTE", KeyEvent.KEYCODE_VOLUME_MUTE, R.drawable.ic_volume_off), Key("VOL+", KeyEvent.KEYCODE_VOLUME_UP, R.drawable.ic_volume_up)),
    listOf(Key("REW", KeyEvent.KEYCODE_MEDIA_REWIND, R.drawable.ic_fast_rewind), Key("PLAY/PAUSE", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, R.drawable.ic_play_pause, 1.3f), Key("FWD", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, R.drawable.ic_fast_forward)),
    listOf(Key("PREV", KeyEvent.KEYCODE_MEDIA_PREVIOUS, R.drawable.ic_skip_previous), Key("STOP", KeyEvent.KEYCODE_MEDIA_STOP, R.drawable.ic_stop), Key("NEXT", KeyEvent.KEYCODE_MEDIA_NEXT, R.drawable.ic_skip_next)),
)

@Composable
fun Screen(content: @Composable ColumnScope.() -> Unit) = MaterialTheme(darkColorScheme(background = Color.Black)) {
    Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
        // no IME insets: in split screen the other app's keyboard would squish the remote, and the TV text
        // dialog is its own window that handles the keyboard itself
        Column(Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
fun RemoteButton(
    label: String, modifier: Modifier = Modifier, color: Color = Grey, highlighted: Boolean = false, icon: Int? = null, iconScale: Float = 1f,
    onHold: ((down: Boolean) -> Unit)? = null, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val press = remember { MutableInteractionSource() }
    var held by remember { mutableStateOf(false) }
    val hold by rememberUpdatedState(onHold)
    // combinedClickable has no release callback for a long click, the press interaction does
    LaunchedEffect(press) {
        press.interactions.collect {
            if (held && (it is PressInteraction.Release || it is PressInteraction.Cancel)) { held = false; hold?.invoke(false) }
        }
    }
    Box(
        modifier.height(56.dp).clip(shape).background(color)
            .then(if (highlighted) Modifier.border(3.dp, Color.White, shape) else Modifier)
            // never focusable: keys typed in another split-screen app leave touch mode for a moment, and the
            // first focusable button would flash its focus highlight on every keystroke
            .focusProperties { canFocus = false }
            .combinedClickable(press, LocalIndication.current, onLongClick = onHold?.let { { held = true; it(true) } }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(painterResource(icon), label, Modifier.size(32.dp * iconScale), tint = Color.White)
        else Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2)
    }
}

// below this height (split screen, pop-up window) the ring would shrink to nothing next to the media block,
// so navigation and media become two swipeable pages instead
private val PAGED_BELOW = 560.dp

@Composable
fun ColumnScope.RemotePad(onKey: (code: Int, direction: Int) -> Unit) =
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        if (maxHeight >= PAGED_BELOW) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NavKeys(onKey)
            Keys(MEDIA, onKey)
        } else Column {
            val pager = rememberPagerState { 2 }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                if (page == 0) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { NavKeys(onKey) }
                else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                    Keys(MEDIA, onKey)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                repeat(2) { Box(Modifier.size(8.dp).clip(CircleShape).background(if (it == pager.currentPage) Color.White else Grey)) }
            }
        }
    }

@Composable
private fun ColumnScope.NavKeys(onKey: (code: Int, direction: Int) -> Unit) {
    Keys(TOP, onKey)
    // ring + BACK centered together, the ring gives up 64dp of height for BACK's row
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val diameter = minOf(maxWidth * 0.75f, maxHeight - 64.dp).coerceAtLeast(0.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DPad(diameter, onKey)
            Keys(BACK, onKey)
        }
    }
}

// one disc: 4 quarter-ring zones around a round OK, hit-tested by distance and angle from the center
@Composable
private fun DPad(diameter: Dp, onKey: (code: Int, direction: Int) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val key by rememberUpdatedState(onKey)
    var pressed by remember { mutableStateOf<Key?>(null) }
    Box(
        modifier.size(diameter)
            .pointerInput(Unit) {
                val r = size.width / 2f
                fun keyAt(o: Offset): Key? {
                    val d = o - Offset(r, r)
                    val dist = d.getDistance()
                    if (dist > r) return null
                    if (dist < r * OK_RATIO) return OK
                    // atan2 is 0 at 3 o'clock going clockwise (y points down), shift so zone 0 spans -45..45 around the top
                    val deg = Math.toDegrees(atan2(d.y, d.x).toDouble()) + 90 + 45
                    return RING[((deg + 360) % 360 / 90).toInt()]
                }
                var held = false
                detectTapGestures(
                    onPress = { o ->
                        val k = keyAt(o)
                        pressed = k
                        tryAwaitRelease()
                        if (held && k != null) key(k.code, Remote.END_LONG)
                        held = false; pressed = null
                    },
                    onLongPress = { o ->
                        keyAt(o)?.let { held = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress); key(it.code, Remote.START_LONG) }
                    },
                    onTap = { o -> keyAt(o)?.let { key(it.code, Remote.SHORT) } },
                )
            }
            .drawBehind {
                val r = size.minDimension / 2
                drawCircle(Grey)
                pressed?.takeIf { it != OK }?.let { drawArc(Pressed, RING.indexOf(it) * 90f - 135f, 90f, useCenter = true) }
                // gaps between the zones and around OK
                for (a in listOf(45f, 135f, 225f, 315f)) rotate(a) { drawLine(Color.Black, center, center + Offset(r, 0f), 4.dp.toPx()) }
                drawCircle(Color.Black, r * OK_RATIO + 4.dp.toPx())
                drawCircle(if (pressed == OK) Pressed else Grey, r * OK_RATIO)
            },
    ) {
        // centers each triangle on its zone, halfway across the ring (ring width = 0.3 * diameter)
        val inset = Modifier.padding((diameter * 0.15f - 40.dp).coerceAtLeast(0.dp)).size(80.dp)
        listOf(Alignment.TopCenter, Alignment.CenterEnd, Alignment.BottomCenter, Alignment.CenterStart).forEachIndexed { i, at ->
            Icon(painterResource(RING[i].icon!!), RING[i].label, inset.align(at), tint = Color.White)
        }
        Text(OK.label, Modifier.align(Alignment.Center), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Keys(rows: List<List<Key?>>, onKey: (code: Int, direction: Int) -> Unit) = rows.forEach { row ->
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { k ->
            val m = Modifier.weight(1f)
            when {
                k == null -> Spacer(m)
                // POWER is a circle on the left edge rather than a pill filling its third
                k.code == KeyEvent.KEYCODE_POWER -> Box(m, contentAlignment = Alignment.CenterStart) { KeyButton(k, Modifier.size(56.dp), onKey) }
                else -> KeyButton(k, m, onKey)
            }
        }
    }
}

@Composable
private fun KeyButton(k: Key, modifier: Modifier, onKey: (code: Int, direction: Int) -> Unit) = RemoteButton(
    k.label, modifier, color = if (k.code == KeyEvent.KEYCODE_POWER) Red else Grey, icon = k.icon, iconScale = k.iconScale,
    onHold = { down -> onKey(k.code, if (down) Remote.START_LONG else Remote.END_LONG) },
) { onKey(k.code, Remote.SHORT) }
