package cz.arnal.bleedge.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.arnal.bleedge.chat.ChatViewModel
import cz.arnal.bleedge.core.isBroadcast
import cz.arnal.bleedge.service.RxPacket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RxLogScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val packets by vm.rxPackets.collectAsState()
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
                    PacketRow(p, vm) { detail = p }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    detail?.let { PacketDetailDialog(it, vm) { detail = null } }
}

private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
private fun PacketRow(p: RxPacket, vm: ChatViewModel, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TypePill(p)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${vm.nameForHex(p.source.toHexString())} → ${destLabel(p, vm)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(2.dp))
            Text(
                "${timeFmt.format(Date(p.timestampMs))} · ${p.mode} · ttl ${p.ttl} · ${p.payloadSize}B" +
                    if (p.trace.isNotEmpty()) " · ${p.trace.size} hop${if (p.trace.size == 1) "" else "s"}" else "",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Short colored label naming the packet's nature (payload type for DATA, else the packet type). */
@Composable
private fun TypePill(p: RxPacket) {
    val label = if (p.type.name == "DATA") p.payloadType.name else p.type.name
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun destLabel(p: RxPacket, vm: ChatViewModel): String =
    if (p.destination.isBroadcast()) "broadcast" else vm.nameForHex(p.destination.toHexString())

@Composable
private fun PacketDetailDialog(p: RxPacket, vm: ChatViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Packet detail") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
