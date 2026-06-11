package cz.arnal.bleedge.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.arnal.bleedge.chat.ChatViewModel
import cz.arnal.bleedge.chat.ConnState
import cz.arnal.bleedge.service.RSSI_UNKNOWN

/**
 * Universal connection-status chip: a colored health dot + the connected-peer count. Tapping
 * it opens a sheet with health, peers and topology stats. Reusable in any TopAppBar.
 */
@Composable
fun ConnectionStatusButton(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val status by vm.connectionStatus.collectAsState()
    val peers by vm.connectedPeers.collectAsState()
    var open by remember { mutableStateOf(false) }

    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .clickable { open = true }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectionDot(status)
        Spacer(Modifier.width(4.dp))
        Text(
            "${peers.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (open) ConnectionStatusSheet(vm) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionStatusSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val status by vm.connectionStatus.collectAsState()
    val peers by vm.connectedPeers.collectAsState()
    val topology by vm.topology.collectAsState()
    val myHex = vm.nodeId.collectAsState().value.toHexString()

    val others = remember(topology, myHex) { topology.filter { it.nodeId.toHexString() != myHex } }
    val links = remember(others) { others.sumOf { it.neighborIds.size } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionDot(status, size = 14)
                Spacer(Modifier.width(10.dp))
                Text(healthLabel(status), style = MaterialTheme.typography.titleMedium)
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatRow("Connected peers", "${peers.size}")
                StatRow("Known nodes", "${others.size}")
                StatRow("Topology links", "$links")
            }

            HorizontalDivider()

            Text("Connected peers", style = MaterialTheme.typography.labelLarge)
            if (peers.isEmpty()) {
                Text("None right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                peers.forEach { p ->
                    val name = p.description.ifBlank { vm.nameForHex(p.nodeId.toHexString()) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(seed = p.nodeId.toHexString(), label = name, size = 32)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.Medium, maxLines = 1)
                            val rssi = if (p.rssi == RSSI_UNKNOWN) "n/a" else "${p.rssi} dBm"
                            Text(
                                "${if (p.incoming) "inbound" else "outbound"} · $rssi · ${p.txPhy}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun healthLabel(state: ConnState): String = when (state) {
    ConnState.CONNECTED -> "Connected"
    ConnState.NO_PEERS -> "No peers in range"
    ConnState.OFFLINE -> "Offline"
    ConnState.ERROR -> "Permissions needed"
}
