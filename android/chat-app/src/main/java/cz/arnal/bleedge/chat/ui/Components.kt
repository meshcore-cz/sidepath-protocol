package cz.arnal.bleedge.chat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.arnal.bleedge.chat.AvatarStyle
import cz.arnal.bleedge.chat.ChatViewModel
import cz.arnal.bleedge.chat.ConnState
import cz.arnal.bleedge.chat.data.ChannelKind
import cz.arnal.bleedge.chat.data.Message
import cz.arnal.bleedge.chat.data.MsgStatus
import cz.arnal.bleedge.chat.data.isChannelPeer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A borderless search field for a TopAppBar title. Requests focus and pops the keyboard as soon
 * as it appears so the user can start typing immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(placeholder) },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

/** Centered placeholder shown while a search is active but the query is still empty. */
@Composable
fun SearchHint(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val avatarColors = listOf(
    Color(0xFF6750A4), Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFB71C1C),
    Color(0xFFEF6C00), Color(0xFF00838F), Color(0xFFAD1457), Color(0xFF4527A0),
)

/** How avatars are drawn app-wide; provided at the root from the user's setting. */
val LocalAvatarStyle = staticCompositionLocalOf { AvatarStyle.IDENTICON }

/**
 * Avatar for a contact/channel. When the identicon style is on AND an [identiconKey] is given
 * (a contact's public key), draws a deterministic identicon from that key; otherwise a colored
 * initials circle. Channels (no public key) always fall back to initials.
 */
@Composable
fun Avatar(seed: String, label: String, size: Int = 44, identiconKey: String? = null, onClick: (() -> Unit)? = null) {
    val base = Modifier.size(size.dp).clip(CircleShape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    if (LocalAvatarStyle.current == AvatarStyle.IDENTICON && !identiconKey.isNullOrBlank()) {
        Identicon(identiconKey, base)
    } else {
        val color = avatarColors[(seed.hashCode().ushr(1)) % avatarColors.size]
        val initials = label.trim().take(2).uppercase().ifBlank { "?" }
        Box(base.background(color), contentAlignment = Alignment.Center) {
            Text(initials, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size / 2.6).sp)
        }
    }
}

// A spread of distinct, vivid identicon colours across the whole hue wheel — picking from these
// gives far more visible variety than a low-saturation HSV sweep (which read as muted blues).
private val identiconColors = listOf(
    Color(0xFFE53935), Color(0xFFD81B60), Color(0xFF8E24AA), Color(0xFF5E35B1),
    Color(0xFF3949AB), Color(0xFF1E88E5), Color(0xFF00ACC1), Color(0xFF00897B),
    Color(0xFF43A047), Color(0xFF7CB342), Color(0xFFC0CA33), Color(0xFFFDD835),
    Color(0xFFFFB300), Color(0xFFFB8C00), Color(0xFFF4511E), Color(0xFF6D4C41),
)

/** A deterministic GitHub-style identicon (5×5 mirrored grid hashed from [key]). */
@Composable
private fun Identicon(key: String, modifier: Modifier) {
    // Same vivid foreground in both themes; only the backdrop swaps to stay legible in dark mode.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val digest = remember(key) { java.security.MessageDigest.getInstance("MD5").digest(key.toByteArray()) }
    // Colour from one hash byte, grid pattern from the others, so similar keys still diverge.
    val fg = remember(key) {
        val idx = ((digest[5].toInt() and 0xFF) xor (digest[11].toInt() and 0xFF)) % identiconColors.size
        identiconColors[idx]
    }
    val bg = if (dark) Color(0xFF26262A) else Color(0xFFEDEDED)
    Canvas(modifier.background(bg)) {
        val cell = size.minDimension / 5f
        for (row in 0 until 5) {
            for (col in 0 until 3) {
                val on = (digest[row * 3 + col].toInt() and 1) == 0
                if (!on) continue
                for (c in intArrayOf(col, 4 - col)) {
                    drawRect(color = fg, topLeft = Offset(c * cell, row * cell), size = Size(cell, cell))
                }
            }
        }
    }
}

/** Renders a public key (hex) compactly as `<e9534284...49e01345>`; blank in → blank out. */
fun formatPubKey(hex: String): String = when {
    hex.isBlank() -> ""
    hex.length >= 16 -> "<${hex.take(8)}...${hex.takeLast(8)}>"
    else -> "<$hex>"
}

/**
 * Channel display label: only name-derived ("public hash") channels get the `#` prefix
 * (no space, e.g. `#test`); Public and Secret channels show their name as-is.
 */
fun channelLabel(name: String, kind: String): String =
    if (kind == ChannelKind.NAMED) "#$name" else name

/** Channel mentions are stored/transmitted as `@[Name]`; this matches one. */
val mentionRegex = Regex("""@\[([^\]]+)\]""")

/**
 * Number of intermediate relays a packet passed through. The stored route is the packet
 * trace, whose last hop is always this node, so relays = hops − 1 (0 = direct).
 */
fun relayCount(routeHex: String): Int =
    (routeHex.split(",").count { it.isNotBlank() } - 1).coerceAtLeast(0)

/** Compact "direct / N relays" chip shown on a message bubble. */
@Composable
fun RouteIndicator(routeHex: String) {
    if (routeHex.isBlank()) return
    val relays = relayCount(routeHex)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(
            if (relays == 0) Icons.Default.Bolt else Icons.Default.Route,
            contentDescription = if (relays == 0) "Direct" else "$relays relays",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            if (relays == 0) "direct" else "$relays",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Colored connection-health dot shown next to the app name. */
@Composable
fun ConnectionDot(state: ConnState, size: Int = 10) {
    val color = when (state) {
        ConnState.CONNECTED -> Color(0xFF2E7D32) // green
        ConnState.NO_PEERS -> Color(0xFFEF6C00)  // orange
        ConnState.OFFLINE -> Color(0xFF9E9E9E)   // grey
        ConnState.ERROR -> Color(0xFFC62828)     // red
    }
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun DeliveryTick(status: Int) {
    val (icon, tint) = when (status) {
        MsgStatus.SENDING -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
        MsgStatus.SENT -> Icons.Default.Done to MaterialTheme.colorScheme.onSurfaceVariant
        MsgStatus.DELIVERED -> Icons.Default.DoneAll to MaterialTheme.colorScheme.primary
        else -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFmt = SimpleDateFormat("MMM d", Locale.getDefault())
private val fullDayFmt = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

fun formatClock(ts: Long): String = timeFmt.format(Date(ts))

fun formatRelative(ts: Long): String {
    val now = System.currentTimeMillis()
    val sameDay = dayFmt.format(Date(ts)) == dayFmt.format(Date(now))
    return if (sameDay) timeFmt.format(Date(ts)) else dayFmt.format(Date(ts))
}

/**
 * Per-message timestamp: "now" (<5s), "Ns ago" (<1m), "Nm ago" (<1h), else the clock time.
 * Recomputed on recomposition (not a live ticker).
 */
fun formatMessageTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 0 -> timeFmt.format(Date(ts))
        diff < 5_000 -> "now"
        diff < 60_000 -> "${diff / 1000}s ago"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        else -> timeFmt.format(Date(ts))
    }
}

private fun isSameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

/** A day separator label: "Today", "Yesterday", or e.g. "Monday, Jun 9". */
fun dateLabel(ts: Long): String {
    val now = System.currentTimeMillis()
    return when {
        isSameDay(ts, now) -> "Today"
        isSameDay(ts, now - 86_400_000L) -> "Yesterday"
        else -> fullDayFmt.format(Date(ts))
    }
}

/** True when [a] and [b] fall on different calendar days (used to insert date separators). */
fun differentDay(a: Long, b: Long): Boolean = !isSameDay(a, b)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailsSheet(
    msg: Message,
    vm: ChatViewModel,
    onOpenProfile: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Message details", style = MaterialTheme.typography.titleMedium)
            // For a channel message, surface the sender and let it open their profile.
            if (isChannelPeer(msg.peerHex) && msg.senderHex.isNotBlank()) {
                val senderLabel = msg.senderName.ifBlank { vm.nameForHex(msg.senderHex) }
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (onOpenProfile != null) Modifier.clickable { onOpenProfile(msg.senderHex) } else Modifier),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Sender", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(seed = msg.senderHex, label = senderLabel, size = 28)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            senderLabel,
                            fontWeight = FontWeight.Medium,
                            color = if (onOpenProfile != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            DetailRow("Direction", if (msg.incoming) "Incoming" else "Outgoing")
            DetailRow("Time", "${dayFmt.format(Date(msg.timestampMs))} ${formatClock(msg.timestampMs)}")
            if (!msg.incoming) {
                DetailRow("Status", when (msg.status) {
                    MsgStatus.SENDING -> "Sending…"
                    MsgStatus.SENT -> "Sent to mesh"
                    MsgStatus.DELIVERED -> "Delivered (ACK received)"
                    else -> "Failed to send"
                })
            }
            val relays = relayCount(msg.routeHex)
            // Real intermediate relays only — drop the final hop (always this node); the
            // sender/recipient endpoints aren't shown.
            val relayHops = msg.routeHex.split(",").filter { it.isNotBlank() }.dropLast(1)
            DetailRow("Delivery", when {
                msg.routeHex.isBlank() -> if (msg.incoming) "—" else "Awaiting confirmation"
                relays == 0 -> "Direct (no relays)"
                else -> "$relays relay${if (relays == 1) "" else "s"}"
            })
            if (relayHops.isNotEmpty()) {
                Text(
                    "Relays",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                relayHops.forEachIndexed { i, hop ->
                    Text(
                        "${i + 1}. ${vm.nameForHex(hop)}  ·  ${hop.take(8)}",
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
