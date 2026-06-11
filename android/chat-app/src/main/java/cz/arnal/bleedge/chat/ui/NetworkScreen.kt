package cz.arnal.bleedge.chat.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.arnal.bleedge.chat.ChatViewModel
import cz.arnal.bleedge.service.PeerInfo
import cz.arnal.bleedge.service.RSSI_UNKNOWN
import cz.arnal.bleedge.service.TopologyEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(vm: ChatViewModel) {
    val peers by vm.connectedPeers.collectAsState()
    val topology by vm.topology.collectAsState()
    val myNode by vm.nodeId.collectAsState()
    val myHex = myNode.toHexString()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Network") }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader("Connected peers (${peers.size})") }
            if (peers.isEmpty()) {
                item { EmptyLine("No peers connected.") }
            } else {
                // Keys must be unique across the whole list; a peer is usually also in the
                // topology below, so namespace the keys to avoid a collision crash.
                items(peers, key = { "peer:${it.nodeId.toHexString()}" }) { PeerRow(it) }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            val others = topology.filter { it.nodeId.toHexString() != myHex }
            item { SectionHeader("Known topology (${others.size})") }
            if (others.isEmpty()) {
                item { EmptyLine("No nodes learned yet.") }
            } else {
                items(others, key = { "topo:${it.nodeId.toHexString()}" }) { TopologyRow(it) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun PeerRow(peer: PeerInfo) {
    val label = peer.description.ifBlank { peer.nodeId.toHexString() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(seed = peer.nodeId.toHexString(), label = label)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                peer.nodeId.toHexString().take(16),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val rssi = if (peer.rssi == RSSI_UNKNOWN) "n/a" else "${peer.rssi} dBm"
            Text(
                "${if (peer.incoming) "inbound" else "outbound"} · $rssi · ${peer.txPhy} · ${peer.caps}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TopologyRow(node: TopologyEntry) {
    val label = node.description.ifBlank { node.nodeId.toHexString() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(seed = node.nodeId.toHexString(), label = label, size = 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium, maxLines = 1)
            val nb = node.neighborIds.size
            Text(
                "${node.nodeId.toHexString().take(16)} · $nb neighbor${if (nb == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
