package cz.arnal.bleedge.chat

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.arnal.bleedge.chat.data.Channel
import cz.arnal.bleedge.chat.data.ChannelKind
import cz.arnal.bleedge.chat.data.ChatDatabase
import cz.arnal.bleedge.chat.data.Contact
import cz.arnal.bleedge.chat.data.Message
import cz.arnal.bleedge.chat.data.MsgStatus
import cz.arnal.bleedge.chat.data.channelPeerId
import cz.arnal.bleedge.chat.data.channelPskHexOf
import cz.arnal.bleedge.chat.data.isChannelPeer
import cz.arnal.bleedge.chatproto.ChatChannel
import cz.arnal.bleedge.chatproto.ChatKind
import cz.arnal.bleedge.protocol.BLEEdge
import cz.arnal.bleedge.protocol.Identity
import cz.arnal.bleedge.protocol.NodeId
import cz.arnal.bleedge.protocol.PayloadProtocol
import cz.arnal.bleedge.protocol.TraceResponseBody
import cz.arnal.bleedge.transport.PHYMode
import cz.arnal.bleedge.service.BLEEdgeService
import cz.arnal.bleedge.service.LogEntry
import cz.arnal.bleedge.service.MeshStats
import cz.arnal.bleedge.service.PeerInfo
import cz.arnal.bleedge.service.ReceivedMessage
import cz.arnal.bleedge.service.TopologyEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bleedge_chat_prefs")
private val SEED_KEY = stringPreferencesKey("seed")
private val DESC_KEY = stringPreferencesKey("description")
private val NAME_KEY = stringPreferencesKey("name")
private val DM_RETRY_DELAY_KEY = intPreferencesKey("dm_retry_delay_ms")
private val DM_MAX_TRIES_KEY = intPreferencesKey("dm_max_tries")
private const val DEFAULT_DM_RETRY_DELAY_MS = 3000
private const val DEFAULT_DM_MAX_TRIES = 3
private val PHY_MODE_KEY = stringPreferencesKey("phy_mode")
private val AVATAR_STYLE_KEY = stringPreferencesKey("avatar_style")
private val PUBLIC_SEEDED_KEY = booleanPreferencesKey("public_seeded")
private val THEME_KEY = stringPreferencesKey("theme_mode")

/** How contact/channel avatars are drawn. */
enum class AvatarStyle(val value: String) {
    IDENTICON("identicon"), // default: a deterministic identicon from the public key
    INITIALS("initials");   // colored circle with initials

    companion object {
        fun fromValue(v: String?) = entries.firstOrNull { it.value == v } ?: IDENTICON
    }
}

/** App theme preference. */
enum class ThemeMode(val value: String) {
    AUTO("auto"), LIGHT("light"), DARK("dark");

    companion object {
        fun fromValue(v: String?) = entries.firstOrNull { it.value == v } ?: AUTO
    }
}

/** Live state of an in-progress / completed trace, shown on the trace page. */
data class TraceUi(
    val peerHex: String,
    val running: Boolean,
    val tag: Int? = null,
    val startedAtMs: Long = 0L,
    val rttMs: Long? = null, // round-trip time once the response arrives
    val result: TraceResponseBody? = null,
    val error: String? = null,
)

/** A row in the Chats list. */
data class ConversationSummary(
    val peerHex: String,
    val title: String,
    val pubKeyHex: String, // 32-byte Ed25519 key (hex), or "" if not known yet
    val lastText: String,
    val lastTimestampMs: Long,
    val unread: Int,
)

/**
 * A contact/channel profile, shown on the full-screen profile page opened by tapping an avatar.
 * For a user, [pubKeyHex]/[nodeHex]/[isContact] are set; for a channel, [pskHex]/[channelKind]/
 * [channelHash] are.
 */
data class ProfileInfo(
    val peerHex: String,
    val isChannel: Boolean,
    val name: String,
    val nodeHex: String = "",
    val pubKeyHex: String = "",
    val isContact: Boolean = false,
    val online: Boolean = false, // present in the live topology (signed ANNOUNCE)
    val description: String = "", // node's own free-form description, shown only on the profile
    val platform: String = "",    // node's OS/device string, shown in profile node information
    val channelKind: String = "",
    val channelHash: Int = 0,
    val pskHex: String = "",
    val neighborHexes: List<String> = emptyList(), // this node's directly-connected neighbors (from its ANNOUNCE)
)

/** A row in the Channels list. */
data class ChannelSummary(
    val pskHex: String,
    val name: String,
    val kind: String, // ChannelKind.{PUBLIC,NAMED,SECRET}
    val lastSender: String, // author of the last message (channel plaintext name), or ""
    val lastText: String,
    val lastTimestampMs: Long,
    val unread: Int,
)

/** Connection health shown as a colored status dot. */
enum class ConnState { CONNECTED, NO_PEERS, OFFLINE, ERROR }

/** A node we've heard advertise, offered in the "New chat" picker. */
data class AdvertisedNode(
    val nodeHex: String,
    val description: String,
    val pubKeyHex: String,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = ChatDatabase.get(app).chatDao()

    private val _service = MutableStateFlow<BLEEdgeService?>(null)
    private var serviceBound = false

    // packet keys we've already folded into the DB, to dedup the cumulative receivedMessages list.
    private val processed = mutableSetOf<String>()

    private val _permissionsGranted = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    private val _seedHex = MutableStateFlow("")
    val seedHex: StateFlow<String> = _seedHex.asStateFlow()

    /** This node's own 32-byte public key (hex), derived from the seed — for the self identicon. */
    val myPubKeyHex: StateFlow<String> = _seedHex.map { hex ->
        runCatching { Identity.fromSeed(hex.hexToBytes()).publicKey.toHex() }.getOrDefault("")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _description = MutableStateFlow("")
    val description: StateFlow<String> = _description.asStateFlow()

    /** The user's chosen name override (may be blank = use the deterministic default). */
    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    /** This node's effective display name: the override if set, else the deterministic default. */
    val myName: StateFlow<String> = combine(_name, myPubKeyHex) { n, pub ->
        n.ifBlank { nameFromPubKey(pub) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Direct-message delivery retry: wait this long for an ACK before re-sending. */
    private val _dmRetryDelayMs = MutableStateFlow(DEFAULT_DM_RETRY_DELAY_MS)
    val dmRetryDelayMs: StateFlow<Int> = _dmRetryDelayMs.asStateFlow()

    /** Total DM send attempts (including the first) before giving up. */
    private val _dmMaxTries = MutableStateFlow(DEFAULT_DM_MAX_TRIES)
    val dmMaxTries: StateFlow<Int> = _dmMaxTries.asStateFlow()

    private val _avatarStyle = MutableStateFlow(AvatarStyle.IDENTICON)
    val avatarStyle: StateFlow<AvatarStyle> = _avatarStyle.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.AUTO)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _trace = MutableStateFlow<TraceUi?>(null)
    val trace: StateFlow<TraceUi?> = _trace.asStateFlow()

    // Peers (hex) currently shown as "typing…". Each entry has a removal job that expires it
    // if no fresh typing hint arrives; a real incoming message clears it immediately.
    private val _typingPeers = MutableStateFlow<Set<String>>(emptySet())
    val typingPeers: StateFlow<Set<String>> = _typingPeers.asStateFlow()
    private val typingExpiry = mutableMapOf<String, Job>()

    // Outgoing typing: a single loop re-sends a hint every 10s for the peer we're typing to.
    private var outgoingTypingJob: Job? = null
    private var outgoingTypingPeer: String? = null

    val nodeId: StateFlow<NodeId> = _service.flatMapLatest {
        it?.nodeId ?: flowOf(NodeId.BROADCAST)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NodeId.BROADCAST)

    val phyMode: StateFlow<PHYMode> = _service.flatMapLatest {
        it?.phyMode ?: flowOf(PHYMode.ONE_M)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PHYMode.ONE_M)

    val topology: StateFlow<List<TopologyEntry>> = _service.flatMapLatest {
        it?.knownTopology ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val connectedPeers: StateFlow<List<PeerInfo>> = _service.flatMapLatest {
        it?.connectedPeers ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isRunning: StateFlow<Boolean> = _service.flatMapLatest {
        it?.isRunning ?: flowOf(false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val stats: StateFlow<MeshStats> = _service.flatMapLatest {
        it?.stats ?: flowOf(MeshStats())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MeshStats())

    /** Routing/SYS log entries, newest first — raw diagnostic text log. */
    val routingLog: StateFlow<List<LogEntry>> = _service.flatMapLatest {
        it?.routingLog ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Decoded BLEEdge packets seen on the radio, newest first — backs the Rx Log screen. */
    val rxPackets: StateFlow<List<cz.arnal.bleedge.service.RxPacket>> = _service.flatMapLatest {
        it?.rxPackets ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Wall-clock time of the most recent received packet, or null if none yet. */
    val lastPacketAtMs: StateFlow<Long?> = rxPackets
        .map { it.firstOrNull()?.timestampMs }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Repeats of our own flooded (broadcast/channel) messages heard back on the radio, keyed by the
     * message's packet-id hex (= [cz.arnal.bleedge.chat.data.Message.id]). Each sample carries the
     * receiving link's RSSI. Surfaced as an icon+count on the bubble and a list in message details.
     */
    val floodRepeats: StateFlow<Map<String, List<cz.arnal.bleedge.service.RepeatSample>>> =
        _service.flatMapLatest { it?.floodRepeats ?: flowOf(emptyMap()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Per-DM delivery progress (attempts, acked, failed), keyed by the message's packet-id hex. */
    val dmDeliveries: StateFlow<Map<String, cz.arnal.bleedge.service.DmDelivery>> =
        _service.flatMapLatest { it?.dmDeliveries ?: flowOf(emptyMap()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Starts/stops the mesh radio (advertise + scan + GATT). Surfaced on the Network page. */
    fun startMesh() { _service.value?.startBLE() }
    fun stopMesh() { _service.value?.stopBLE() }

    /** Overall connection health, surfaced as the status dot in the Chats header. */
    val connectionStatus: StateFlow<ConnState> =
        combine(permissionsGranted, isRunning, connectedPeers) { granted, running, peers ->
            when {
                !granted -> ConnState.ERROR
                !running -> ConnState.OFFLINE
                peers.isEmpty() -> ConnState.NO_PEERS
                else -> ConnState.CONNECTED
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ConnState.OFFLINE)

    val contacts: StateFlow<List<Contact>> =
        dao.contacts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val channels: StateFlow<List<Channel>> =
        dao.channels().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Conversations for the Chats tab (direct messages only — channels are a separate tab). */
    val conversations: StateFlow<List<ConversationSummary>> =
        combine(dao.allMessages(), contacts, topology) { msgs, contacts, topo ->
            val byNode = contacts.associateBy { it.nodeHex }
            msgs.filter { !isChannelPeer(it.peerHex) }
                .groupBy { it.peerHex }
                .map { (peer, list) ->
                    val last = list.maxByOrNull { it.timestampMs }!!
                    ConversationSummary(
                        peerHex = peer,
                        title = displayName(peer, byNode, topo),
                        pubKeyHex = publicKeyHex(peer, byNode, topo),
                        lastText = last.text,
                        lastTimestampMs = last.timestampMs,
                        unread = list.count { it.incoming && !it.read },
                    )
                }
                .sortedByDescending { it.lastTimestampMs }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Channels list (joined channels with their latest message + unread count). */
    val channelConversations: StateFlow<List<ChannelSummary>> =
        combine(channels, dao.allMessages()) { chans, msgs ->
            val byPeer = msgs.groupBy { it.peerHex }
            chans.map { ch ->
                val list = byPeer[channelPeerId(ch.pskHex)].orEmpty()
                val last = list.maxByOrNull { it.timestampMs }
                ChannelSummary(
                    pskHex = ch.pskHex,
                    name = ch.name,
                    kind = ch.kind,
                    lastSender = last?.senderName ?: "",
                    lastText = last?.text ?: "",
                    lastTimestampMs = last?.timestampMs ?: 0L,
                    unread = list.count { it.incoming && !it.read },
                )
            }.sortedByDescending { it.lastTimestampMs }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Every node we can start an encrypted chat with — i.e. whose public key we know,
     * whether from the live topology (signed ANNOUNCE) or a saved contact (learned from a
     * received DM or a previous chat).
     */
    val advertisedNodes: StateFlow<List<AdvertisedNode>> =
        combine(topology, contacts, nodeId) { topo, contacts, me ->
            val meHex = me.toHex()
            val byHex = LinkedHashMap<String, AdvertisedNode>()
            contacts.forEach { c ->
                if (c.nodeHex != meHex && c.pubKeyHex.length == 64) {
                    byHex[c.nodeHex] = AdvertisedNode(c.nodeHex, c.description, c.pubKeyHex)
                }
            }
            topo.forEach { t ->
                val hex = t.nodeId.toHex()
                if (hex != meHex && t.publicKey.size == 32) {
                    val desc = t.description.ifBlank { byHex[hex]?.description ?: "" }
                    byHex[hex] = AdvertisedNode(hex, desc, t.publicKey.toHex())
                }
            }
            byHex.values.sortedBy { it.description.ifBlank { it.nodeHex } }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun messagesFor(peerHex: String): StateFlow<List<Message>> =
        dao.messagesFor(peerHex).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun displayNameFor(peerHex: String): StateFlow<String> =
        combine(contacts, topology) { c, t ->
            displayName(peerHex, c.associateBy { it.nodeHex }, t)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, shortHex(peerHex))

    // ---- service lifecycle ---------------------------------------------------

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as BLEEdgeService.LocalBinder).getService()
            _service.value = service
            viewModelScope.launch { initializeService(service) }
            observeIncoming(service)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            _service.value = null
            serviceBound = false
        }
    }

    fun onPermissionsGranted() {
        _permissionsGranted.value = true
        bindService()
    }

    private fun bindService() {
        if (serviceBound) return
        serviceBound = true
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, BLEEdgeService::class.java)
        ctx.startForegroundService(intent)
        ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private suspend fun initializeService(service: BLEEdgeService) {
        val ctx = getApplication<Application>()
        val pref = ctx.dataStore.data.first()
        val seedHex = pref[SEED_KEY] ?: generateSeedHex().also { gen ->
            ctx.dataStore.edit { it[SEED_KEY] = gen }
        }
        val desc = pref[DESC_KEY] ?: ""
        val nodeName = pref[NAME_KEY] ?: ""
        val retryDelay = pref[DM_RETRY_DELAY_KEY] ?: DEFAULT_DM_RETRY_DELAY_MS
        val maxTries = pref[DM_MAX_TRIES_KEY] ?: DEFAULT_DM_MAX_TRIES
        val phy = PHYMode.fromString(pref[PHY_MODE_KEY] ?: PHYMode.ONE_M.value)
        _seedHex.value = seedHex
        _description.value = desc
        _name.value = nodeName
        _dmRetryDelayMs.value = retryDelay
        _dmMaxTries.value = maxTries
        _avatarStyle.value = AvatarStyle.fromValue(pref[AVATAR_STYLE_KEY])
        _themeMode.value = ThemeMode.fromValue(pref[THEME_KEY])

        val identity = Identity.fromSeed(seedHex.hexToBytes())
        service.initialize(identity, phy, emptySet(), desc, nodeName, retryDelay.toLong(), maxTries)
        ensurePublicChannel()
    }

    /**
     * Joins MeshCore's default Public channel exactly once, for a brand-new user. After this
     * one-time seeding the user is free to leave Public and it won't be re-added.
     */
    private suspend fun ensurePublicChannel() {
        val ctx = getApplication<Application>()
        if (ctx.dataStore.data.first()[PUBLIC_SEEDED_KEY] == true) return
        ctx.dataStore.edit { it[PUBLIC_SEEDED_KEY] = true }
        val pskHex = ChatChannel.PUBLIC_SECRET.toHex()
        if (dao.channelByPsk(pskHex) == null) {
            dao.upsertChannel(
                Channel(
                    pskHex = pskHex,
                    name = "Public",
                    hashByte = ChatChannel.channelHash(ChatChannel.PUBLIC_SECRET).toInt() and 0xFF,
                    kind = ChannelKind.PUBLIC,
                )
            )
        }
    }

    init {
        // Load display preferences early so the theme/avatars are right before the service binds.
        viewModelScope.launch {
            val pref = getApplication<Application>().dataStore.data.first()
            _avatarStyle.value = AvatarStyle.fromValue(pref[AVATAR_STYLE_KEY])
            _themeMode.value = ThemeMode.fromValue(pref[THEME_KEY])
        }
    }

    fun setAvatarStyle(style: AvatarStyle) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[AVATAR_STYLE_KEY] = style.value }
            _avatarStyle.value = style
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[THEME_KEY] = mode.value }
            _themeMode.value = mode
        }
    }

    /**
     * Starts a source-routed trace to a node; results arrive via [trace]. With an empty [route] the
     * router picks the path automatically; pass an explicit hop list (NOT including this node) to
     * trace a route the user specified by hand or by picking nodes.
     */
    fun startTrace(peerHex: String, route: List<NodeId> = emptyList()) {
        val service = _service.value
        if (service == null) {
            _trace.value = TraceUi(peerHex, running = false, error = "Mesh service not ready yet.")
            return
        }
        val tag = service.sendTrace(NodeId.fromHex(peerHex), route)
        _trace.value = if (tag == null) {
            TraceUi(peerHex, running = false, error = if (route.isEmpty())
                "No route to this node yet — try again once it's in the network."
            else
                "Couldn't start that route — its first hop must be a directly connected peer.")
        } else {
            TraceUi(peerHex, running = true, tag = tag, startedAtMs = System.currentTimeMillis())
        }
    }

    /**
     * Parses a manual route string like "d503fdbcb61c654f,be0d40fda9b839b5,d503fdbcb61c654f" into
     * NodeIDs. Accepts comma/space/newline separators; each token must be 16 hex chars (an 8-byte
     * NodeId). Returns null if any token is malformed so the UI can show an error.
     */
    fun parseManualRoute(text: String): List<NodeId>? {
        val tokens = text.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.map { tok ->
            if (tok.length != BLEEdge.NODE_ID_BYTES * 2 || tok.any { it.digitToIntOrNull(16) == null }) return null
            NodeId.fromHex(tok.lowercase())
        }
    }

    fun clearTrace() { _trace.value = null }

    private fun handleTraceResponse(msg: ReceivedMessage) {
        val result = msg.traceResponse ?: return
        val cur = _trace.value ?: return
        if (cur.tag != null && cur.tag.toLong() == result.tag) {
            val rtt = (System.currentTimeMillis() - cur.startedAtMs).coerceAtLeast(0)
            _trace.value = cur.copy(running = false, rttMs = rtt, result = result)
        }
    }

    private fun observeIncoming(service: BLEEdgeService) {
        viewModelScope.launch {
            service.receivedMessages.collect { list ->
                list.forEach { handleIncoming(it) }
            }
        }
        // When a DM exhausts all retries with no ACK, mark it failed (errored) in the chat.
        viewModelScope.launch {
            service.dmDeliveries.collect { map ->
                map.forEach { (idHex, d) ->
                    if (d.failed && !d.acked && processed.add("fail:$idHex")) {
                        dao.updateDelivery(idHex, MsgStatus.FAILED, "")
                    }
                }
            }
        }
    }

    private suspend fun handleIncoming(msg: ReceivedMessage) {
        if (msg.isAck) {
            val acked = msg.ackedId ?: return
            val key = "ack:" + acked.toHex()
            if (!processed.add(key)) return
            dao.updateDelivery(acked.toHex(), MsgStatus.DELIVERED, msg.path.toRouteHex())
            return
        }
        if (msg.protocol == PayloadProtocol.BLEEDGE_CONTROL && msg.traceResponse != null) {
            handleTraceResponse(msg); return
        }
        when (msg.chatKind) {
            ChatKind.DIRECT_TEXT -> handleIncomingDm(msg)
            ChatKind.CHANNEL_TEXT -> handleIncomingChannel(msg)
            ChatKind.TYPING -> showTyping(msg.fromNodeId.toHex())
            else -> return // PUBLIC_TEXT / other: not part of the direct/channel chat model
        }
    }

    /** Marks [peerHex] as typing and (re)arms a timer to clear it if no fresh hint arrives. */
    private fun showTyping(peerHex: String) {
        _typingPeers.value = _typingPeers.value + peerHex
        typingExpiry.remove(peerHex)?.cancel()
        // A bit longer than the sender's 10s resend so a steady typist stays "typing".
        typingExpiry[peerHex] = viewModelScope.launch {
            delay(13_000)
            clearTyping(peerHex)
        }
    }

    private fun clearTyping(peerHex: String) {
        typingExpiry.remove(peerHex)?.cancel()
        if (peerHex in _typingPeers.value) _typingPeers.value = _typingPeers.value - peerHex
    }

    private suspend fun handleIncomingDm(msg: ReceivedMessage) {
        if (!processed.add(msg.datagramId.toHex())) return
        val peer = msg.fromNodeId.toHex()
        // A real message means they're no longer typing — drop the indicator at once.
        clearTyping(peer)
        dao.insertMessage(
            Message(
                id = msg.datagramId.toHex().ifEmpty { newId() },
                peerHex = peer,
                senderHex = peer,
                incoming = true,
                text = msg.text ?: "[unable to decrypt]",
                timestampMs = msg.timestampMs,
                status = MsgStatus.DELIVERED,
                routeHex = msg.path.toRouteHex(),
                read = false,
            )
        )
        // The service already verified outer.source == sender_public_key[:10]; save the
        // sender's key so we can reply even if the node isn't (yet) in our topology.
        learnContact(peer, msg.senderPublicKey)
    }

    private suspend fun handleIncomingChannel(msg: ReceivedMessage) {
        if (!processed.add(msg.datagramId.toHex())) return
        val channelPayload = msg.channelPayload ?: return
        val hashByte = ChatChannel.payloadChannelHash(channelPayload) ?: return
        // Try every joined channel whose hash matches; the MAC tells us which one decrypts.
        for (ch in dao.channelsByHash(hashByte)) {
            val decoded = ChatChannel.decodePayload(ch.pskHex.hexToBytes(), channelPayload) ?: continue
            dao.insertMessage(
                Message(
                    id = msg.datagramId.toHex().ifEmpty { newId() },
                    peerHex = channelPeerId(ch.pskHex),
                    senderHex = msg.fromNodeId.toHex(),
                    senderName = decoded.senderLabel,
                    incoming = true,
                    text = decoded.text,
                    timestampMs = msg.timestampMs,
                    status = MsgStatus.DELIVERED,
                    routeHex = msg.path.toRouteHex(),
                    read = false,
                )
            )
            return
        }
    }

    /** Saves/updates a contact's public key. Prefers an explicit key, else the topology key. */
    private suspend fun learnContact(nodeHex: String, pub: ByteArray?) {
        val topo = topology.value.firstOrNull { it.nodeId.toHex() == nodeHex }
        val key = pub?.takeIf { it.size == 32 } ?: topo?.publicKey?.takeIf { it.size == 32 } ?: return
        val desc = topo?.description ?: dao.contactByNode(nodeHex)?.description ?: ""
        dao.upsertContact(Contact(nodeHex, key.toHex(), desc))
    }

    // ---- actions -------------------------------------------------------------

    /** Ensures a contact row exists for [node] before opening its conversation. */
    fun startChat(node: AdvertisedNode) {
        viewModelScope.launch {
            dao.upsertContact(Contact(node.nodeHex, node.pubKeyHex, node.description))
        }
    }

    /** Live profile (name, public key, contact state) for the profile page. */
    fun profileFor(peerHex: String): StateFlow<ProfileInfo> =
        combine(contacts, topology, channels) { c, t, chans ->
            if (isChannelPeer(peerHex)) {
                val psk = channelPskHexOf(peerHex)
                val ch = chans.firstOrNull { it.pskHex == psk }
                ProfileInfo(
                    peerHex = peerHex,
                    isChannel = true,
                    name = ch?.name ?: "Channel",
                    channelKind = ch?.kind ?: "",
                    channelHash = ch?.hashByte ?: 0,
                    pskHex = psk,
                )
            } else if (peerHex == nodeId.value.toHex()) {
                // Our own profile (opened from the avatar): use our local identity/settings.
                ProfileInfo(
                    peerHex = peerHex,
                    isChannel = false,
                    name = myName.value.ifBlank { shortHex(peerHex) },
                    nodeHex = peerHex,
                    pubKeyHex = myPubKeyHex.value,
                    isContact = false,
                    online = true,
                    description = _description.value,
                    platform = _service.value?.defaultPlatform() ?: "",
                )
            } else {
                val byNode = c.associateBy { it.nodeHex }
                ProfileInfo(
                    peerHex = peerHex,
                    isChannel = false,
                    name = displayName(peerHex, byNode, t),
                    nodeHex = peerHex,
                    pubKeyHex = publicKeyHex(peerHex, byNode, t),
                    isContact = byNode[peerHex] != null,
                    online = t.any { it.nodeId.toHex() == peerHex },
                    description = nodeDescription(peerHex, byNode, t),
                    platform = nodePlatform(peerHex, t),
                    neighborHexes = t.firstOrNull { it.nodeId.toHex() == peerHex }
                        ?.neighborIds?.map { it.toHex() } ?: emptyList(),
                )
            }
        }.stateIn(
            viewModelScope, SharingStarted.Eagerly,
            ProfileInfo(peerHex, isChannelPeer(peerHex), name = shortHex(peerHex)),
        )

    /** Renames a contact, preserving its public key (creating the row if needed). */
    fun renameContact(nodeHex: String, newName: String) {
        viewModelScope.launch {
            val pub = dao.contactByNode(nodeHex)?.pubKeyHex
                ?: topology.value.firstOrNull { it.nodeId.toHex() == nodeHex }
                    ?.publicKey?.takeIf { it.size == 32 }?.toHex()
                ?: ""
            dao.upsertContact(Contact(nodeHex, pub, newName.trim()))
        }
    }

    /** Removes a node from saved contacts (its messages and topology entry stay). */
    fun deleteContact(nodeHex: String) {
        viewModelScope.launch { dao.deleteContact(nodeHex) }
    }

    /** Renames a joined channel (keeps its PSK / hash). */
    fun renameChannel(pskHex: String, newName: String) {
        viewModelScope.launch {
            dao.channelByPsk(pskHex)?.let { dao.upsertChannel(it.copy(name = newName.trim())) }
        }
    }

    /**
     * Signals that the local user is actively typing in the DM with [peerHex]. Starts a loop
     * that emits a typing hint immediately and re-sends every 10s until [stopTyping]. No-op for
     * channels (broadcast typing would just spam the mesh).
     */
    fun onUserTyping(peerHex: String) {
        if (isChannelPeer(peerHex)) return
        if (outgoingTypingPeer == peerHex && outgoingTypingJob?.isActive == true) return
        outgoingTypingJob?.cancel()
        outgoingTypingPeer = peerHex
        outgoingTypingJob = viewModelScope.launch {
            val dst = NodeId.fromHex(peerHex)
            while (isActive) {
                _service.value?.sendTyping(dst)
                delay(10_000)
            }
        }
    }

    /** Stops re-sending typing hints for [peerHex] (user cleared the field, sent, or left). */
    fun stopTyping(peerHex: String) {
        if (outgoingTypingPeer == peerHex) {
            outgoingTypingJob?.cancel()
            outgoingTypingJob = null
            outgoingTypingPeer = null
        }
    }

    /** Sends an encrypted direct message to a node conversation. */
    fun sendChat(peerHex: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val service = _service.value ?: return
        stopTyping(peerHex)
        viewModelScope.launch {
            val dst = NodeId.fromHex(peerHex)
            // Resolve the recipient's key from the saved contact (learned from a received DM
            // or the picker), falling back to topology inside the service.
            val pub = dao.contactByNode(peerHex)?.pubKeyHex?.takeIf { it.length == 64 }?.hexToBytes()
            val id = service.sendChat(body, dst, pub)
            dao.insertMessage(
                Message(
                    id = id?.toHex() ?: newId(),
                    peerHex = peerHex,
                    senderHex = nodeId.value.toHex(),
                    incoming = false,
                    text = body,
                    timestampMs = System.currentTimeMillis(),
                    status = if (id == null) MsgStatus.FAILED else MsgStatus.SENT,
                )
            )
        }
    }

    /** Sends a message to a channel (MeshCore GRP_TXT, broadcast to the mesh). */
    fun sendChannelMessage(pskHex: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val service = _service.value ?: return
        viewModelScope.launch {
            // The channel sender label is our display name (deterministic name or override),
            // matching what everyone shows for us elsewhere — not the free-form description.
            val myLabel = myName.value.ifBlank { shortHex(nodeId.value.toHex()) }
            val id = service.sendChannel(pskHex.hexToBytes(), myLabel, body)
            dao.insertMessage(
                Message(
                    id = id.toHex(),
                    peerHex = channelPeerId(pskHex),
                    senderHex = nodeId.value.toHex(),
                    senderName = myLabel,
                    incoming = false,
                    text = body,
                    timestampMs = System.currentTimeMillis(),
                    status = MsgStatus.SENT, // broadcast — no per-message ACK
                )
            )
        }
    }

    // ---- channel management --------------------------------------------------

    fun joinPublic() = joinChannelPsk(ChatChannel.PUBLIC_SECRET, "Public", ChannelKind.PUBLIC)

    fun joinNamedChannel(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        joinChannelPsk(ChatChannel.namedSecret(n), n, ChannelKind.NAMED)
    }

    fun joinSecretChannel(name: String, secret: String) {
        val n = name.trim().ifBlank { "Secret" }
        val s = secret.trim()
        if (s.isEmpty()) return
        // A 32-hex-char secret is taken as the raw 16-byte PSK; anything else is hashed.
        val psk = if (s.length == 32 && s.all { it.lowercaseChar() in "0123456789abcdef" }) s.hexToBytes()
        else ChatChannel.namedSecret(s)
        joinChannelPsk(psk, n, ChannelKind.SECRET)
    }

    fun leaveChannel(pskHex: String) {
        viewModelScope.launch { dao.deleteChannel(pskHex) }
    }

    // ---- MeshCore deep links (meshcore://… opened from a QR / link) ----------

    /** A conversation/channel the UI should open in response to a deep link, or null. */
    private val _pendingOpenPeer = MutableStateFlow<String?>(null)
    val pendingOpenPeer: StateFlow<String?> = _pendingOpenPeer.asStateFlow()

    fun consumePendingOpen() { _pendingOpenPeer.value = null }

    /**
     * Handles a scanned/clicked `meshcore://` link: joins the channel or adds the contact, then
     * asks the UI to open it. Returns true if the URI was a recognised MeshCore link.
     */
    fun handleSharedUri(uri: String): Boolean {
        MeshCoreUri.parseChannel(uri)?.let { ch ->
            joinSecretChannel(ch.name, ch.secretHex)
            _pendingOpenPeer.value = channelPeerId(ch.secretHex.lowercase())
            return true
        }
        MeshCoreUri.parseContact(uri)?.let { c ->
            val nodeHex = c.publicKeyHex.take(BLEEdge.NODE_ID_BYTES * 2)
            viewModelScope.launch { dao.upsertContact(Contact(nodeHex, c.publicKeyHex, c.name)) }
            _pendingOpenPeer.value = nodeHex
            return true
        }
        return false
    }

    private fun joinChannelPsk(psk: ByteArray, name: String, kind: String) {
        viewModelScope.launch {
            dao.upsertChannel(
                Channel(
                    pskHex = psk.toHex(),
                    name = name,
                    hashByte = ChatChannel.channelHash(psk).toInt() and 0xFF,
                    kind = kind,
                )
            )
        }
    }

    fun markRead(peerHex: String) {
        viewModelScope.launch { dao.markRead(peerHex) }
    }

    /** Deletes a direct conversation's message history (removes it from the chat list). */
    fun deleteChat(peerHex: String) {
        viewModelScope.launch { dao.deleteMessagesFor(peerHex) }
    }

    fun setDescription(text: String) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[DESC_KEY] = text }
            _description.value = text
            _service.value?.setDescription(text)
        }
    }

    /** Updates DM delivery retry settings (wait time per try and total tries). */
    fun setDmRetry(retryDelayMs: Int, maxTries: Int) {
        val delay = retryDelayMs.coerceAtLeast(500)
        val tries = maxTries.coerceIn(1, 10)
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit {
                it[DM_RETRY_DELAY_KEY] = delay
                it[DM_MAX_TRIES_KEY] = tries
            }
            _dmRetryDelayMs.value = delay
            _dmMaxTries.value = tries
            _service.value?.setDmRetry(delay.toLong(), tries)
        }
    }

    /** Sets the user's display-name override (blank resets to the deterministic default). */
    fun setName(text: String) {
        val clean = text.trim()
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[NAME_KEY] = clean }
            _name.value = clean
            _service.value?.setName(clean)
        }
    }

    fun setPhyMode(mode: PHYMode) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[PHY_MODE_KEY] = mode.value }
            _service.value?.setPhyMode(mode)
        }
    }

    /** Replaces the identity seed (64 hex chars) and restarts the mesh node. */
    fun applySeed(hex: String): Boolean {
        val clean = hex.trim().lowercase()
        if (clean.length != BLEEdge.SEED_BYTES * 2 || !clean.all { it in "0123456789abcdef" }) return false
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[SEED_KEY] = clean }
            _seedHex.value = clean
            processed.clear()
            restartService()
        }
        return true
    }

    fun regenerateSeed() {
        applySeed(generateSeedHex())
    }

    private fun restartService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, BLEEdgeService::class.java)
        if (serviceBound) {
            runCatching { ctx.unbindService(serviceConnection) }
            serviceBound = false
        }
        ctx.stopService(intent)
        _service.value = null
        bindService()
    }

    override fun onCleared() {
        super.onCleared()
        if (serviceBound) {
            runCatching { getApplication<Application>().unbindService(serviceConnection) }
            serviceBound = false
        }
    }

    /** Resolves a node id (hex) to its best-known display name, for route detail rendering. */
    fun nameForHex(hex: String): String =
        displayName(hex, contacts.value.associateBy { it.nodeHex }, topology.value)

    /** This node's own NodeId as hex — used to exclude self from route pickers. */
    fun myNodeHex(): String = nodeId.value.toHex()

    // ---- helpers -------------------------------------------------------------

    /**
     * The primary label for a node: a deterministic name derived from its public key (e.g.
     * "barrel-two-return"), so the same identity reads the same everywhere. Falls back to a short
     * id until the public key is known. Channels keep their joined name. The node's own free-form
     * description is NOT used here — it's shown only on the profile page (see [nodeDescription]).
     */
    private fun displayName(peerHex: String, contacts: Map<String, Contact>, topo: List<TopologyEntry>): String {
        if (isChannelPeer(peerHex)) {
            return channels.value.firstOrNull { it.pskHex == channelPskHexOf(peerHex) }?.name ?: "Channel"
        }
        // Prefer the node's real name carried on the wire (ANNOUNCE/NODE_INFO); if it hasn't sent
        // one (e.g. ESP32, or not yet learned), derive the deterministic default from its pubkey.
        topo.firstOrNull { it.nodeId.toHex() == peerHex }?.name?.takeIf { it.isNotBlank() }?.let { return it }
        val derived = nameFromPubKey(publicKeyHex(peerHex, contacts, topo))
        return derived.ifBlank { shortHex(peerHex) }
    }

    /** The node's OS/device string (from its ANNOUNCE / NODE_INFO), or "". */
    private fun nodePlatform(peerHex: String, topo: List<TopologyEntry>): String =
        topo.firstOrNull { it.nodeId.toHex() == peerHex }?.platform?.takeIf { it.isNotBlank() } ?: ""

    /** The node's own free-form description (from its ANNOUNCE / NODE_INFO), or "" — profile only. */
    private fun nodeDescription(peerHex: String, contacts: Map<String, Contact>, topo: List<TopologyEntry>): String {
        contacts[peerHex]?.description?.takeIf { it.isNotBlank() }?.let { return it }
        return topo.firstOrNull { it.nodeId.toHex() == peerHex }?.description?.takeIf { it.isNotBlank() } ?: ""
    }

    /** The node's 32-byte Ed25519 key (hex), from a saved contact or the live topology, or "". */
    private fun publicKeyHex(peerHex: String, contacts: Map<String, Contact>, topo: List<TopologyEntry>): String {
        contacts[peerHex]?.pubKeyHex?.takeIf { it.length == 64 }?.let { return it }
        topo.firstOrNull { it.nodeId.toHex() == peerHex }?.publicKey
            ?.takeIf { it.size == 32 }?.let { return it.toHex() }
        return ""
    }

    private fun generateSeedHex(): String =
        ByteArray(BLEEdge.SEED_BYTES).also { SecureRandom().nextBytes(it) }.toHex()

    private fun newId(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
}

// ---- byte/hex utilities -----------------------------------------------------

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
fun shortHex(hex: String): String = if (hex.length >= 8) "node ${hex.take(8)}" else hex
private fun List<NodeId>.toRouteHex(): String = joinToString(",") { it.toHex() }
