package cz.arnal.bleedge.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cz.arnal.bleedge.chat.ChatViewModel
import cz.arnal.bleedge.chat.data.CHANNEL_PEER

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChatRoot(vm: ChatViewModel) {
    var openPeer by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(0) }

    // A direct conversation / settings are shown full-screen over the tab scaffold.
    if (openPeer != null) {
        ConversationScreen(vm, openPeer!!, onBack = { openPeer = null })
        return
    }
    if (showSettings) {
        SettingsScreen(vm, onBack = { showSettings = false })
        return
    }

    val conversations by vm.conversations.collectAsState()
    val unread = remember(conversations) { conversations.sumOf { it.unread } }
    val imeVisible = WindowInsets.isImeVisible

    Scaffold(
        // Each tab screen owns its own system-bar insets via its TopAppBar / composer,
        // so the root must not also pad the content (that double-counted the status bar).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // Hide the bottom nav while typing: it frees room and lets the conversation
            // composer's imePadding lift cleanly above the keyboard (no nav-bar-sized gap).
            if (!imeVisible) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = {
                            BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) {
                                Icon(Icons.Default.Forum, contentDescription = "Chats")
                            }
                        },
                        label = { Text("Chats") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Default.Public, contentDescription = "Channel") },
                        label = { Text("Channel") },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Default.Hub, contentDescription = "Network") },
                        label = { Text("Network") },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> ChatsScreen(
                    vm,
                    onOpenConversation = { openPeer = it },
                    onOpenSettings = { showSettings = true },
                )
                1 -> ConversationScreen(vm, CHANNEL_PEER, onBack = null)
                else -> NetworkScreen(vm)
            }
        }
    }
}
