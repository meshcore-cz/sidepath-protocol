package cz.arnal.bleedge.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.arnal.bleedge.chat.AdvertisedNode
import cz.arnal.bleedge.chat.ChatViewModel
import cz.arnal.bleedge.chat.data.DiscoveredContact
import cz.arnal.bleedge.chat.data.DiscoverySource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    vm: ChatViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val discovered by vm.discoveredContacts.collectAsState()
    var detail by remember { mutableStateOf<DiscoveredContact?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Explore") },
                actions = {
                    ConnectionStatusButton(vm)
                    OverflowMenu(
                        onOpenSettings = onOpenSettings,
                        onOpenAbout = onOpenAbout,
                        extraItems = { dismiss ->
                            DropdownMenuItem(
                                text = { Text("Clear discovered contacts") },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                                onClick = {
                                    dismiss()
                                    confirmClear = true
                                },
                            )
                        },
                    )
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (discovered.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.TravelExplore, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
                Text("No discovered contacts", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Nodes you hear advertising — over BLEEdge or bridged from MeshCore — appear here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Text(
                        "Discovered contacts",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                items(discovered, key = { it.pubKeyHex }) { d ->
                    DiscoveredRow(d) { detail = d }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    detail?.let { d ->
        DiscoveredDetailDialog(
            d = d,
            onStartChat = {
                vm.startChat(AdvertisedNode(d.nodeHex, "", d.pubKeyHex))
                detail = null
                onOpenConversation(d.nodeHex)
            },
            onAddContact = {
                vm.startChat(AdvertisedNode(d.nodeHex, "", d.pubKeyHex))
                detail = null
            },
            onDismiss = { detail = null },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear discovered contacts?") },
            text = { Text("This removes all discovered contacts from Explore. Saved contacts and chat history are kept.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.clearDiscoveredContacts()
                        confirmClear = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DiscoveredRow(d: DiscoveredContact, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(seed = d.nodeHex, label = d.name, identiconKey = d.pubKeyHex.ifBlank { null })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(d.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                SourceBadge(d.source)
            }
            val sub = buildString {
                append(d.nodeHex.take(16))
                if (d.source == DiscoverySource.MESHCORE) append(" · ${nodeTypeLabel(d.nodeType)}")
            }
            Text(
                sub,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatRelative(d.lastAdvertisedMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceBadge(source: String) {
    val (label, color) = when (source) {
        DiscoverySource.MESHCORE -> "MESHCORE" to Color(0xFF00838F)
        else -> "BLEEDGE" to Color(0xFF546E7A)
    }
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(6.dp)) {
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
private fun DiscoveredDetailDialog(
    d: DiscoveredContact,
    onStartChat: () -> Unit,
    onAddContact: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isBleedge = d.source == DiscoverySource.BLEEDGE
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (isBleedge) TextButton(onClick = onStartChat) { Text("Start chat") }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (isBleedge) TextButton(onClick = onAddContact) { Text("Add contact") }
        },
        title = { Text(d.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ExploreField("Source", if (isBleedge) "BLEEdge node" else "MeshCore node (bridged)")
                if (d.source == DiscoverySource.MESHCORE) {
                    ExploreField("Node type", nodeTypeLabel(d.nodeType))
                    ExploreField("Signature", if (d.sigVerified) "verified" else "unverified")
                }
                ExploreField("Node id", d.nodeHex)
                if (d.pubKeyHex.isNotBlank()) ExploreField("Public key", d.pubKeyHex)
                if (d.hasGps) ExploreField("Location", "%.6f, %.6f".format(d.lat, d.lon))
                ExploreField("Last advertised", "${formatRelative(d.lastAdvertisedMs)} (${formatClock(d.lastAdvertisedMs)})")
                if (d.source == DiscoverySource.MESHCORE && d.nodeAdvertisedMs > 0) {
                    ExploreField("Node advert time", "${formatRelative(d.nodeAdvertisedMs)} (${formatClock(d.nodeAdvertisedMs)})")
                }
                if (d.firstSeenMs > 0) ExploreField("First seen", formatRelative(d.firstSeenMs))
                if (!isBleedge) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "MeshCore nodes aren't directly reachable over BLEEdge yet, so they're display-only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun ExploreField(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private fun nodeTypeLabel(t: Int): String = when (t) {
    1 -> "chat"
    2 -> "repeater"
    3 -> "room"
    4 -> "sensor"
    else -> "unknown"
}
