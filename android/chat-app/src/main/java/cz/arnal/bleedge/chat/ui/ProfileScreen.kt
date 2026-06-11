package cz.arnal.bleedge.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.arnal.bleedge.chat.ChatViewModel
import cz.arnal.bleedge.chat.MeshCoreUri
import cz.arnal.bleedge.chat.ProfileInfo
import cz.arnal.bleedge.chat.data.ChannelKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    vm: ChatViewModel,
    peerHex: String,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onTrace: (String) -> Unit,
) {
    val profile by remember(peerHex) { vm.profileFor(peerHex) }.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }

    // A MeshCore share URI other apps can scan to add this contact / join this channel.
    val shareUri = when {
        profile.isChannel && profile.pskHex.isNotBlank() -> MeshCoreUri.channel(profile.name, profile.pskHex)
        !profile.isChannel && profile.pubKeyHex.isNotBlank() -> MeshCoreUri.contact(profile.name, profile.pubKeyHex)
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (profile.isChannel) "Channel info" else "Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Big centered avatar. Identicons are keyed on a contact's public key; channels
            // (and contacts without a known key) fall back to initials.
            Avatar(
                seed = peerHex,
                label = profile.name,
                size = 120,
                identiconKey = if (!profile.isChannel) profile.pubKeyHex else null,
            )
            Spacer(Modifier.size(16.dp))
            Text(
                if (profile.isChannel) channelLabel(profile.name, profile.channelKind) else profile.name,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            // Public key (users) shown right under the name, same compact form as the chat list.
            if (!profile.isChannel && profile.pubKeyHex.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    formatPubKey(profile.pubKeyHex),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Direct messages to a contact are end-to-end encrypted.
            if (!profile.isChannel) {
                Spacer(Modifier.size(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "End-to-end encrypted",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // The node's own free-form description is shown only here, between the hero and the
            // actions (the deterministic name is the primary label everywhere else).
            if (!profile.isChannel && profile.description.isNotBlank()) {
                Spacer(Modifier.size(12.dp))
                Text(
                    profile.description,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.size(20.dp))

            // Actions, kept compact and up near the hero. Primary action is full-width; the
            // rest are a row of small icon-over-label buttons.
            Button(
                onClick = { onOpenConversation(peerHex) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (profile.isChannel) "Open channel" else "Message")
            }
            Spacer(Modifier.size(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactAction(Icons.Default.Edit, "Rename") { renaming = true }
                if (!profile.isChannel) {
                    CompactAction(Icons.Default.Route, "Trace") { onTrace(peerHex) }
                }
                if (profile.isChannel) {
                    CompactAction(Icons.Default.Logout, "Leave") { confirmDelete = true }
                } else if (profile.isContact) {
                    CompactAction(Icons.Default.Delete, "Delete") { confirmDelete = true }
                }
            }

            Spacer(Modifier.size(28.dp))

            // Information block.
            if (profile.isChannel) {
                ChannelInfo(profile)
            } else {
                UserInfo(profile)
            }

            // Share QR — a MeshCore URI other apps can scan to add this contact/channel.
            if (shareUri != null) {
                Spacer(Modifier.size(28.dp))
                Text(
                    if (profile.isChannel) "Scan to join this channel" else "Scan to add this contact",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                QrImage(shareUri, modifier = Modifier.size(240.dp).clickable { showShare = true })
            }
        }
    }

    if (showShare && shareUri != null) {
        ShareQrSheet(
            title = if (profile.isChannel) "Share ${channelLabel(profile.name, profile.channelKind)}" else "Share ${profile.name}",
            subtitle = if (profile.isChannel) "Scan to join this channel" else "Scan to add this contact",
            uri = shareUri,
            onDismiss = { showShare = false },
        )
    }

    if (renaming) {
        RenameDialog(
            current = profile.name,
            isChannel = profile.isChannel,
            onConfirm = { name ->
                if (profile.isChannel) vm.renameChannel(profile.pskHex, name)
                else vm.renameContact(profile.nodeHex, name)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (profile.isChannel) "Leave channel?" else "Delete contact?") },
            text = {
                Text(
                    if (profile.isChannel) "You'll stop receiving messages on ${channelLabel(profile.name, profile.channelKind)}. You can rejoin later."
                    else "Remove ${profile.name} from your contacts. Your message history stays.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (profile.isChannel) vm.leaveChannel(profile.pskHex)
                    else vm.deleteContact(profile.nodeHex)
                    confirmDelete = false
                    onBack()
                }) { Text(if (profile.isChannel) "Leave" else "Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

/** A small icon-over-label outlined button that shares a row evenly with its siblings. */
@Composable
private fun RowScope.CompactAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun UserInfo(p: ProfileInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        InfoRow("Node ID", p.nodeHex)
        if (p.pubKeyHex.isNotBlank()) InfoRow("Public key", p.pubKeyHex)
        if (p.platform.isNotBlank()) InfoRow("Platform", p.platform)
        InfoRow("Status", if (p.online) "Online (announcing)" else "Not currently visible")
        InfoRow("In contacts", if (p.isContact) "Yes" else "No")
    }
}

@Composable
private fun ChannelInfo(p: ProfileInfo) {
    val kindLabel = when (p.channelKind) {
        ChannelKind.PUBLIC -> "Public (MeshCore default)"
        ChannelKind.NAMED -> "Named (key derived from name)"
        ChannelKind.SECRET -> "Secret (shared key)"
        else -> p.channelKind.ifBlank { "—" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        InfoRow("Type", kindLabel)
        InfoRow("Channel hash", "0x%02x".format(p.channelHash))
        InfoRow("Pre-shared key", p.pskHex)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RenameDialog(
    current: String,
    isChannel: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isChannel) "Rename channel" else "Rename contact") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
