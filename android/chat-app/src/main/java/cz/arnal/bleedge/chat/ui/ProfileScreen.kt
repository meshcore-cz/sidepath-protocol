package cz.arnal.bleedge.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Logout
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
) {
    val profile by remember(peerHex) { vm.profileFor(peerHex) }.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

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
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Big centered avatar.
            Avatar(seed = peerHex, label = profile.name, size = 120)
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

            Spacer(Modifier.size(24.dp))

            // Information block.
            if (profile.isChannel) {
                ChannelInfo(profile)
            } else {
                UserInfo(profile)
            }

            Spacer(Modifier.size(28.dp))

            // Actions.
            Button(
                onClick = { onOpenConversation(peerHex) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (profile.isChannel) "Open channel" else "Message")
            }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = { renaming = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Rename")
            }

            if (profile.isChannel) {
                // The Public channel is the always-available default; don't offer to leave it.
                if (profile.channelKind != ChannelKind.PUBLIC) {
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Logout, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Leave channel")
                    }
                }
            } else if (profile.isContact) {
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Delete from contacts")
                }
            }

            // Share QR — a MeshCore URI other apps can scan to add this contact/channel.
            val shareUri = when {
                profile.isChannel && profile.pskHex.isNotBlank() ->
                    MeshCoreUri.channel(profile.name, profile.pskHex)
                !profile.isChannel && profile.pubKeyHex.isNotBlank() ->
                    MeshCoreUri.contact(profile.name, profile.pubKeyHex)
                else -> null
            }
            if (shareUri != null) {
                Spacer(Modifier.size(28.dp))
                Text(
                    if (profile.isChannel) "Scan to join this channel" else "Scan to add this contact",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                QrImage(shareUri, modifier = Modifier.size(240.dp))
            }
        }
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

@Composable
private fun UserInfo(p: ProfileInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        InfoRow("Node ID", p.nodeHex)
        if (p.pubKeyHex.isNotBlank()) InfoRow("Public key", p.pubKeyHex)
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
