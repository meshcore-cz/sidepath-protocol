package cz.arnal.bleedge.chat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import cz.arnal.bleedge.chat.ChannelSummary
import cz.arnal.bleedge.chat.ChatViewModel
import cz.arnal.bleedge.chat.MeshCoreUri
import cz.arnal.bleedge.chat.data.ChannelKind
import cz.arnal.bleedge.chat.data.channelPeerId
import cz.arnal.bleedge.chat.isHex32
import cz.arnal.bleedge.chat.toHex
import java.security.SecureRandom

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(
    vm: ChatViewModel,
    onOpenChannel: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val channels by vm.channelConversations.collectAsState()
    val joined by vm.channels.collectAsState()
    val publicJoined = remember(joined) { joined.any { it.kind == ChannelKind.PUBLIC } }
    var showJoin by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val visible = remember(channels, query) {
        if (query.isBlank()) channels
        else channels.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        SearchField(query, { query = it }, "Search channels")
                    } else {
                        Text("Channels")
                    }
                },
                actions = {
                    ConnectionStatusButton(vm)
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }) {
                        Icon(
                            if (searching) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searching) "Close search" else "Search",
                        )
                    }
                    OverflowMenu(onOpenSettings = onOpenSettings, onOpenAbout = onOpenAbout)
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showJoin = true }) {
                Icon(Icons.Default.Add, contentDescription = "Join channel")
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (searching && query.isBlank()) {
            SearchHint("Start typing to search channels…", Modifier.fillMaxSize().padding(padding))
        } else if (visible.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.Public, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
                Text(if (searching) "No matching channels" else "No channels", style = MaterialTheme.typography.titleMedium)
                if (!searching) Text("Tap + to join a channel.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(visible, key = { it.pskHex }) { ch ->
                    ChannelRow(
                        ch,
                        onClick = { onOpenChannel(channelPeerId(ch.pskHex)) },
                        onAvatarClick = { onOpenProfile(channelPeerId(ch.pskHex)) },
                    )
                }
            }
        }
    }

    if (showJoin) {
        JoinChannelSheet(
            vm = vm,
            showPublic = !publicJoined,
            onJoined = { showJoin = false },
            onDismiss = { showJoin = false },
        )
    }
}

@Composable
private fun ChannelRow(ch: ChannelSummary, onClick: () -> Unit, onAvatarClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(seed = ch.pskHex, label = ch.name, onClick = onAvatarClick)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                channelLabel(ch.name, ch.kind),
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val subtitle = when {
                ch.lastText.isBlank() -> "No messages yet"
                ch.lastSender.isBlank() -> ch.lastText
                else -> "${ch.lastSender}: ${ch.lastText}"
            }
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (ch.lastTimestampMs > 0) {
                Text(
                    formatRelative(ch.lastTimestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ch.unread > 0) {
                Spacer(Modifier.size(4.dp))
                Badge { Text("${ch.unread}") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JoinChannelSheet(
    vm: ChatViewModel,
    showPublic: Boolean,
    onJoined: () -> Unit,
    onDismiss: () -> Unit,
) {
    var namedName by remember { mutableStateOf("") }
    var secretName by remember { mutableStateOf("") }
    var secretValue by remember { mutableStateOf("") }

    // Scan a MeshCore QR; a channel URI pre-fills the secret-channel fields.
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        MeshCoreUri.parseChannel(contents)?.let { ch ->
            secretName = ch.name
            secretValue = ch.secretHex
        }
    }
    fun launchScan() = scan.launch(
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .setPrompt("Scan a MeshCore channel QR"),
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Join channel", style = MaterialTheme.typography.titleMedium)
                // Scan affordance, top-right of the title.
                IconButton(onClick = { launchScan() }) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan MeshCore QR")
                }
            }

            // Public — only offered when not already joined.
            if (showPublic) {
                Text("Public", style = MaterialTheme.typography.labelLarge)
                Text(
                    "MeshCore's default public channel (hash 0x11).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { vm.joinPublic(); onJoined() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Join Public")
                }
                HorizontalDivider()
            }

            // Named (public hash, derived from name)
            Text("Named channel (hash channels)", style = MaterialTheme.typography.labelLarge)
            Text(
                "Anyone who knows the name joins the same channel (key = SHA-256(name)).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = namedName,
                onValueChange = { namedName = it },
                label = { Text("Channel name") },
                prefix = { Text("#") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { namedName = randomChannelName() }) {
                        Icon(Icons.Default.Casino, contentDescription = "Suggest a name")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { vm.joinNamedChannel(namedName); onJoined() },
                enabled = namedName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Join named channel") }

            HorizontalDivider()

            // Secret — a raw 16-byte PSK (32 hex chars), shared out-of-band or scanned.
            Text("Secret channel", style = MaterialTheme.typography.labelLarge)
            Text(
                "Share the 32-hex-character key out-of-band, or scan a MeshCore QR.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = secretName,
                onValueChange = { secretName = it },
                label = { Text("Channel name") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { secretName = randomChannelName() }) {
                        Icon(Icons.Default.Casino, contentDescription = "Suggest a name")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            val secretValid = isHex32(secretValue)
            OutlinedTextField(
                value = secretValue,
                onValueChange = { secretValue = it.trim() },
                label = { Text("Secret (32 hex chars)") },
                singleLine = true,
                isError = secretValue.isNotEmpty() && !secretValid,
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon = {
                    Row {
                        // Generate a fresh random key — tap again for another.
                        IconButton(onClick = { secretValue = randomSecretHex() }) {
                            Icon(Icons.Default.Casino, contentDescription = "Generate random secret")
                        }
                        IconButton(onClick = { launchScan() }) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR")
                        }
                    }
                },
                supportingText = {
                    if (secretValue.isNotEmpty() && !secretValid) Text("Must be exactly 32 hexadecimal characters.")
                    else Text("Tap the dice to generate a new random key.")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { vm.joinSecretChannel(secretName, secretValue); onJoined() },
                enabled = secretValid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Join secret channel") }
        }
    }
}

// Friendly word pool for suggesting channel names (three hyphenated words, e.g. "amber-river-fox").
private val nameWords = listOf(
    "amber", "river", "fox", "echo", "delta", "north", "ember", "lunar", "cedar", "quartz",
    "harbor", "pixel", "cobalt", "maple", "raven", "summit", "tundra", "violet", "willow", "zephyr",
    "comet", "drift", "flint", "glade", "haze", "ivory", "jade", "kelp", "lark", "moss",
    "nova", "onyx", "pine", "reef", "sage", "thorn", "umber", "vapor", "wren", "yarrow",
)

private fun randomChannelName(): String = (1..3).map { nameWords.random() }.joinToString("-")

private fun randomSecretHex(): String =
    ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
