package cz.arnal.bleedge.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
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

@Composable
fun ChatRoot(vm: ChatViewModel) {
    var openPeer by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(0) }

    // A direct conversation is shown full-screen over the tab scaffold.
    if (openPeer != null) {
        ConversationScreen(vm, openPeer!!, onBack = { openPeer = null })
        return
    }

    val conversations by vm.conversations.collectAsState()
    val unread = remember(conversations) { conversations.sumOf { it.unread } }

    Scaffold(
        bottomBar = {
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
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> ChatsScreen(vm, onOpenConversation = { openPeer = it })
                1 -> ConversationScreen(vm, CHANNEL_PEER, onBack = null)
                else -> SettingsScreen(vm)
            }
        }
    }
}
