package cz.arnal.bleedge.chat.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import cz.arnal.bleedge.chat.toHex
import cz.arnal.bleedge.chat.data.ChannelKind
import cz.arnal.bleedge.chat.data.Message
import cz.arnal.bleedge.chat.data.MsgStatus
import cz.arnal.bleedge.service.DmDelivery
import cz.arnal.bleedge.service.RSSI_UNKNOWN
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
 * A stable, distinct color for a "virtual" identity — a bridged MeshCore channel author we know
 * only by a declared name (no public key). Keyed on the name so the same author reads the same
 * color, visibly different from our own primary-colored, verified senders.
 */
fun virtualNameColor(name: String): Color =
    avatarColors[(name.lowercase().hashCode().ushr(1)) % avatarColors.size]

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

/** Avatar for the "Note to Self" conversation: a note glyph on a tinted circle (Signal-style). */
@Composable
fun NoteToSelfAvatar(size: Int = 44, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Description,
            contentDescription = "Note to Self",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size((size * 0.55).dp),
        )
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

/**
 * Delivery tick for an outgoing DM that pulses while we're still waiting for the recipient's ACK
 * (i.e. [status] is SENT and a live [delivery] is in flight, not yet acked/failed) — so a single
 * checkmark visibly signals "in progress". Otherwise renders the static [DeliveryTick].
 */
@Composable
fun AnimatedDeliveryTick(status: Int, delivery: DmDelivery?) {
    val awaiting = status == MsgStatus.SENT && delivery != null && !delivery.acked && !delivery.failed
    if (!awaiting) {
        DeliveryTick(status)
        return
    }
    val transition = rememberInfiniteTransition(label = "delivery")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
        label = "tickAlpha",
    )
    Icon(
        Icons.Default.Done,
        contentDescription = "Awaiting acknowledgement",
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        modifier = Modifier.size(15.dp),
    )
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
 * Like [formatRelative] but renders the last hour as a relative age ("now", "Ns ago", "Nm ago"),
 * falling back to the clock (same day) or date. Used for recently discovered contacts on Explore.
 */
fun formatRelativeAge(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return if (diff in 0 until 3_600_000) formatMessageTime(ts) else formatRelative(ts)
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

/** Compact elapsed-duration label, e.g. "9s", "4m", "2h", "3d". Used for peer connection uptime. */
fun shortDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3_600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3_600}h"
        else -> "${s / 86_400}d"
    }
}

/** Compact elapsed delay for an echo: "130ms", "2.3s", or "1m 5s" for longer round-trips. */
fun formatEchoDelay(ms: Long): String {
    val d = ms.coerceAtLeast(0)
    return when {
        d < 1_000 -> "${d}ms"
        d < 60_000 -> "%.1fs".format(d / 1000.0)
        else -> "${d / 60_000}m ${(d % 60_000) / 1000}s"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailsSheet(
    msg: Message,
    vm: ChatViewModel,
    onOpenProfile: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    // Echoes are persisted per-message (survive restart), unlike the in-memory routing logs.
    val echoesMap by vm.echoes.collectAsState()
    val repeatSamples = echoesMap[msg.id].orEmpty()
    val dmDeliveries by vm.dmDeliveries.collectAsState()
    val delivery = dmDeliveries[msg.id]
    val isChannel = isChannelPeer(msg.peerHex)
    val channelEchoed = isChannel && repeatSamples.isNotEmpty()

    // Cross-link to the packet detail dialogs. For an outgoing message we keep its raw datagram on
    // the message itself (persistent); otherwise fall back to the (trimmed) Rx Log. A MeshCore-
    // bridged message also carries the inner MeshCore packet's content id.
    val meshCorePackets by vm.meshCorePackets.collectAsState()
    val rxPackets by vm.rxPackets.collectAsState()
    val peers by vm.connectedPeers.collectAsState()
    val blePacket = remember(msg.packetHex, rxPackets, msg.id) {
        if (msg.packetHex.isNotBlank()) vm.decodePacket(msg.packetHex, timestampMs = msg.timestampMs)
        else rxPackets.firstOrNull { it.id.toHex() == msg.id }
    }
    val meshPacket = remember(meshCorePackets, msg.meshCorePacketId) {
        msg.meshCorePacketId.takeIf { it.isNotBlank() }?.let { id -> meshCorePackets.firstOrNull { it.contentId == id } }
    }
    var showBlePacket by remember { mutableStateOf(false) }
    var showMeshPacket by remember { mutableStateOf(false) }
    // A specific echo's reception packet, opened by tapping that echo row.
    var echoPacket by remember { mutableStateOf<cz.arnal.bleedge.service.RxPacket?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Message details", style = MaterialTheme.typography.titleMedium)
            // A bridged MeshCore channel author is unverifiable — we only know its declared name.
            // Show the bridge node (real, tappable) and the declared sender (linked only if its
            // name matches a saved contact) separately, so they're not conflated.
            if (isChannel && msg.viaMeshCore) {
                if (msg.bridgeHex.isNotBlank()) {
                    SenderRow("Sender (bridge)", msg.bridgeHex, vm, onOpenProfile)
                }
                val declaredHex = msg.senderName.takeIf { it.isNotBlank() }?.let { vm.nodeHexForName(it) }
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (declaredHex != null && onOpenProfile != null) Modifier.clickable { onOpenProfile(declaredHex) } else Modifier),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Declared sender", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        msg.senderName.ifBlank { "—" },
                        fontWeight = FontWeight.Medium,
                        color = if (declaredHex != null) MaterialTheme.colorScheme.primary
                        else virtualNameColor(msg.senderName),
                    )
                }
                Text(
                    if (declaredHex != null) "Name matches a saved contact (still unverified — no public key)."
                    else "Unverified — bridged names carry no public key, so this can't be linked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (isChannel && msg.senderHex.isNotBlank()) {
                // Native channel message — the originating node is a real, verified identity.
                SenderRow("Sender", msg.senderHex, vm, onOpenProfile)
            }
            DetailRow("Direction", if (msg.incoming) "Incoming" else "Outgoing")
            if (msg.viaMeshCore) {
                DetailRow("Origin", "MeshCore (bridged from LoRa)")
                if (msg.meshCoreType.isNotBlank()) DetailRow("MeshCore type", msg.meshCoreType)
                if (msg.meshCoreRoute.isNotBlank()) {
                    val hops = if (msg.meshCoreHops > 0) " · ${msg.meshCoreHops} hop${if (msg.meshCoreHops == 1) "" else "s"}" else ""
                    DetailRow("MeshCore route", msg.meshCoreRoute + hops)
                }
                if (msg.meshCorePacketId.isNotBlank()) {
                    // Small inline "details" affordance opening the MeshCore packet's Rx Log dialog.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("MeshCore packet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(msg.meshCorePacketId, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
                            if (meshPacket != null) {
                                Surface(
                                    color = Color(0xFF00838F).copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.clickable { showMeshPacket = true },
                                ) {
                                    Text(
                                        "details",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF00838F),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Outbound bridging: a gateway relayed this channel message onto MeshCore (ACK_BRIDGED).
            if (msg.bridgedToMeshCore) {
                DetailRow(
                    "Bridged to MeshCore",
                    if (msg.bridgedByHex.isNotBlank()) "via ${vm.nameForHex(msg.bridgedByHex).ifBlank { msg.bridgedByHex.take(12) }}" else "yes",
                )
            }
            DetailRow("Time", "${dayFmt.format(Date(msg.timestampMs))} ${formatClock(msg.timestampMs)}")
            if (!msg.incoming) {
                DetailRow("Status", when {
                    // Channels are broadcast and never ACKed — hearing our own message echoed back
                    // is the only confirmation it propagated, so treat an echo as "delivered".
                    isChannel -> if (channelEchoed)
                        "Delivered (heard echoed ${repeatSamples.size}×)"
                    else "Sent to mesh (no echo yet)"
                    msg.status == MsgStatus.SENDING -> "Sending…"
                    msg.status == MsgStatus.SENT -> buildString {
                        append(if (delivery != null && !delivery.acked && !delivery.failed)
                            "Sent — try ${delivery.attemptsSent} of ${delivery.maxTries}, awaiting ACK"
                        else "Sent to mesh")
                        if (repeatSamples.isNotEmpty())
                            append(" · ${repeatSamples.size} repeat${if (repeatSamples.size == 1) "" else "s"} heard")
                    }
                    msg.status == MsgStatus.DELIVERED ->
                        if (delivery != null && delivery.attemptsSent > 1)
                            "Delivered (ACK after ${delivery.attemptsSent} tries)"
                        else "Delivered (ACK received)"
                    else ->
                        if (delivery != null) "Failed — no ACK after ${delivery.attemptsSent} tries"
                        else "Failed to send"
                })
                // Retry detail for a DM that needed (or is making) more than one attempt.
                if (!isChannel && delivery != null && delivery.maxTries > 1) {
                    DetailRow("Delivery attempts", "${delivery.attemptsSent} of ${delivery.maxTries}")
                }
            }
            val relays = relayCount(msg.routeHex)
            // Real intermediate relays only — drop the final hop (always this node); the
            // sender/recipient endpoints aren't shown.
            val relayHops = msg.routeHex.split(",").filter { it.isNotBlank() }.dropLast(1)
            DetailRow("Delivery", when {
                msg.routeHex.isBlank() -> when {
                    msg.incoming -> "—"
                    isChannel -> if (channelEchoed)
                        "Echoed by ${repeatSamples.size} node${if (repeatSamples.size == 1) "" else "s"}"
                    else "Broadcast — no echo yet"
                    else -> "Awaiting confirmation"
                }
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
            // Repeats of this flooded message we heard echoed back, with the RSSI of each reception.
            if (repeatSamples.isNotEmpty()) {
                DetailRow("Repeats heard", "${repeatSamples.size}")
                Text(
                    "Echoes",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                repeatSamples.forEachIndexed { i, s ->
                    val via = if (s.viaMeshCore) "MeshCore"
                    else s.forwarderHex.takeIf { it.isNotBlank() }?.let { vm.nameForHex(it) }
                    // Round-trip delay since we sent the message — more useful than a wall-clock time.
                    val delay = formatEchoDelay(s.timestampMs - msg.timestampMs)
                    val clickable = s.packetHex.isNotBlank()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (clickable) Modifier.clickable { echoPacket = vm.decodePacket(s.packetHex, s.rssi, s.timestampMs) } else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${i + 1}. +$delay${if (via != null) "  · via $via" else ""}",
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!s.viaMeshCore) SignalLabel(s.rssi, "rssi")
                        if (clickable) Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = "packet details",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Jump to the raw BLEEdge packet in the Rx Log (when it's still buffered).
            if (blePacket != null) {
                OutlinedButton(onClick = { showBlePacket = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Packet details")
                }
            }
        }
    }

    if (showBlePacket && blePacket != null) {
        PacketDetailDialog(
            p = blePacket,
            vm = vm,
            peers = peers,
            onOpenProfile = { hex -> showBlePacket = false; onOpenProfile?.invoke(hex) },
            onDismiss = { showBlePacket = false },
        )
    }
    if (showMeshPacket && meshPacket != null) {
        MeshCoreDetailDialog(meshPacket, vm, onDismiss = { showMeshPacket = false })
    }
    echoPacket?.let { ep ->
        PacketDetailDialog(
            p = ep,
            vm = vm,
            peers = peers,
            onOpenProfile = { hex -> echoPacket = null; onOpenProfile?.invoke(hex) },
            onDismiss = { echoPacket = null },
        )
    }
}

/** A sender/bridge row in message details: avatar + name, tappable to open the node's profile. */
@Composable
private fun SenderRow(label: String, nodeHex: String, vm: ChatViewModel, onOpenProfile: ((String) -> Unit)?) {
    val profile by remember(nodeHex) { vm.profileFor(nodeHex) }.collectAsState()
    val name = profile.name.ifBlank { vm.nameForHex(nodeHex) }
    Row(
        Modifier.fillMaxWidth()
            .then(if (onOpenProfile != null) Modifier.clickable { onOpenProfile(nodeHex) } else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(seed = nodeHex, label = name, size = 28, identiconKey = profile.pubKeyHex.ifBlank { null })
            Spacer(Modifier.size(8.dp))
            Text(
                name,
                fontWeight = FontWeight.Medium,
                color = if (onOpenProfile != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
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

// ---- Signal quality indicator -----------------------------------------------

/**
 * Maps a signal reading to a traffic-light colour. [metric] is "snr" (dB, higher is better) or
 * anything else = RSSI (dBm, less-negative is better). A null / unknown value is grey.
 */
fun signalColor(value: Int?, metric: String): Color {
    if (value == null || value == RSSI_UNKNOWN) return Color(0xFF9E9E9E) // grey — unknown
    val q = if (metric == "snr") {
        when { value >= 10 -> 4; value >= 5 -> 3; value >= 0 -> 2; value >= -5 -> 1; else -> 0 }
    } else {
        when { value >= -60 -> 4; value >= -70 -> 3; value >= -80 -> 2; value >= -90 -> 1; else -> 0 }
    }
    return when (q) {
        4 -> Color(0xFF2E7D32) // strong — green
        3 -> Color(0xFF7CB342) // good — light green
        2 -> Color(0xFFF9A825) // fair — amber
        1 -> Color(0xFFEF6C00) // weak — orange
        else -> Color(0xFFC62828) // poor — red
    }
}

/** A small colour-coded dot indicating signal quality, for use next to an RSSI/SNR number. */
@Composable
fun SignalDot(value: Int?, metric: String = "rssi", modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(signalColor(value, metric)))
}

/** A signal dot followed by its value, e.g. "● -63 dBm". [value] = RSSI_UNKNOWN renders "n/a". */
@Composable
fun SignalLabel(
    value: Int?,
    metric: String = "rssi",
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelMedium,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SignalDot(value, metric)
        val unit = if (metric == "snr") "dB" else "dBm"
        Text(
            if (value == null || value == RSSI_UNKNOWN) "n/a" else "$value $unit",
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
