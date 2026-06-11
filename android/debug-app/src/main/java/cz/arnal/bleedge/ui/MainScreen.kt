package cz.arnal.bleedge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.arnal.bleedge.core.Capability
import cz.arnal.bleedge.core.Capabilities
import cz.arnal.bleedge.core.PHY
import cz.arnal.bleedge.core.PHYMode
import cz.arnal.bleedge.service.LogEntry
import cz.arnal.bleedge.service.LogTag
import cz.arnal.bleedge.service.NeighborEntry
import cz.arnal.bleedge.service.PeerInfo
import cz.arnal.bleedge.service.RSSI_UNKNOWN
import cz.arnal.bleedge.service.ReceivedMessage
import cz.arnal.bleedge.service.TopologyEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val activity = LocalContext.current as? MainActivity

    val nodeId by viewModel.nodeId.collectAsState()
    val bleMacAddress by viewModel.bleMacAddress.collectAsState()
    val phyMode by viewModel.phyMode.collectAsState()
    val codedPhySupported by viewModel.codedPhySupported.collectAsState()
    val phyFallback by viewModel.phyFallback.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val advertisingActive by viewModel.advertisingActive.collectAsState()
    val scanningActive by viewModel.scanningActive.collectAsState()
    val connectedPeers by viewModel.connectedPeers.collectAsState()
    val receivedMessages by viewModel.receivedMessages.collectAsState()
    val routingLog by viewModel.routingLog.collectAsState()
    val neighborTable by viewModel.neighborTable.collectAsState()
    val knownTopology by viewModel.knownTopology.collectAsState()
    val allowlist by viewModel.allowlist.collectAsState()
    val allowlistInput by viewModel.allowlistInput.collectAsState()

    var destination by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }
    var ttlText by remember { mutableStateOf("4") }
    var selectedTab by remember { mutableIntStateOf(0) }

    val tabs = listOf("Overview", "Log", "Messages", "Neighbors", "Topology", "Allowlist")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BLEEdge") },
                actions = {
                    IconButton(onClick = { viewModel.clearData() }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Clear data",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { viewModel.toggleRunning(activity) }) {
                        Icon(
                            imageVector = if (isRunning) Icons.Filled.Close else Icons.Filled.PlayArrow,
                            contentDescription = if (isRunning) "Stop BLE" else "Start BLE",
                            tint = if (isRunning) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {

            // Tab row
            ScrollableTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }

            when (selectedTab) {
                0 -> OverviewTab(
                    nodeId = nodeId.toHexString(),
                    bleMacAddress = bleMacAddress,
                    phyMode = phyMode,
                    onPhyModeChange = viewModel::setPhyMode,
                    phyFallback = phyFallback,
                    codedPhySupported = codedPhySupported,
                    advertisingActive = advertisingActive,
                    scanningActive = scanningActive,
                    connectedPeers = connectedPeers,
                    destination = destination,
                    onDestinationChange = { destination = it },
                    message = messageText,
                    onMessageChange = { messageText = it },
                    ttl = ttlText,
                    onTtlChange = { ttlText = it },
                    onSend = {
                        viewModel.sendMessage(
                            destination,
                            messageText,
                            ttlText.toIntOrNull() ?: 4
                        )
                        messageText = ""
                    },
                )
                1 -> LogTab(log = routingLog)
                2 -> MessagesTab(messages = receivedMessages)
                3 -> NeighborTab(neighbors = neighborTable)
                4 -> TopologyTab(topology = knownTopology)
                5 -> AllowlistTab(
                    allowlist = allowlist,
                    input = allowlistInput,
                    onInputChange = viewModel::onAllowlistInputChange,
                    onAdd = { viewModel.addAllowlistEntry(allowlistInput) },
                    onRemove = viewModel::removeAllowlistEntry,
                )
            }
        }
    }
}

@Composable
private fun OverviewTab(
    nodeId: String,
    bleMacAddress: String,
    phyMode: PHYMode,
    onPhyModeChange: (PHYMode) -> Unit,
    phyFallback: Boolean,
    codedPhySupported: Boolean,
    advertisingActive: Boolean,
    scanningActive: Boolean,
    connectedPeers: List<PeerInfo>,
    destination: String,
    onDestinationChange: (String) -> Unit,
    message: String,
    onMessageChange: (String) -> Unit,
    ttl: String,
    onTtlChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // PHY fallback warning banner
        if (phyFallback) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFF3CD),
                    ),
                ) {
                    Text(
                        text = "LE Coded PHY not supported — using 1M PHY. " +
                            "Coded PHY (Long Range) requires Bluetooth 5.0 (e.g. Pixel 6+).",
                        modifier = Modifier.padding(12.dp),
                        color = Color(0xFF856404),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            SectionHeader("Node Identity")
            InfoRow("Node ID", nodeId)
            if (bleMacAddress.isNotEmpty()) InfoRow("BLE MAC", bleMacAddress)
            InfoRow("Coded PHY support", if (codedPhySupported) "yes" else "no")
            InfoRow("Advertising", if (advertisingActive) "active" else "inactive")
            InfoRow("Scanning", if (scanningActive) "active" else "inactive")
            Spacer(Modifier.height(8.dp))

            Text(
                "PHY Mode" + if (phyFallback) " (fallback active)" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PHYMode.entries.forEach { mode ->
                    FilterChip(
                        selected = phyMode == mode,
                        onClick = { if (phyMode != mode) onPhyModeChange(mode) },
                        label = { Text(mode.value) },
                    )
                }
            }
            Text(
                "1m is the default. coded-only/coded-preferred enable Long Range but only " +
                    "work on devices that can also scan Coded PHY (many can't, despite reporting support).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
        }

        item {
            SectionHeader("Capabilities")
            // Android always has these capabilities
            val caps = Capabilities(
                cz.arnal.bleedge.core.Capability.SENDER or
                    cz.arnal.bleedge.core.Capability.RECEIVER or
                    cz.arnal.bleedge.core.Capability.RELAY or
                    cz.arnal.bleedge.core.Capability.CODED_PHY
            )
            CapabilityRow("Sender", caps.isSender())
            CapabilityRow("Receiver", caps.isReceiver())
            CapabilityRow("Relay", caps.isRelay())
            CapabilityRow("Gateway", caps.isGateway())
            CapabilityRow("Coded PHY", caps.hasCodedPhy())
            Spacer(Modifier.height(8.dp))
        }

        item {
            SectionHeader("Connected Peers (${connectedPeers.size})")
        }
        items(connectedPeers) { peer ->
            PeerCard(peer)
        }
        if (connectedPeers.isEmpty()) {
            item { Text("No peers connected", color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(4.dp)) }
        }

        item {
            Spacer(Modifier.height(12.dp))
            SectionHeader("Send Packet")
            OutlinedTextField(
                value = destination,
                onValueChange = onDestinationChange,
                label = { Text("Destination NodeID (empty = broadcast)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = message,
                onValueChange = onMessageChange,
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = ttl,
                    onValueChange = onTtlChange,
                    label = { Text("TTL") },
                    modifier = Modifier.width(80.dp),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSend, enabled = message.isNotBlank()) {
                    Text("Send Packet")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PeerCard(peer: PeerInfo) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (peer.phyInvalid) Color(0xFFFFF3CD) else MaterialTheme.colorScheme.surface
        ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(peer.nodeId.toHexString(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (peer.incoming) {
                        Text("incoming", color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp)
                    }
                    if (peer.phyInvalid) {
                        Text("Not Long Range", color = Color(0xFF856404), fontSize = 11.sp)
                    }
                }
            }
            InfoRow("Description", peer.description.ifBlank { "—" })
            InfoRow("RSSI", if (peer.rssi == RSSI_UNKNOWN) "n/a (inbound)" else "${peer.rssi} dBm")
            InfoRow("TX PHY", if (peer.txPhy == PHY.UNKNOWN) "—" else peer.txPhy.toString())
            InfoRow("RX PHY", if (peer.rxPhy == PHY.UNKNOWN) "—" else peer.rxPhy.toString())
            InfoRow("Capabilities", peer.caps.toString().ifBlank { "—" })
        }
    }
}

private val tagColors = mapOf(
    LogTag.SYS    to Color(0xFF9E9E9E),
    LogTag.SCAN   to Color(0xFF1976D2),
    LogTag.PEER   to Color(0xFF388E3C),
    LogTag.SERVER to Color(0xFF00838F),
    LogTag.GATT   to Color(0xFF6A1B9A),
    LogTag.PHY    to Color(0xFFE65100),
    LogTag.ROUTER to Color(0xFF795548),
    LogTag.MSG    to Color(0xFFC62828),
)

@Composable
private fun LogTab(log: List<LogEntry>) {
    var activeTags by remember { mutableStateOf(enumValues<LogTag>().toList().toSet()) }
    val filtered = remember(log, activeTags) { log.filter { it.tag in activeTags } }
    val state = rememberLazyListState()
    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) state.animateScrollToItem(filtered.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Filter chips
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(enumValues<LogTag>().toList()) { tag ->
                val selected = tag in activeTags
                val color = tagColors[tag] ?: MaterialTheme.colorScheme.primary
                FilterChip(
                    selected = selected,
                    onClick = {
                        activeTags = if (selected && activeTags.size > 1)
                            activeTags - tag else activeTags + tag
                    },
                    label = { Text(tag.name, fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = color.copy(alpha = 0.18f),
                        selectedLabelColor = color,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        selectedBorderColor = color,
                        borderColor = color.copy(alpha = 0.4f),
                    ),
                )
            }
        }
        Divider()

        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            items(filtered) { entry ->
                val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestampMs))
                val color = tagColors[entry.tag] ?: MaterialTheme.colorScheme.onSurface
                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    Text(
                        text = time,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(84.dp),
                    )
                    Text(
                        text = "[${entry.tag.name.padEnd(6)}] ",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = color,
                        modifier = Modifier.width(72.dp),
                    )
                    Text(
                        text = entry.message,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessagesTab(messages: List<ReceivedMessage>) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        items(messages.reversed()) { msg ->
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(msg.timestampMs))
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    if (msg.isAck) {
                        Text("ACK from ${msg.fromNodeId.toHexString()}  $time",
                            color = Color(0xFF388E3C), fontWeight = FontWeight.Medium)
                    } else {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("From: ${msg.fromNodeId.toHexString()}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            Text(time, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(String(msg.payload, Charsets.UTF_8), modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NeighborTab(neighbors: List<NeighborEntry>) {
    if (neighbors.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No neighbors", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        items(neighbors) { n ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(n.nodeId.toHexString(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    InfoRow("Description", n.description.ifBlank { "—" })
                    InfoRow("RSSI", "${n.rssi} dBm")
                    InfoRow("TX PHY", n.txPhy.toString())
                    InfoRow("RX PHY", n.rxPhy.toString())
                    InfoRow("Capabilities", n.caps.toString())
                }
            }
        }
    }
}

/** Formats a wall-clock timestamp (ms) as a short "Ns ago" string. */
private fun relativeTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return "never"
    val deltaSec = ((System.currentTimeMillis() - timestampMs) / 1000).coerceAtLeast(0)
    return when {
        deltaSec < 1    -> "just now"
        deltaSec < 60   -> "${deltaSec}s ago"
        deltaSec < 3600 -> "${deltaSec / 60}m ${deltaSec % 60}s ago"
        else            -> "${deltaSec / 3600}h ago"
    }
}

@Composable
private fun TopologyTab(topology: List<TopologyEntry>) {
    if (topology.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No topology data", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        items(topology) { node ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(node.nodeId.toHexString(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    InfoRow("Description", node.description.ifBlank { "—" })
                    InfoRow("Capabilities", node.caps.toString())
                    InfoRow("Last announce", relativeTime(node.lastAnnounceMs))
                    InfoRow("Neighbors (${node.neighborIds.size})",
                        node.neighborIds.joinToString { it.toHexString().take(8) + "…" })
                }
            }
        }
    }
}

@Composable
private fun AllowlistTab(
    allowlist: Set<String>,
    input: String,
    onInputChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        SectionHeader("Allowlist (${allowlist.size} entries)")
        Text("Empty = allow all BLEEdge peers", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text("Node ID hex (16 chars)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onAdd, enabled = input.length == 16) { Text("Add") }
        }

        Spacer(Modifier.height(8.dp))
        Divider()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(allowlist.toList()) { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(entry, fontFamily = FontFamily.Monospace)
                    TextButton(onClick = { onRemove(entry) }) { Text("Remove") }
                }
                Divider()
            }
        }
    }
}

// ---- helpers ----------------------------------------------------------------

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text("$label: ", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.width(140.dp))
        Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun CapabilityRow(label: String, active: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text("  $label: ", fontSize = 13.sp, modifier = Modifier.width(140.dp))
        Text(
            if (active) "yes" else "no",
            fontSize = 13.sp,
            color = if (active) Color(0xFF388E3C) else Color(0xFFB71C1C),
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun cz.arnal.bleedge.core.NodeID.toHexString(): String =
    bytes.joinToString("") { "%02x".format(it) }
