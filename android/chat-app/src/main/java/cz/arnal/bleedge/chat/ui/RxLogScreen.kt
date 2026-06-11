package cz.arnal.bleedge.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.arnal.bleedge.chat.ChatViewModel
import cz.arnal.bleedge.chat.ProfileInfo
import cz.arnal.bleedge.core.isBroadcast
import cz.arnal.bleedge.service.PeerInfo
import cz.arnal.bleedge.service.RxPacket
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RxLogScreen(vm: ChatViewModel, onBack: () -> Unit, onOpenProfile: (String) -> Unit = {}) {
    val packets by vm.rxPackets.collectAsState()
    val myNode by vm.nodeId.collectAsState()
    val peers by vm.connectedPeers.collectAsState()
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(350)
        }
    }
    var detail by remember { mutableStateOf<RxPacket?>(null) }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Column {
                        Text("Rx Log")
                        Text(
                            "${packets.size} packet${if (packets.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (packets.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("No BLEEdge packets received yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(packets) { p ->
                    PacketRow(
                        p = p,
                        vm = vm,
                        peers = peers,
                        myNodeHex = myNode.toHexString(),
                        nowMs = nowMs,
                    ) { detail = p }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    detail?.let {
        PacketDetailDialog(
            p = it,
            vm = vm,
            peers = peers,
            onOpenProfile = { peerHex ->
                detail = null
                onOpenProfile(peerHex)
            },
            onDismiss = { detail = null },
        )
    }
}

private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
private fun PacketRow(
    p: RxPacket,
    vm: ChatViewModel,
    peers: List<PeerInfo>,
    myNodeHex: String,
    nowMs: Long,
    onClick: () -> Unit,
) {
    val ageMs = (nowMs - p.timestampMs).coerceAtLeast(0)
    val freshAlpha = ((7_000L - ageMs).coerceIn(0L, 7_000L).toFloat() / 7_000f) * 0.13f
    val typeColor = packetColor(p)
    val isMine = p.source.toHexString() == myNodeHex
    val direct = directPeerFor(p, peers)
    val bg = typeColor.copy(alpha = freshAlpha)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TypePill(p)
                if (isMine) MineChip()
                Text(timeFmt.format(Date(p.timestampMs)), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Text(
                    "${vm.nameForHex(p.source.toHexString())} → ${destLabel(p, vm)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                SignalLabel(direct?.rssi, "rssi", style = MaterialTheme.typography.labelSmall)
                Text(packetMeta(p), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypePill(p)
                    if (isMine) MineChip()
                    Text(
                        "${vm.nameForHex(p.source.toHexString())} → ${destLabel(p, vm)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${timeFmt.format(Date(p.timestampMs))} · ${packetMeta(p)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    SignalLabel(direct?.rssi, "rssi", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Short colored label naming the packet's nature (payload type for DATA, else the packet type). */
@Composable
private fun TypePill(p: RxPacket) {
    val label = if (p.type.name == "DATA") p.payloadType.name else p.type.name
    val color = packetColor(p)
    Surface(
        color = color.copy(alpha = 0.18f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MineChip() {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.CallMade,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "mine",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun packetColor(p: RxPacket): Color =
    when {
        p.type.name == "ACK" -> Color(0xFF2E7D32)
        p.type.name == "ANNOUNCE" -> Color(0xFF546E7A)
        p.payloadType.name.contains("TRACE") -> Color(0xFF6A1B9A)
        p.payloadType.name == "CHANNEL" -> Color(0xFF00838F)
        p.payloadType.name.contains("CHAT") -> MaterialTheme.colorScheme.primary
        p.payloadType.name == "TYPING" -> Color(0xFFEF6C00)
        else -> MaterialTheme.colorScheme.secondary
    }

private fun packetMeta(p: RxPacket): String =
    "${p.mode} · ttl ${p.ttl} · ${p.payloadSize}B" +
        if (p.trace.isNotEmpty()) " · ${p.trace.size} hop${if (p.trace.size == 1) "" else "s"}" else ""

private fun directPeerFor(p: RxPacket, peers: List<PeerInfo>): PeerInfo? {
    val directHex = (p.trace.lastOrNull() ?: p.source).toHexString()
    return peers.firstOrNull { it.nodeId.toHexString() == directHex }
}

private fun destLabel(p: RxPacket, vm: ChatViewModel): String =
    if (p.destination.isBroadcast()) "broadcast" else vm.nameForHex(p.destination.toHexString())

@Composable
private fun PacketDetailDialog(
    p: RxPacket,
    vm: ChatViewModel,
    peers: List<PeerInfo>,
    onOpenProfile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sourceHex = p.source.toHexString()
    val profile by remember(sourceHex) { vm.profileFor(sourceHex) }.collectAsState()
    val peer = peers.firstOrNull { it.nodeId.toHexString() == sourceHex }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Packet detail") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (shouldShowProfile(profile, peer)) {
                    PeerProfileBox(profile, peer) { onOpenProfile(sourceHex) }
                    Spacer(Modifier.width(4.dp))
                }
                Field("Time", timeFmt.format(Date(p.timestampMs)))
                Field("Type", p.type.name)
                Field("Payload", "${p.payloadType.name} (${p.payloadSize} bytes)")
                Field("Packet id", p.id.toHexLower())
                Field("Source", "${vm.nameForHex(p.source.toHexString())}\n${p.source.toHexString()}")
                Field("Destination", if (p.destination.isBroadcast()) "broadcast" else
                    "${vm.nameForHex(p.destination.toHexString())}\n${p.destination.toHexString()}")
                Field("Routing", "${p.mode} · ttl ${p.ttl} · ${if (p.forUs) "for us" else "overheard"}")
                if (p.route.isNotEmpty()) Field("Route", p.route.joinToString(" → ") { it.toHexString().take(16) })
                if (p.trace.isNotEmpty()) Field("Trace", p.trace.joinToString(" → ") { it.toHexString().take(16) })

                Spacer(Modifier.width(4.dp))
                Text("Raw packet (${p.raw.size} bytes)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                Text(
                    p.raw.toHexDump(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                )
            }
        },
    )
}

private fun shouldShowProfile(profile: ProfileInfo, peer: PeerInfo?): Boolean =
    peer != null || profile.pubKeyHex.isNotBlank() || profile.description.isNotBlank() ||
        profile.platform.isNotBlank() || profile.online

@Composable
private fun PeerProfileBox(profile: ProfileInfo, peer: PeerInfo?, onClick: () -> Unit) {
    val displayName = profile.name.ifBlank { profile.peerHex.take(16).ifBlank { "Unknown peer" } }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(
                seed = profile.peerHex,
                label = displayName,
                size = 40,
                identiconKey = profile.pubKeyHex.ifBlank { null },
            )
            Column(Modifier.weight(1f)) {
                Text(displayName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    profile.nodeHex.ifBlank { profile.peerHex }.take(16),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val platform = profile.platform.ifBlank { peer?.platform.orEmpty() }
                if (platform.isNotBlank() || peer != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SignalDot(peer?.rssi, "rssi")
                        Text(
                            listOfNotNull(
                                platform.takeIf { it.isNotBlank() },
                                peer?.let { if (it.incoming) "inbound" else "outbound" },
                                peer?.txPhy?.toString(),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(90.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private fun ByteArray.toHexLower(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/** Classic offset / hex / ascii dump, 16 bytes per line. */
private fun ByteArray.toHexDump(): String {
    if (isEmpty()) return "(empty)"
    val sb = StringBuilder()
    var i = 0
    while (i < size) {
        sb.append("%04x  ".format(i))
        val end = minOf(i + 16, size)
        for (j in i until end) sb.append("%02x ".format(this[j].toInt() and 0xFF))
        repeat(16 - (end - i)) { sb.append("   ") }
        sb.append(" ")
        for (j in i until end) {
            val c = this[j].toInt() and 0xFF
            sb.append(if (c in 0x20..0x7e) c.toChar() else '.')
        }
        sb.append('\n')
        i += 16
    }
    return sb.toString().trimEnd()
}
