package cz.arnal.bleedge.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.arnal.bleedge.chat.ChatViewModel
import kotlinx.coroutines.delay
import cz.arnal.bleedge.chat.MeshCoreUri
import cz.arnal.bleedge.chat.ProfileInfo
import cz.arnal.bleedge.chat.data.Message
import cz.arnal.bleedge.chat.data.channelPskHexOf
import cz.arnal.bleedge.chat.data.isChannelPeer

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ConversationScreen(
    vm: ChatViewModel,
    peerHex: String,
    onBack: (() -> Unit)?,
    onOpenProfile: ((String) -> Unit)? = null,
) {
    val isChannel = isChannelPeer(peerHex)
    val messages by remember(peerHex) { vm.messagesFor(peerHex) }.collectAsState()
    val profile by remember(peerHex) { vm.profileFor(peerHex) }.collectAsState()
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var detailsFor by remember { mutableStateOf<Message?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    LaunchedEffect(peerHex, messages.size) { vm.markRead(peerHex) }

    // Whether the remote peer is currently typing (DMs only).
    val typingPeers by vm.typingPeers.collectAsState()
    val peerTyping = !isChannel && peerHex in typingPeers

    // Drive outgoing typing hints: each keystroke (re)arms this; after ~5s of no change we
    // consider the user stopped. An empty field stops immediately, as does leaving the screen.
    LaunchedEffect(draft.text, peerHex, isChannel) {
        if (isChannel) return@LaunchedEffect
        if (draft.text.isBlank()) {
            vm.stopTyping(peerHex)
            return@LaunchedEffect
        }
        vm.onUserTyping(peerHex)
        delay(5_000)
        vm.stopTyping(peerHex)
    }
    DisposableEffect(peerHex) { onDispose { vm.stopTyping(peerHex) } }

    // Channel participants (name → node id) learned from the channel's messages; used to
    // resolve a tapped @mention to a profile, and to power the @-autocomplete.
    val mentionTargets = remember(messages) {
        messages.filter { it.senderName.isNotBlank() && it.senderHex.isNotBlank() }
            .associate { it.senderName to it.senderHex }
    }
    val onMentionClick: (String) -> Unit = { name ->
        mentionTargets[name]?.let { hex -> onOpenProfile?.invoke(hex) }
    }

    val mentionQuery = if (isChannel) mentionQueryOf(draft.text) else null
    val suggestions = if (mentionQuery != null) {
        mentionTargets.keys.filter { it.contains(mentionQuery, ignoreCase = true) }.sorted().take(6)
    } else emptyList()

    val shown = remember(messages, searching, searchQuery) {
        if (searching && searchQuery.isNotBlank()) messages.filter { it.text.contains(searchQuery, ignoreCase = true) }
        else messages
    }

    val shareUri = when {
        isChannel && profile.pskHex.isNotBlank() -> MeshCoreUri.channel(profile.name, profile.pskHex)
        !isChannel && profile.pubKeyHex.isNotBlank() -> MeshCoreUri.contact(profile.name, profile.pubKeyHex)
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                title = {
                    if (searching) {
                        SearchField(searchQuery, { searchQuery = it }, "Search messages")
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = if (onOpenProfile != null) Modifier.clickable { onOpenProfile(peerHex) } else Modifier,
                        ) {
                            Avatar(
                                seed = peerHex,
                                label = profile.name,
                                size = 36,
                                identiconKey = if (!isChannel) profile.pubKeyHex else null,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(profile.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                val showKey = !isChannel && !peerTyping && profile.pubKeyHex.isNotBlank()
                                val subtitle = when {
                                    peerTyping -> "typing…"
                                    isChannel -> "Channel · shared-key encrypted"
                                    profile.pubKeyHex.isNotBlank() -> formatPubKey(profile.pubKeyHex)
                                    else -> "End-to-end encrypted"
                                }
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = if (showKey) FontFamily.Monospace else FontFamily.Default,
                                    color = if (peerTyping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                actions = {
                    ConnectionStatusButton(vm)
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) searchQuery = ""
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
                            text = { Text(if (isChannel) "Channel info" else "Contact info") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = { menuOpen = false; onOpenProfile?.invoke(peerHex) },
                        )
                        if (shareUri != null) {
                            DropdownMenuItem(
                                text = { Text(if (isChannel) "Share channel" else "Share contact") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = { menuOpen = false; showShare = true },
                            )
                        }
                        if (isChannel) {
                            DropdownMenuItem(
                                text = { Text("Leave channel") },
                                leadingIcon = { Icon(Icons.Default.Logout, contentDescription = null) },
                                onClick = { menuOpen = false; confirmLeave = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (suggestions.isNotEmpty()) {
                    MentionSuggestions(suggestions) { name ->
                        val t = insertMention(draft.text, name)
                        draft = TextFieldValue(t, TextRange(t.length))
                    }
                }
                MessageInput(
                    value = draft,
                    onChange = { draft = it },
                    fullScreen = onBack != null,
                    onEmoji = { showEmoji = true },
                    onSend = {
                        val text = draft.text
                        if (text.isNotBlank()) {
                            if (isChannel) vm.sendChannelMessage(channelPskHexOf(peerHex), text)
                            else vm.sendChat(peerHex, text)
                            draft = TextFieldValue("")
                        }
                    },
                )
            }
        },
        // TopAppBar + composer handle their own system-bar/ime insets.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (searching && searchQuery.isBlank()) {
            SearchHint("Start typing to search messages…", Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        val listState = rememberLazyListState()
        // Jump straight to the newest message on first load (no visible scroll-down), then
        // animate to the bottom only for messages arriving while the chat is open. Skipped
        // while searching so the filtered view doesn't auto-scroll.
        var positioned by remember(peerHex) { mutableStateOf(false) }
        LaunchedEffect(messages.size) {
            if (searching || messages.isEmpty()) return@LaunchedEffect
            // +1 for the header panel item that precedes the messages.
            if (!positioned) {
                listState.scrollToItem(messages.size)
                positioned = true
            } else {
                listState.animateScrollToItem(messages.size)
            }
        }
        val imeVisible = WindowInsets.isImeVisible
        LaunchedEffect(imeVisible) {
            if (!searching && imeVisible && messages.isNotEmpty() && !listState.canScrollForward) {
                listState.animateScrollToItem(messages.size)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            // Minimal breathing room at the top and bottom of the message list.
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // A header panel with a big avatar + basic details; also fills the empty space of a
            // brand-new conversation. Hidden while searching so results aren't pushed down.
            if (!searching) {
                item(key = "__header__") {
                    ConversationHeaderPanel(profile, isChannel, onClick = { onOpenProfile?.invoke(peerHex) })
                }
            }
            itemsIndexed(shown, key = { _, m -> m.id }) { i, msg ->
                val sender = if (isChannel) msg.senderName.ifBlank { vm.nameForHex(msg.senderHex) }
                else vm.nameForHex(msg.senderHex)
                Column {
                    // Date separator whenever the day changes (first message always gets one).
                    if (!searching) {
                        val prev = shown.getOrNull(i - 1)
                        if (prev == null || differentDay(prev.timestampMs, msg.timestampMs)) {
                            DateSeparator(dateLabel(msg.timestampMs))
                        }
                    }
                    MessageBubble(msg, isChannel, sender, onMentionClick) { detailsFor = msg }
                }
            }
        }
    }

    detailsFor?.let { msg ->
        MessageDetailsSheet(msg, vm, onOpenProfile = onOpenProfile) { detailsFor = null }
    }
    if (showShare && shareUri != null) {
        ShareQrSheet(
            title = if (isChannel) "Share ${channelLabel(profile.name, profile.channelKind)}" else "Share ${profile.name}",
            subtitle = if (isChannel) "Scan to join this channel" else "Scan to add this contact",
            uri = shareUri,
            onDismiss = { showShare = false },
        )
    }
    if (showEmoji) {
        EmojiPickerSheet(
            onPick = { emoji ->
                // Append the emoji, drop the cursor at the end, and close the picker.
                val t = draft.text + emoji
                draft = TextFieldValue(t, TextRange(t.length))
                showEmoji = false
            },
            onDismiss = { showEmoji = false },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave channel?") },
            text = { Text("You'll stop receiving messages on ${channelLabel(profile.name, profile.channelKind)}. You can rejoin later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    vm.leaveChannel(profile.pskHex)
                    onBack?.invoke()
                }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MessageBubble(
    msg: Message,
    isChannel: Boolean,
    senderLabel: String,
    onMentionClick: (String) -> Unit,
    onClick: () -> Unit,
) {
    val mine = !msg.incoming
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 300.dp).clickable(onClick = onClick),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (isChannel && msg.incoming) {
                    Text(
                        senderLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                MessageContent(msg.text, enableMentions = isChannel, onMentionClick = onMentionClick)
                Spacer(Modifier.size(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        formatMessageTime(msg.timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (mine) DeliveryTick(msg.status) else RouteIndicator(msg.routeHex)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MessageInput(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    fullScreen: Boolean,
    onEmoji: () -> Unit,
    onSend: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (fullScreen) Modifier.navigationBarsPadding() else Modifier)
                .imePadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onEmoji) {
                Icon(Icons.Default.Mood, contentDescription = "Emoji")
            }
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                    // Enter sends; Shift+Enter inserts a newline at the cursor.
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                        if (e.isShiftPressed) {
                            val sel = value.selection
                            val text = value.text.substring(0, sel.start) + "\n" + value.text.substring(sel.end)
                            onChange(TextFieldValue(text, TextRange(sel.start + 1)))
                            true
                        } else { onSend(); true }
                    } else false
                },
                placeholder = { Text("Message") },
                maxLines = 5,
                // Highlight @[mentions] as they're typed; the stored text is unchanged.
                visualTransformation = mentionInputTransformation(accent),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            IconButton(onClick = onSend, enabled = value.text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

/** Horizontal strip of @-mention candidates shown above the composer while typing "@". */
@Composable
private fun MentionSuggestions(names: List<String>, onPick: (String) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(names, key = { it }) { name ->
                AssistChip(onClick = { onPick(name) }, label = { Text("@$name") })
            }
        }
    }
}

/** Centered day separator ("Today" / "Yesterday" / date) between message groups. */
@Composable
private fun DateSeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Header at the top of the message stream: a large avatar and a few basic details. Doubles as
 * the filler for a brand-new, empty conversation.
 */
@Composable
private fun ConversationHeaderPanel(profile: ProfileInfo, isChannel: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(
            seed = profile.peerHex,
            label = profile.name,
            size = 88,
            identiconKey = if (!isChannel) profile.pubKeyHex else null,
        )
        Spacer(Modifier.size(10.dp))
        Text(
            if (isChannel) channelLabel(profile.name, profile.channelKind) else profile.name,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(4.dp))
        if (isChannel) {
            Text(
                "Group channel · 0x%02x".format(profile.channelHash),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(4.dp))
                Text(
                    "Shared-key encrypted",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            if (profile.pubKeyHex.isNotBlank()) {
                Text(
                    formatPubKey(profile.pubKeyHex),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(4.dp))
                Text(
                    "End-to-end encrypted",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The active mention query: the partial after a trailing "@" (word-boundary), or null. */
fun mentionQueryOf(draft: String): String? =
    Regex("""(?:^|\s)@([^\s@]*)$""").find(draft)?.groupValues?.get(1)

/** Replaces the trailing "@partial" the user is typing with a `@[Name]` token (and a space). */
fun insertMention(draft: String, name: String): String =
    Regex("""(?:^|\s)@([^\s@]*)$""").replace(draft) { m ->
        val lead = if (m.value.startsWith("@")) "" else m.value.take(1)
        "$lead@[$name] "
    }
