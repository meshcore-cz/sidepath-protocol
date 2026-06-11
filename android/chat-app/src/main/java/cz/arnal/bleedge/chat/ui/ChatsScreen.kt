package cz.arnal.bleedge.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.arnal.bleedge.chat.AdvertisedNode
import cz.arnal.bleedge.chat.ChatViewModel
import cz.arnal.bleedge.chat.ConversationSummary
import cz.arnal.bleedge.chat.nameFromPubKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    vm: ChatViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrace: ((String) -> Unit)? = null,
    onOpenRxLog: (() -> Unit)? = null,
) {
    val conversations by vm.conversations.collectAsState()
    val myNode by vm.nodeId.collectAsState()
    val myName by vm.myName.collectAsState()
    val myPubKeyHex by vm.myPubKeyHex.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val visible = remember(conversations, query) {
        if (query.isBlank()) conversations
        else conversations.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenSettings) {
                        Avatar(
                            seed = myNode.toHexString(),
                            label = myName.ifBlank { "Me" },
                            identiconKey = myPubKeyHex,
                            size = 32,
                        )
                    }
                },
                title = {
                    if (searching) {
                        SearchField(query, { query = it }, "Search chats")
                    } else {
                        Text("BLEEdge")
                    }
                },
                actions = {
                    ConnectionStatusButton(vm, onOpenTrace = onOpenTrace, onOpenRxLog = onOpenRxLog)
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }) {
                        Icon(
                            if (searching) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searching) "Close search" else "Search",
                        )
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onOpenSettings()
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showPicker = true }) {
                Icon(Icons.Default.Create, contentDescription = "New chat")
            }
        },
        // Root scaffold reserves the bottom-nav space; TopAppBar handles the status bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        when {
            searching && query.isBlank() ->
                SearchHint("Start typing to search chats…", Modifier.fillMaxSize().padding(padding))
            visible.isEmpty() ->
                EmptyState(Modifier.fillMaxSize().padding(padding), searching = searching)
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(visible, key = { it.peerHex }) { conv ->
                    ConversationRow(
                        conv,
                        onClick = { onOpenConversation(conv.peerHex) },
                        onAvatarClick = { onOpenProfile(conv.peerHex) },
                    )
                }
            }
        }
    }

    if (showPicker) {
        NewChatSheet(
            vm = vm,
            onPick = { node ->
                vm.startChat(node)
                showPicker = false
                onOpenConversation(node.nodeHex)
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun ConversationRow(conv: ConversationSummary, onClick: () -> Unit, onAvatarClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(seed = conv.peerHex, label = conv.title, identiconKey = conv.pubKeyHex, onClick = onAvatarClick)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(conv.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val pub = formatPubKey(conv.pubKeyHex)
            if (pub.isNotEmpty()) {
                Text(
                    pub,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                conv.lastText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatRelative(conv.lastTimestampMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (conv.unread > 0) {
                Spacer(Modifier.size(4.dp))
                androidx.compose.material3.Badge { Text("${conv.unread}") }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, searching: Boolean) {
    Column(
        modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Chat,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        if (searching) {
            Text("No matching chats", style = MaterialTheme.typography.titleMedium)
        } else {
            Text("No chats yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tap the pencil to start a chat with a nearby node.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatSheet(vm: ChatViewModel, onPick: (AdvertisedNode) -> Unit, onDismiss: () -> Unit) {
    val nodes by vm.advertisedNodes.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "New chat",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
            if (nodes.isEmpty()) {
                Text(
                    "No nodes discovered yet. Make sure another BLEEdge node is nearby and advertising.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                nodes.forEach { node ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(node) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val label = nameFromPubKey(node.pubKeyHex).ifBlank { node.nodeHex.take(16) }
                        Avatar(seed = node.nodeHex, label = label, identiconKey = node.pubKeyHex)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(label, fontWeight = FontWeight.Medium)
                            Text(
                                node.nodeHex.take(16),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
