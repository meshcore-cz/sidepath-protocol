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
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.arnal.bleedge.chat.data.CHANNEL_PEER
import cz.arnal.bleedge.chat.data.ChatDatabase
import cz.arnal.bleedge.chat.data.Contact
import cz.arnal.bleedge.chat.data.Message
import cz.arnal.bleedge.chat.data.MsgStatus
import cz.arnal.bleedge.core.Crypto
import cz.arnal.bleedge.core.Identity
import cz.arnal.bleedge.core.NodeID
import cz.arnal.bleedge.core.PHYMode
import cz.arnal.bleedge.core.PayloadType
import cz.arnal.bleedge.core.SEED_SIZE
import cz.arnal.bleedge.service.BLEEdgeService
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.SecureRandom

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bleedge_chat_prefs")
private val SEED_KEY = stringPreferencesKey("seed")
private val DESC_KEY = stringPreferencesKey("description")
private val PHY_MODE_KEY = stringPreferencesKey("phy_mode")

/** A row in the Chats list. */
data class ConversationSummary(
    val peerHex: String,
    val title: String,
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

    private val _description = MutableStateFlow("")
    val description: StateFlow<String> = _description.asStateFlow()

    val nodeId: StateFlow<NodeID> = _service.flatMapLatest {
        it?.nodeId ?: flowOf(NodeID(ByteArray(8)))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NodeID(ByteArray(8)))

    val phyMode: StateFlow<PHYMode> = _service.flatMapLatest {
        it?.phyMode ?: flowOf(PHYMode.DEBUG_1M)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PHYMode.DEBUG_1M)

    val topology: StateFlow<List<TopologyEntry>> = _service.flatMapLatest {
        it?.knownTopology ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val connectedPeers: StateFlow<List<PeerInfo>> = _service.flatMapLatest {
        it?.connectedPeers ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isRunning: StateFlow<Boolean> = _service.flatMapLatest {
        it?.isRunning ?: flowOf(false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    /** Conversations for the Chats tab (excludes the public channel). */
    val conversations: StateFlow<List<ConversationSummary>> =
        combine(dao.allMessages(), contacts, topology) { msgs, contacts, topo ->
            val byNode = contacts.associateBy { it.nodeHex }
            msgs.filter { it.peerHex != CHANNEL_PEER }
                .groupBy { it.peerHex }
                .map { (peer, list) ->
                    val last = list.maxByOrNull { it.timestampMs }!!
                    ConversationSummary(
                        peerHex = peer,
                        title = displayName(peer, byNode, topo),
                        lastText = last.text,
                        lastTimestampMs = last.timestampMs,
                        unread = list.count { it.incoming && !it.read },
                    )
                }
                .sortedByDescending { it.lastTimestampMs }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Every node we can start an encrypted chat with — i.e. whose public key we know,
     * whether from the live topology (signed ANNOUNCE) or a saved contact (learned from a
     * received DM or a previous chat).
     */
    val advertisedNodes: StateFlow<List<AdvertisedNode>> =
        combine(topology, contacts, nodeId) { topo, contacts, me ->
            val meHex = me.toHexString()
            val byHex = LinkedHashMap<String, AdvertisedNode>()
            contacts.forEach { c ->
                if (c.nodeHex != meHex && c.pubKeyHex.length == 64) {
                    byHex[c.nodeHex] = AdvertisedNode(c.nodeHex, c.description, c.pubKeyHex)
                }
            }
            topo.forEach { t ->
                val hex = t.nodeId.toHexString()
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
        val phy = PHYMode.fromString(pref[PHY_MODE_KEY] ?: PHYMode.DEBUG_1M.value)
        _seedHex.value = seedHex
        _description.value = desc

        val identity = Identity.fromSeed(seedHex.hexToBytes())
        service.initialize(identity, phy, emptySet(), desc)
    }

    private fun observeIncoming(service: BLEEdgeService) {
        viewModelScope.launch {
            service.receivedMessages.collect { list ->
                list.forEach { handleIncoming(it) }
            }
        }
    }

    private suspend fun handleIncoming(msg: ReceivedMessage) {
        if (msg.isAck) {
            val acked = msg.ackedId ?: return
            val key = "ack:" + acked.toHex()
            if (!processed.add(key)) return
            dao.updateDelivery(acked.toHex(), MsgStatus.DELIVERED, msg.trace.toRouteHex())
            return
        }
        if (msg.payloadType != PayloadType.CHAT_PLAIN && msg.payloadType != PayloadType.CHAT_ENCRYPTED) return
        val key = msg.packetId.toHex()
        if (!processed.add(key)) return

        val isChannel = msg.payloadType == PayloadType.CHAT_PLAIN
        val peer = if (isChannel) CHANNEL_PEER else msg.fromNodeId.toHexString()
        val text = msg.text ?: if (msg.payloadType == PayloadType.CHAT_ENCRYPTED) "[unable to decrypt]" else ""

        dao.insertMessage(
            Message(
                id = msg.packetId.toHex().ifEmpty { newId() },
                peerHex = peer,
                senderHex = msg.fromNodeId.toHexString(),
                incoming = true,
                text = text,
                timestampMs = msg.timestampMs,
                status = MsgStatus.DELIVERED,
                routeHex = msg.trace.toRouteHex(),
                read = false,
            )
        )
        if (!isChannel) {
            // Learn the sender's public key from the DM envelope itself, so we can reply
            // even if the node isn't (yet) in our topology.
            val envPub = Crypto.envelopeSenderPubKey(msg.payload)
            learnContact(msg.fromNodeId.toHexString(), envPub)
        }
    }

    /** Saves/updates a contact's public key. Prefers an explicit key, else the topology key. */
    private suspend fun learnContact(nodeHex: String, pub: ByteArray?) {
        val topo = topology.value.firstOrNull { it.nodeId.toHexString() == nodeHex }
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

    fun sendChat(peerHex: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val service = _service.value ?: return
        viewModelScope.launch {
            val dst = if (peerHex == CHANNEL_PEER) NodeID.BROADCAST else NodeID.fromHex(peerHex)
            // Resolve the recipient's key from the saved contact (learned from a received DM
            // or the picker), falling back to topology inside the service.
            val pub = if (peerHex == CHANNEL_PEER) null
            else dao.contactByNode(peerHex)?.pubKeyHex?.takeIf { it.length == 64 }?.hexToBytes()
            val id = service.sendChat(body, dst, pub)
            dao.insertMessage(
                Message(
                    id = id?.toHex() ?: newId(),
                    peerHex = peerHex,
                    senderHex = nodeId.value.toHexString(),
                    incoming = false,
                    text = body,
                    timestampMs = System.currentTimeMillis(),
                    status = if (id == null) MsgStatus.FAILED else MsgStatus.SENT,
                )
            )
        }
    }

    fun markRead(peerHex: String) {
        viewModelScope.launch { dao.markRead(peerHex) }
    }

    fun setDescription(text: String) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[DESC_KEY] = text }
            _description.value = text
            _service.value?.setDescription(text)
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
        if (clean.length != SEED_SIZE * 2 || !clean.all { it in "0123456789abcdef" }) return false
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

    // ---- helpers -------------------------------------------------------------

    private fun displayName(peerHex: String, contacts: Map<String, Contact>, topo: List<TopologyEntry>): String {
        if (peerHex == CHANNEL_PEER) return "Channel"
        contacts[peerHex]?.description?.takeIf { it.isNotBlank() }?.let { return it }
        topo.firstOrNull { it.nodeId.toHexString() == peerHex }?.description
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return shortHex(peerHex)
    }

    private fun generateSeedHex(): String =
        ByteArray(SEED_SIZE).also { SecureRandom().nextBytes(it) }.toHex()

    private fun newId(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
}

// ---- byte/hex utilities -----------------------------------------------------

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
fun shortHex(hex: String): String = if (hex.length >= 8) "node ${hex.take(8)}" else hex
private fun List<NodeID>.toRouteHex(): String = joinToString(",") { it.toHexString() }
