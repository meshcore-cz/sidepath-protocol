package cz.arnal.bleedge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import cz.arnal.bleedge.ble.BLEEdgeAdvertiser
import cz.arnal.bleedge.ble.BLEEdgeGattClient
import cz.arnal.bleedge.ble.BLEEdgeGattServer
import cz.arnal.bleedge.ble.BLEEdgeScanner
import cz.arnal.bleedge.ble.BLEManager
import cz.arnal.bleedge.ble.BLEPeerLink
import cz.arnal.bleedge.chatproto.Chat
import cz.arnal.bleedge.chatproto.ChatChannel
import cz.arnal.bleedge.chatproto.ChatChannelReaction
import cz.arnal.bleedge.chatproto.ChatContext
import cz.arnal.bleedge.chatproto.ChatDirectReaction
import cz.arnal.bleedge.chatproto.ChatDirectText
import cz.arnal.bleedge.chatproto.ChatKind
import cz.arnal.bleedge.chatproto.ChatPublicText
import cz.arnal.bleedge.chatproto.ChatTyping
import cz.arnal.bleedge.meshcore.MeshCoreAdvert
import cz.arnal.bleedge.meshcore.MeshCoreCodec
import cz.arnal.bleedge.meshcore.MeshCorePacket
import cz.arnal.bleedge.meshcore.MeshCoreType
import cz.arnal.bleedge.protocol.AckBody
import cz.arnal.bleedge.protocol.BridgedBody
import cz.arnal.bleedge.protocol.Action
import cz.arnal.bleedge.protocol.ActionType
import cz.arnal.bleedge.protocol.AnnounceBody
import cz.arnal.bleedge.protocol.BLEEdge
import cz.arnal.bleedge.protocol.Capabilities
import cz.arnal.bleedge.protocol.ControlKind
import cz.arnal.bleedge.protocol.ControlMessage
import cz.arnal.bleedge.protocol.Datagram
import cz.arnal.bleedge.protocol.DatagramFlags
import cz.arnal.bleedge.protocol.DropReason
import cz.arnal.bleedge.protocol.Frame
import cz.arnal.bleedge.protocol.Identity
import cz.arnal.bleedge.protocol.NeighborEntry as ProtoNeighbor
import cz.arnal.bleedge.protocol.NodeId
import cz.arnal.bleedge.protocol.PayloadProtocol
import cz.arnal.bleedge.protocol.Reassembler
import cz.arnal.bleedge.protocol.Router
import cz.arnal.bleedge.protocol.TopoNode
import cz.arnal.bleedge.protocol.TraceMetric
import cz.arnal.bleedge.protocol.TraceRequestBody
import cz.arnal.bleedge.protocol.TraceResponseBody
import cz.arnal.bleedge.protocol.defaultNodeName
import cz.arnal.bleedge.transport.ANDROID_CAPABILITIES
import cz.arnal.bleedge.transport.PHY
import cz.arnal.bleedge.transport.PHYMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

private const val TAG = "BLEEdgeService"
private const val NOTIFICATION_CHANNEL = "bleedge"
private const val NOTIFICATION_ID = 1
private const val EPOCH_PREFS = "bleedge_node"
private const val EPOCH_KEY = "announce_epoch"
private const val MAX_OUTGOING_LINKS = 4
private const val MAX_CONNECTING_LINKS = 1
private const val CONNECT_TIMEOUT_MS = 20_000L
private const val CONNECTION_CANDIDATE_TTL_MS = 45_000L
private const val RECONNECT_BASE_DELAY_MS = 5_000L
private const val RECONNECT_MAX_DELAY_MS = 90_000L

/** Sentinel for "RSSI not measurable" (e.g. inbound GATT-server peers). */
const val RSSI_UNKNOWN = Int.MIN_VALUE

// ---- UI data models ---------------------------------------------------------

data class PeerInfo(
    val nodeId: NodeId,
    val rssi: Int,
    val txPhy: PHY,
    val rxPhy: PHY,
    val caps: Capabilities,
    val incoming: Boolean = false,
    val degraded: Boolean = false,
    val name: String = "",
    val publicKey: ByteArray = ByteArray(0),
    val connectedSinceMs: Long = 0L, // when this peer first appeared connected (for an uptime label)
)

/**
 * A locally delivered datagram surfaced to the UI. For BLEEDGE_CHAT payloads the
 * service has already verified/decrypted what it can: DIRECT_TEXT and PUBLIC_TEXT
 * carry [text]; CHANNEL_TEXT carries [channelPayload] for the app to decode with
 * its joined channel secrets; TYPING sets [isTyping].
 */
data class ReceivedMessage(
    val fromNodeId: NodeId,
    val datagramId: ByteArray,
    val protocol: Int,
    val chatKind: Int? = null,
    val text: String? = null,
    val senderPublicKey: ByteArray? = null,
    val isAck: Boolean = false,
    val ackedId: ByteArray? = null,
    val isTyping: Boolean = false,
    val channelPayload: ByteArray? = null,
    // Emoji reactions. DIRECT_REACTION is decrypted by the service ([reactionTargetRef]/[reactionEmoji]/
    // [reactionRemove] set); CHANNEL_REACTION is passed raw in [channelReactionPayload] for the app to
    // decode with its joined channel secrets.
    val reactionTargetRef: String? = null,
    val reactionEmoji: String? = null,
    val reactionRemove: Boolean = false,
    val channelReactionPayload: ByteArray? = null,
    val traceResponse: TraceResponseBody? = null,
    // Set on an inbound ACK_BRIDGED (ControlKind.BRIDGED): a gateway relayed the channel datagram
    // [bridgedDatagramId] onto an external network (MeshCore). [bridgedByNodeId] is the gateway.
    val bridgedDatagramId: ByteArray? = null,
    val bridgedByNodeId: NodeId? = null,
    val path: List<NodeId> = emptyList(),
    val fromMeshCore: Boolean = false,
    // MeshCore carrier metadata (set when fromMeshCore), surfaced in message details.
    val meshCoreType: String? = null,
    val meshCoreRoute: String? = null,
    val meshCoreHops: Int = 0,
    val meshCorePacketId: String? = null,
    val sentAtMs: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis(),
)

/** A decoded datagram captured for the Rx Log. */
data class RxPacket(
    val timestampMs: Long,
    val protocol: Int,
    val chatKind: Int?,       // for BLEEDGE_CHAT: the chat message kind (ChatKind.*)
    val controlKind: Int?,    // for BLEEDGE_CONTROL: the control message kind (ControlKind.*)
    val id: ByteArray,
    val source: NodeId,
    val destination: NodeId,
    val sourceRouted: Boolean,
    val routeCursor: Int,
    val ttl: Int,
    val flags: Int,
    val ackRequested: Boolean,
    val path: List<NodeId>,
    val route: List<NodeId>,
    val payloadSize: Int,
    val raw: ByteArray,
    val forUs: Boolean,
    // RSSI of the direct link that delivered this packet, captured at receipt (BLE gives no
    // per-notification RSSI, so this is the connection's last-read value frozen for this row).
    val rssi: Int = RSSI_UNKNOWN,
    val droppedReason: String? = null, // non-null if the router dropped it (e.g. "duplicate", "loop")
)

data class RepeatSample(
    val timestampMs: Long,
    val rssi: Int,
    val forwarderId: NodeId? = null,
    // True when this echo of our own channel message came back via the MeshCore bridge (the message
    // we bridged out was re-flooded onto MeshCore and returned), rather than a native BLEEdge relay.
    val viaMeshCore: Boolean = false,
    // The raw received datagram bytes for this echo, so it can be opened as a packet detail.
    val raw: ByteArray? = null,
)

data class DmDelivery(
    val attemptsSent: Int,
    val maxTries: Int,
    val acked: Boolean = false,
    val failed: Boolean = false,
)

enum class LogTag { SYS, SCAN, PEER, SERVER, GATT, PHY, ROUTER, MSG }

data class LogEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val tag: LogTag = LogTag.SYS,
    val message: String,
)

data class NeighborEntry(
    val nodeId: NodeId,
    val rssi: Int,
    val caps: Capabilities,
    val name: String = "",
)

data class TopologyEntry(
    val nodeId: NodeId,
    val caps: Capabilities,
    val neighborIds: List<NodeId>,
    val lastAnnounceMs: Long = 0L,
    val name: String = "",
    val description: String = "",
    val platform: String = "",
    val publicKey: ByteArray = ByteArray(0),
)

data class MeshStats(
    val packetsReceived: Int = 0,
    val packetsSent: Int = 0,
    val floodRelays: Int = 0,
    val acksSent: Int = 0,
    val tracesSent: Int = 0,
    val duplicatesDropped: Int = 0,
)

/**
 * Time- and size-bounded "have I seen this content before?" set, keyed by a content id. Used to
 * process each unique MeshCore packet once regardless of how many BLEEdge paths re-deliver it.
 */
private class ContentDedupCache(
    private val ttlMs: Long = BLEEdge.DEDUP_TTL_MS,
    private val maxEntries: Int = BLEEdge.DEDUP_LIMIT,
) {
    private val seen = LinkedHashMap<String, Long>()

    /** Returns true only the first time [key] is seen within the TTL window. */
    @Synchronized fun firstSight(key: String): Boolean {
        val now = System.currentTimeMillis()
        val it = seen.entries.iterator()
        while (it.hasNext()) { if (now - it.next().value > ttlMs) it.remove() else break }
        seen[key]?.let { if (now - it <= ttlMs) return false }
        seen[key] = now
        while (seen.size > maxEntries) {
            val k = seen.keys.iterator()
            if (!k.hasNext()) break
            k.next(); k.remove()
        }
        return true
    }
}

// ---- Service ----------------------------------------------------------------

class BLEEdgeService : Service() {
    inner class LocalBinder : Binder() {
        fun getService(): BLEEdgeService = this@BLEEdgeService
    }
    private val binder = LocalBinder()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var router: Router
    private lateinit var reassembler: Reassembler
    private lateinit var bleManager: BLEManager
    private var bleGattServer: BLEEdgeGattServer? = null

    // Outgoing connections (we are GATT client): BLE MAC hex -> link.
    private val peers = ConcurrentHashMap<String, BLEPeerLink>()

    // First time each peer (by node-id hex) was seen connected, for the "connected for N" label.
    // Entries are pruned in updatePeersState once a peer is no longer present.
    private val connectedSince = ConcurrentHashMap<String, Long>()
    // Incoming connections (peer is client to our server): BLE MAC hex -> direct peer NodeId (or null).
    private val serverPeers = ConcurrentHashMap<String, NodeId?>()
    private val serverPeerDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private data class ConnectionCandidate(
        val device: BluetoothDevice,
        val rssi: Int,
        val advertisedNodeId: NodeId?,
        val lastSeenMs: Long = System.currentTimeMillis(),
    )
    private val connectionLock = Any()
    private val connectionCandidates = LinkedHashMap<String, ConnectionCandidate>()
    private val connectingPeers = ConcurrentHashMap<String, Long>()
    private val reconnectAfterMs = ConcurrentHashMap<String, Long>()
    private val connectionFailures = ConcurrentHashMap<String, Int>()

    private var identity: Identity? = null
    private var epoch: Long = 0
    private var announceSeq: Long = 0

    private var bleAdvertiser: BLEEdgeAdvertiser? = null
    private var bleScanner: BLEEdgeScanner? = null

    private var nodeName: String = ""
    private var nodeDescription: String = ""
    private var nodePlatform: String = ""

    private val _nodeId = MutableStateFlow(NodeId.BROADCAST)
    val nodeId: StateFlow<NodeId> = _nodeId.asStateFlow()

    private val _rxPackets = MutableStateFlow<List<RxPacket>>(emptyList())
    val rxPackets: StateFlow<List<RxPacket>> = _rxPackets.asStateFlow()
    private val maxRxPackets = 300
    // Cumulative count of all packets received (the rxPackets list is trimmed to maxRxPackets).
    private val _rxTotal = MutableStateFlow(0)
    val rxTotal: StateFlow<Int> = _rxTotal.asStateFlow()

    // ---- MeshCore subsystem ----
    private val _meshCorePackets = MutableStateFlow<List<MeshCorePacket>>(emptyList())
    val meshCorePackets: StateFlow<List<MeshCorePacket>> = _meshCorePackets.asStateFlow()
    private val _meshCoreTotal = MutableStateFlow(0)
    val meshCoreTotal: StateFlow<Int> = _meshCoreTotal.asStateFlow()
    // Decoded MeshCore ADVERTs (node advertisements) seen on the mesh — consumed by the chat-app
    // to populate its discovered-contacts table.
    private val _meshCoreAdverts = MutableStateFlow<List<MeshCoreAdvert>>(emptyList())
    val meshCoreAdverts: StateFlow<List<MeshCoreAdvert>> = _meshCoreAdverts.asStateFlow()
    // Joined-channel PSKs (16-byte secrets), pushed in by the chat-app, used only to
    // render decrypted GRP_TXT plaintext in the MeshCore Rx Log.
    @Volatile private var meshCoreChannelSecrets: List<ByteArray> = emptyList()
    // Content-level dedup for MeshCore side effects: the same logical MeshCore packet reaches us
    // via multiple LoRa paths / BLEEdge relays (each a distinct datagram), so we key on the stable
    // inner payload to ingest chat/discovery once while still counting every RX log sighting.
    private val meshCoreContentSeen = ContentDedupCache()

    private val originatedFloodIds = ConcurrentHashMap<String, Long>()
    private val _floodRepeats = MutableStateFlow<Map<String, List<RepeatSample>>>(emptyMap())
    val floodRepeats: StateFlow<Map<String, List<RepeatSample>>> = _floodRepeats.asStateFlow()

    // channel_payload hex -> originating channel datagram id hex. Lets us recognise a MeshCore
    // GRP_TXT that is an echo of a channel message we bridged out (same deterministic payload),
    // so we fold it into that message's flood-repeats instead of showing a duplicate inbound row.
    private val originatedChannelCp = ConcurrentHashMap<String, String>()

    // datagram id hex -> raw outgoing datagram bytes (hex), so the chat app can persist & show
    // "Packet details" for a message we sent. Bounded.
    private val originatedRaw = ConcurrentHashMap<String, String>()

    /** Raw bytes (hex) of a datagram we originated, if still cached — for the outgoing packet detail. */
    fun originatedPacketHex(idHex: String): String? = originatedRaw[idHex]

    private val _bleMacAddress = MutableStateFlow("")
    val bleMacAddress: StateFlow<String> = _bleMacAddress.asStateFlow()

    private val _phyMode = MutableStateFlow(PHYMode.ONE_M)
    val phyMode: StateFlow<PHYMode> = _phyMode.asStateFlow()

    private val _codedPhySupported = MutableStateFlow(false)
    val codedPhySupported: StateFlow<Boolean> = _codedPhySupported.asStateFlow()

    private val _phyFallback = MutableStateFlow(false)
    val phyFallback: StateFlow<Boolean> = _phyFallback.asStateFlow()

    private val _advertisingActive = MutableStateFlow(false)
    val advertisingActive: StateFlow<Boolean> = _advertisingActive.asStateFlow()

    private val _scanningActive = MutableStateFlow(false)
    val scanningActive: StateFlow<Boolean> = _scanningActive.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val connectedPeers: StateFlow<List<PeerInfo>> = _connectedPeers.asStateFlow()

    // ---- DM delivery retry ----
    @Volatile private var dmRetryDelayMs: Long = 3000
    @Volatile private var dmMaxTries: Int = 3
    private val pendingDms = ConcurrentHashMap<String, PendingDm>()    // key = original id hex
    private val attemptToOriginal = ConcurrentHashMap<String, String>() // attempt id hex -> original id hex
    private val _dmDeliveries = MutableStateFlow<Map<String, DmDelivery>>(emptyMap())
    val dmDeliveries: StateFlow<Map<String, DmDelivery>> = _dmDeliveries.asStateFlow()

    private fun updateDelivery(idHex: String, f: (DmDelivery) -> DmDelivery) {
        _dmDeliveries.update { m -> m[idHex]?.let { m + (idHex to f(it)) } ?: m }
    }

    /** A direct message awaiting ACK. The text/recipient are stored (not the sealed bytes) because
     *  each retry uses a fresh datagram id and the AEAD AAD binds to that id, so it must be re-sealed. */
    private class PendingDm(
        val originalIdHex: String,
        val text: String,
        val recipientPub: ByteArray,
        val dest: NodeId,
        val floodTtl: Int,
        var attemptsSent: Int,
        var job: Job? = null,
    )

    private val _receivedMessages = MutableStateFlow<List<ReceivedMessage>>(emptyList())
    val receivedMessages: StateFlow<List<ReceivedMessage>> = _receivedMessages.asStateFlow()

    private val _routingLog = MutableStateFlow<List<LogEntry>>(emptyList())
    val routingLog: StateFlow<List<LogEntry>> = _routingLog.asStateFlow()

    private val _neighborTable = MutableStateFlow<List<NeighborEntry>>(emptyList())
    val neighborTable: StateFlow<List<NeighborEntry>> = _neighborTable.asStateFlow()

    private val _knownTopology = MutableStateFlow<List<TopologyEntry>>(emptyList())
    val knownTopology: StateFlow<List<TopologyEntry>> = _knownTopology.asStateFlow()

    private val _stats = MutableStateFlow(MeshStats())
    val stats: StateFlow<MeshStats> = _stats.asStateFlow()

    private var allowlist = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("BLEEdge running"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        if (::bleManager.isInitialized) bleManager.stopAll()
        scope.cancel()
        super.onDestroy()
    }

    fun defaultPlatform(): String = "Android ${Build.VERSION.RELEASE} (${Build.MODEL})"

    fun initialize(
        identity: Identity,
        requestedPhyMode: PHYMode,
        allowlist: Set<String>,
        description: String = "",
        name: String = "",
        dmRetryDelayMs: Long = 3000,
        dmMaxTries: Int = 3,
    ) {
        setDmRetry(dmRetryDelayMs, dmMaxTries)
        this.identity = identity
        val localId = identity.nodeId

        peers.clear(); serverPeers.clear(); serverPeerDevices.clear(); resetConnectionManager()
        _connectedPeers.value = emptyList()
        _receivedMessages.value = emptyList()
        _routingLog.value = emptyList()
        _neighborTable.value = emptyList()
        _knownTopology.value = emptyList()

        _nodeId.value = localId
        this.allowlist = allowlist.toMutableSet()

        // §8.5: increment + persist the epoch before the first announce after startup.
        val prefs = getSharedPreferences(EPOCH_PREFS, Context.MODE_PRIVATE)
        epoch = prefs.getLong(EPOCH_KEY, 0) + 1
        prefs.edit().putLong(EPOCH_KEY, epoch).apply()
        announceSeq = 0

        nodeDescription = description
        nodePlatform = defaultPlatform()
        nodeName = name.ifBlank { defaultNodeName(identity.publicKey) }

        router = Router(identity)
        router.allowlist.addAll(allowlist)
        reassembler = Reassembler()

        bleManager = BLEManager(this, requestedPhyMode)
        bleManager.logCapabilities()
        _codedPhySupported.value = bleManager.isLeCodedPhySupported
        _bleMacAddress.value = bleManager.adapter.address ?: ""
        log("BLE caps: codedPhy=${bleManager.isLeCodedPhySupported} extAdv=${bleManager.isLeExtendedAdvertisingSupported} multiAdv=${bleManager.isMultipleAdvertisementSupported}", LogTag.SYS)

        val effectivePhy = if (!bleManager.isLeCodedPhySupported && requestedPhyMode != PHYMode.ONE_M) {
            log("WARNING: LE Coded PHY not supported — falling back to 1m", LogTag.PHY)
            PHYMode.ONE_M
        } else requestedPhyMode
        _phyMode.value = effectivePhy
        _phyFallback.value = effectivePhy != requestedPhyMode

        val gattServer = bleManager.createGattServer(
            pubKey = identity.publicKey,
            caps = ANDROID_CAPABILITIES,
            onFrameReceived = { frame, device -> handleIncomingFrame(frame, device) },
            onDeviceConnected = { device ->
                val addrHex = device.address.replace(":", "")
                serverPeers[addrHex] = null
                serverPeerDevices[addrHex] = device
                log("device connected addr=${device.address}", LogTag.SERVER)
            },
            onDeviceDisconnected = { device ->
                removeServerPeer(device, "disconnected")
            },
            onDeviceUnreachable = { device, reason ->
                removeServerPeer(device, reason)
            },
            onLog = { msg -> log(msg, LogTag.SERVER) },
        )
        gattServer.start()
        bleGattServer = gattServer

        bleAdvertiser = bleManager.createAdvertiser(localId)
        bleAdvertiser!!.startAdvertising(localId, effectivePhy) { msg -> log("advertiser: $msg", LogTag.SYS) }
        _advertisingActive.value = true

        bleScanner = bleManager.createScanner()
        bleScanner!!.startScan(
            phyMode = effectivePhy,
            onFound = { device, rssi, advNodeId -> handleFoundDevice(device, rssi, advNodeId) },
            onFailed = { errorCode -> log("scan FAILED errorCode=$errorCode", LogTag.SCAN) },
        )
        _scanningActive.value = true
        _isRunning.value = true

        scope.launch { while (true) { delay(BLEEdge.ANNOUNCE_INTERVAL_MS); sendAnnounce() } }
        scope.launch { while (true) { delay(2_000); refreshTopologyState() } }

        log("node started id=${localId.toHex()} epoch=$epoch requested-phy=$requestedPhyMode effective-phy=$effectivePhy", LogTag.SYS)
    }

    // ---- discovery / connection ----------------------------------------------

    private fun removeServerPeer(device: BluetoothDevice, reason: String) {
        val addrHex = device.address.replace(":", "")
        val nid = serverPeers.remove(addrHex)
        serverPeerDevices.remove(addrHex)
        nid?.let { router.neighbors.remove(it) }
        log("device $reason addr=${device.address} node=${nid?.toHex() ?: "unknown"}", LogTag.SERVER)
        updatePeersState()
    }

    private fun removePeerLink(addrHex: String, link: BLEPeerLink, reason: String) {
        if (!peers.remove(addrHex, link)) return
        connectingPeers.remove(addrHex)
        link.peerId?.let { router.neighbors.remove(it) }
        link.disconnect()
        log("peer removed reason=$reason addr=$addrHex node=${link.peerId?.toHex() ?: "unknown"}", LogTag.PEER)
        updatePeersState()
        if (_isRunning.value) scheduleConnectionDrain(RECONNECT_BASE_DELAY_MS)
    }

    private fun handleFoundDevice(device: BluetoothDevice, rssi: Int, advNodeId: NodeId?) {
        val peerHex = device.address.replace(":", "")
        val localId = _nodeId.value
        if (advNodeId == localId) return

        val wasNew = synchronized(connectionLock) {
            val previous = connectionCandidates.put(
                peerHex,
                ConnectionCandidate(device = device, rssi = rssi, advertisedNodeId = advNodeId),
            )
            previous == null
        }
        if (wasNew) {
            log(
                if (advNodeId != null) "scan candidate node=${advNodeId.toHex()} addr=${device.address} rssi=$rssi"
                else "scan candidate addr=${device.address} rssi=$rssi (no adv nodeId)",
                LogTag.SCAN,
            )
        }
        scheduleConnectionDrain()
    }

    private fun resetConnectionManager() {
        synchronized(connectionLock) {
            connectionCandidates.clear()
        }
        connectingPeers.clear()
        reconnectAfterMs.clear()
        connectionFailures.clear()
    }

    private fun scheduleConnectionDrain(delayMs: Long = 0) {
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            drainConnectionQueue()
        }
    }

    private fun drainConnectionQueue() {
        if (!_isRunning.value) return
        val now = System.currentTimeMillis()
        val timedOut = mutableListOf<Pair<String, BLEPeerLink>>()
        val toStart = mutableListOf<ConnectionCandidate>()

        synchronized(connectionLock) {
            for ((addrHex, startedAt) in connectingPeers.entries.toList()) {
                if (now - startedAt <= CONNECT_TIMEOUT_MS) continue
                connectingPeers.remove(addrHex)
                peers.remove(addrHex)?.let { timedOut += addrHex to it }
                recordConnectionBackoffLocked(addrHex, now)
            }

            connectionCandidates.entries.removeAll { now - it.value.lastSeenMs > CONNECTION_CANDIDATE_TTL_MS }

            val usableOutgoing = peers.values.count { it.isUsable }
            val slots = minOf(
                MAX_OUTGOING_LINKS - usableOutgoing - connectingPeers.size,
                MAX_CONNECTING_LINKS - connectingPeers.size,
            )
            if (slots > 0) {
                val selected = connectionCandidates.values
                    .filter { isConnectionCandidateEligibleLocked(it, now) }
                    .sortedWith(
                        compareByDescending<ConnectionCandidate> { it.advertisedNodeId != null }
                            .thenByDescending { it.rssi }
                            .thenBy { it.device.address }
                    )
                    .take(slots)
                for (candidate in selected) {
                    val addrHex = candidate.device.address.replace(":", "")
                    connectionCandidates.remove(addrHex)
                    connectingPeers[addrHex] = now
                    toStart += candidate
                }
            }
        }

        for ((addrHex, link) in timedOut) {
            link.disconnect()
            log("connect timeout addr=$addrHex; cooling down", LogTag.PEER)
        }
        for (candidate in toStart) startOutgoingConnection(candidate)
        if (timedOut.isNotEmpty()) scheduleConnectionDrain(RECONNECT_BASE_DELAY_MS)
    }

    private fun isConnectionCandidateEligibleLocked(candidate: ConnectionCandidate, now: Long): Boolean {
        val addrHex = candidate.device.address.replace(":", "")
        if (peers.containsKey(addrHex) || connectingPeers.containsKey(addrHex)) return false
        if ((reconnectAfterMs[addrHex] ?: 0L) > now) return false
        val advNodeId = candidate.advertisedNodeId
        if (advNodeId != null) {
            if (advNodeId == _nodeId.value) return false
            if (peers.values.any { it.peerId == advNodeId }) return false
            if (serverPeers.values.any { it == advNodeId }) return false
        }
        return true
    }

    private fun startOutgoingConnection(candidate: ConnectionCandidate) {
        val device = candidate.device
        val peerHex = device.address.replace(":", "")
        val localId = _nodeId.value
        log(
            if (candidate.advertisedNodeId != null)
                "connecting candidate node=${candidate.advertisedNodeId.toHex()} addr=${device.address} rssi=${candidate.rssi}"
            else "connecting candidate addr=${device.address} rssi=${candidate.rssi}",
            LogTag.SCAN,
        )
        val client = BLEEdgeGattClient(
            context = this,
            phyMode = _phyMode.value,
            initialRssi = candidate.rssi,
            onPhyUpdate = { _, _ -> updatePeersState() },
            onFrameReceived = { frame -> handleIncomingFrame(frame, device) },
            onDisconnected = {
                val wasConnecting = connectingPeers.remove(peerHex) != null
                val link = peers.remove(peerHex)
                if (link != null) {
                    link.peerId?.let { router.neighbors.remove(it) }
                    log("peer disconnected addr=$peerHex node=${link.peerId?.toHex()}", LogTag.PEER)
                    updatePeersState()
                }
                if (_isRunning.value && (wasConnecting || link != null)) {
                    recordConnectionBackoff(peerHex)
                    scheduleConnectionDrain()
                }
            },
            onLog = { addr, msg -> log("gatt addr=$addr $msg", LogTag.GATT) },
            onNodeInfoRead = { peerId, peerPubKey, caps ->
                connectingPeers.remove(peerHex)
                connectionFailures.remove(peerHex)
                reconnectAfterMs.remove(peerHex)
                router.neighbors.upsert(ProtoNeighbor(id = peerId, publicKey = peerPubKey, rssi = candidate.rssi, provisionalCaps = caps))
                refreshTopologyState()
                val dupOutgoing = peers.entries.any { (key, link) -> key != peerHex && link.peerId == peerId }
                val haveInbound = serverPeers.values.any { it == peerId }
                when {
                    dupOutgoing -> {
                        log("peer=${peerId.toHex()} already outbound on another link; dropping addr=$peerHex", LogTag.PEER)
                        scheduleConnectionDrain()
                        peers.remove(peerHex); false
                    }
                    haveInbound && localId >= peerId -> {
                        log("peer=${peerId.toHex()} inbound and we are larger — keeping inbound", LogTag.PEER)
                        scheduleConnectionDrain()
                        peers.remove(peerHex); false
                    }
                    else -> {
                        log("connected to peer=${peerId.toHex()} caps=$caps", LogTag.PEER)
                        scope.launch { sendAnnounce() }
                        scheduleConnectionDrain()
                        updatePeersState(); true
                    }
                }
            },
        )
        peers[peerHex] = BLEPeerLink(client)
        scope.launch(Dispatchers.Main) { client.connect(device) }
        scheduleConnectionDrain(CONNECT_TIMEOUT_MS + 500)
    }

    private fun recordConnectionBackoff(addrHex: String) {
        synchronized(connectionLock) { recordConnectionBackoffLocked(addrHex, System.currentTimeMillis()) }
    }

    private fun recordConnectionBackoffLocked(addrHex: String, now: Long) {
        val failures = (connectionFailures[addrHex] ?: 0) + 1
        connectionFailures[addrHex] = failures
        val delayMs = (RECONNECT_BASE_DELAY_MS shl (failures - 1).coerceAtMost(4))
            .coerceAtMost(RECONNECT_MAX_DELAY_MS)
        reconnectAfterMs[addrHex] = now + delayMs
        connectionCandidates.remove(addrHex)
        log("connection backoff addr=$addrHex failures=$failures delay=${delayMs}ms", LogTag.PEER)
    }

    // ---- receive path --------------------------------------------------------

    private fun handleIncomingFrame(frameBytes: ByteArray, device: BluetoothDevice) {
        if (!_isRunning.value) return
        val frame = runCatching { Frame.decode(frameBytes) }.getOrElse { log("decode frame error: ${it.message}"); return }
        val addrHex = device.address.replace(":", "")
        if (frame.fragmentCount > 1) {
            log(
                "rx fragment addr=$addrHex transfer=${frame.transferId.take(4).toHex()} " +
                    "${frame.fragmentIndex + 1}/${frame.fragmentCount} len=${frame.data.size} crc=0x%08x".format(frame.payloadCrc32),
                LogTag.GATT,
            )
        }
        val data = runCatching { reassembler.addFrame(addrHex, frame) }.getOrElse { return } ?: return
        if (frame.fragmentCount > 1) {
            log("rx reassembled addr=$addrHex transfer=${frame.transferId.take(4).toHex()} bytes=${data.size}", LogTag.GATT)
        }
        val dg = runCatching { Datagram.decode(data) }.getOrElse { log("decode datagram error: ${it.message}"); return }

        // The directly connected peer that relayed this to us is the last path hop, or the
        // source when the originator is our direct neighbor (originators don't append themselves).
        val directPeer = dg.path.lastOrNull() ?: dg.source.takeIf { !it.isBroadcast() && it != _nodeId.value }
        learnNeighbor(directPeer, addrHex, device)

        // A reception of one of our own flooded messages is a repeat (a relay rebroadcast it).
        val idHex = dg.id.toHex()
        if (originatedFloodIds.containsKey(idHex)) {
            val rssi = peers[addrHex]?.rssi ?: RSSI_UNKNOWN
            _floodRepeats.update { m -> m + (idHex to ((m[idHex] ?: emptyList()) + RepeatSample(System.currentTimeMillis(), rssi, directPeer, raw = data))) }
        }

        val actions = router.handle(dg, directPeer)

        // Capture for the Rx Log AFTER routing so we can flag drops (duplicate, loop, ttl, …).
        val forUs = dg.isBroadcast || dg.destination == _nodeId.value
        val chatKind = if (dg.protocol == PayloadProtocol.BLEEDGE_CHAT) Chat.peekKind(dg.payload) else null
        val controlKind = if (dg.protocol == PayloadProtocol.BLEEDGE_CONTROL)
            runCatching { ControlMessage.decode(dg.payload).kind }.getOrNull() else null
        val droppedReason = actions.firstOrNull { it.type == ActionType.DROP }?.reason
        _rxPackets.update {
            (listOf(RxPacket(
                timestampMs = System.currentTimeMillis(),
                protocol = dg.protocol, chatKind = chatKind, controlKind = controlKind,
                id = dg.id, source = dg.source, destination = dg.destination,
                sourceRouted = dg.isSourceRouted, routeCursor = dg.routeCursor, ttl = dg.ttl,
                flags = dg.flags, ackRequested = dg.ackRequested(),
                path = dg.path, route = dg.route, payloadSize = dg.payload.size, raw = data, forUs = forUs,
                rssi = peers[addrHex]?.rssi ?: RSSI_UNKNOWN,
                droppedReason = droppedReason,
            )) + it).take(maxRxPackets)
        }
        _rxTotal.update { it + 1 }

        // MeshCore subsystem: record every MeshCore-carrying datagram we see. Side effects such
        // as chat/discovery ingestion are deduped inside handleMeshCorePacket.
        if (dg.protocol == PayloadProtocol.MESHCORE_PACKET) {
            handleMeshCorePacket(dg, addrHex, data)
        }

        _stats.update { s ->
            s.copy(
                packetsReceived = s.packetsReceived + 1,
                duplicatesDropped = s.duplicatesDropped + actions.count { it.type == ActionType.DROP && it.reason == DropReason.DUPLICATE },
            )
        }
        executeActions(actions)
    }

    private fun learnNeighbor(directPeer: NodeId?, addrHex: String, device: BluetoothDevice) {
        if (directPeer == null) return
        // Register/refresh inbound server peers so we can notify them back.
        val isOutgoing = peers[addrHex]?.let { it.peerId == directPeer && it.isUsable } == true ||
            peers.values.any { it.peerId == directPeer && it.isUsable }
        if (!isOutgoing) {
            if (serverPeers[addrHex] != directPeer) {
                serverPeers[addrHex] = directPeer
                serverPeerDevices[addrHex] = device
                updatePeersState()
            }
            val existing = router.neighbors.get(directPeer)
            if (existing == null) {
                router.neighbors.upsert(ProtoNeighbor(id = directPeer, rssi = RSSI_UNKNOWN))
            } else {
                router.neighbors.touch(directPeer)
            }
        } else {
            router.neighbors.touch(directPeer)
        }
    }

    private fun executeActions(actions: List<Action>) {
        for (action in actions) {
            when (action.type) {
                ActionType.DELIVER_LOCAL -> deliverLocal(action.datagram)
                ActionType.RELAY_FLOOD -> {
                    val jitter = router.floodJitterMs()
                    scope.launch { delay(jitter); relayFlood(action) }
                }
                ActionType.RELAY_NEXT_HOP -> relayNextHop(action)
                ActionType.SEND_ACK -> sendBuiltAck(action)
                ActionType.DROP -> log("drop reason=${action.reason} src=${action.datagram.source.toHex()} id=${action.datagram.id.take(4).toHex()}", LogTag.ROUTER)
            }
        }
    }

    private fun deliverLocal(dg: Datagram) {
        when (dg.protocol) {
            PayloadProtocol.BLEEDGE_CONTROL -> deliverControl(dg)
            PayloadProtocol.BLEEDGE_CHAT -> deliverChat(dg)
            // MeshCore packets are handled (deduped) in handleMeshCorePacket from the receive path.
            PayloadProtocol.MESHCORE_PACKET -> Unit
            else -> appendMessage(ReceivedMessage(dg.source, dg.id, dg.protocol, path = dg.path))
        }
    }

    /**
     * Handles a received MeshCore-carrying datagram: every sighting updates the MeshCore Rx Log
     * receive count, while side effects are deduped on the inner MeshCore packet bytes.
     */
    private fun handleMeshCorePacket(dg: Datagram, addrHex: String, raw: ByteArray) {
        val hash = sha256(dg.payload)
        val contentId = hash.copyOf(6).toHex()
        val rssi = peers[addrHex]?.rssi ?: RSSI_UNKNOWN
        val firstSight = meshCoreContentSeen.firstSight(hash.toHex())
        val existing = _meshCorePackets.value.firstOrNull { it.contentId == contentId }
        val env = existing?.envelope ?: MeshCoreCodec.decodeEnvelope(dg.payload)
        log(
            "meshcore rx carrier dg=${dg.id.take(4).toHex()} content=$contentId raw=${dg.payload.size}B " +
                "type=${env?.type ?: "decode-failed"} route=${env?.route ?: "?"} hops=${env?.hopCount ?: -1} first=$firstSight",
            LogTag.MSG,
        )

        // MeshCore ADVERT → discovered-contacts feed.
        if (firstSight && env != null && env.type == MeshCoreType.ADVERT && env.payload.isNotEmpty()) {
            MeshCoreCodec.decodeAdvert(env.payload)?.let { adv ->
                _meshCoreAdverts.update { (listOf(adv) + it).take(maxRxPackets) }
            }
        }

        // MeshCore channel message (GRP_TXT): decrypt for display + ingest into the chat-app.
        var sender: String? = existing?.channelSender
        var text: String? = existing?.channelText
        if (firstSight && env != null && env.isGroupText && env.payload.isNotEmpty()) {
            for (secret in meshCoreChannelSecrets) {
                val decoded = ChatChannel.decodePayload(secret, env.payload) ?: continue
                sender = decoded.senderLabel
                text = decoded.text
                break
            }
            // If this GRP_TXT is the echo of a channel message we bridged out, fold it into that
            // message's flood-repeats (so it's marked delivered + shown like a normal echo) instead
            // of surfacing a duplicate inbound bubble of our own message.
            val originId = originatedChannelCp[env.payload.toHex()]
            if (originId != null) {
                _floodRepeats.update { m ->
                    m + (originId to ((m[originId] ?: emptyList()) +
                        RepeatSample(System.currentTimeMillis(), rssi, dg.source, viaMeshCore = true, raw = raw)))
                }
                log("meshcore echo of our channel msg dg=$originId content=$contentId", LogTag.MSG)
            } else {
                val transport = if (env.isTransport && env.transportCodes != null)
                    " transport %04x/%04x".format(env.transportCodes!![0], env.transportCodes!![1]) else ""
                appendMessage(
                    ReceivedMessage(
                        dg.source, dg.id, dg.protocol,
                        chatKind = ChatKind.CHANNEL_TEXT,
                        channelPayload = env.payload,
                        path = dg.path,
                        fromMeshCore = true,
                        meshCoreType = env.type,
                        meshCoreRoute = env.route + transport,
                        meshCoreHops = env.hopCount,
                        meshCorePacketId = contentId,
                    )
                )
            }
        }

        val packet = MeshCorePacket(
            timestampMs = System.currentTimeMillis(),
            source = dg.source, datagramId = dg.id, path = dg.path,
            directRssi = rssi, raw = dg.payload, envelope = env, contentId = contentId,
            channelSender = sender, channelText = text,
            receiveCount = (existing?.receiveCount ?: 0) + 1,
        )
        _meshCorePackets.update { packets ->
            (listOf(packet) + packets.filterNot { it.contentId == contentId }).take(maxRxPackets)
        }
        _meshCoreTotal.update { it + 1 }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Chat-app pushes the joined-channel PSKs so the MeshCore Rx Log can render GRP_TXT plaintext. */
    fun setMeshCoreChannelSecrets(secrets: List<ByteArray>) {
        meshCoreChannelSecrets = secrets
    }

    private fun deliverControl(dg: Datagram) {
        val ctrl = runCatching { ControlMessage.decode(dg.payload) }.getOrNull() ?: return
        when (ctrl.kind) {
            ControlKind.ANNOUNCE -> { /* topology already updated + verified in the router */ }
            ControlKind.ACK -> {
                val acked = runCatching { AckBody.decode(ctrl.body).ackedId }.getOrNull()
                val resolved = resolveDmAck(acked)
                log("ACK from=${dg.source.toHex()} acks=${acked?.take(4)?.toHex()}", LogTag.MSG)
                appendMessage(ReceivedMessage(dg.source, dg.id, dg.protocol, isAck = true, ackedId = resolved, path = dg.path))
            }
            ControlKind.TRACE_REQUEST -> respondToTrace(dg, ctrl)
            ControlKind.TRACE_RESPONSE -> {
                val body = runCatching { TraceResponseBody.decode(ctrl.body) }.getOrNull()
                appendMessage(ReceivedMessage(dg.source, dg.id, dg.protocol, traceResponse = body, path = dg.path))
            }
            ControlKind.BRIDGED -> {
                val body = runCatching { BridgedBody.decode(ctrl.body) }.getOrNull()
                if (body != null) {
                    log("BRIDGED from=${dg.source.toHex()} dg=${body.bridgedId.take(4).toHex()} -> MeshCore", LogTag.MSG)
                    appendMessage(
                        ReceivedMessage(
                            dg.source, dg.id, dg.protocol,
                            bridgedDatagramId = body.bridgedId,
                            bridgedByNodeId = body.bridgeId,
                            path = dg.path,
                        ),
                    )
                }
            }
            else -> Unit
        }
    }

    private fun deliverChat(dg: Datagram) {
        val id = identity ?: return
        val ctx = ChatContext(dg.id, dg.source, dg.destination)
        val msg = when (Chat.peekKind(dg.payload)) {
            ChatKind.PUBLIC_TEXT -> ChatPublicText.open(dg.payload, ctx)?.let {
                ReceivedMessage(dg.source, dg.id, dg.protocol, ChatKind.PUBLIC_TEXT, it.text, it.senderPublicKey, path = dg.path, sentAtMs = it.sentAt * 1000)
            }
            ChatKind.DIRECT_TEXT -> ChatDirectText.open(id, dg.payload, ctx)?.let {
                ReceivedMessage(dg.source, dg.id, dg.protocol, ChatKind.DIRECT_TEXT, it.text, it.senderPublicKey, path = dg.path, sentAtMs = it.sentAt * 1000)
            }
            ChatKind.TYPING -> ChatTyping.open(dg.payload, ctx)?.let {
                ReceivedMessage(dg.source, dg.id, dg.protocol, ChatKind.TYPING, isTyping = true, senderPublicKey = it.senderPublicKey, path = dg.path, sentAtMs = it.sentAt * 1000)
            }
            ChatKind.CHANNEL_TEXT -> ChatChannel.channelPayload(dg.payload)?.let {
                ReceivedMessage(dg.source, dg.id, dg.protocol, ChatKind.CHANNEL_TEXT, channelPayload = it, path = dg.path)
            }
            ChatKind.DIRECT_REACTION -> ChatDirectReaction.open(id, dg.payload, ctx)?.let {
                ReceivedMessage(
                    dg.source, dg.id, dg.protocol, ChatKind.DIRECT_REACTION,
                    senderPublicKey = it.senderPublicKey, path = dg.path, sentAtMs = it.sentAt * 1000,
                    reactionTargetRef = it.targetRef, reactionEmoji = it.emoji, reactionRemove = it.remove,
                )
            }
            ChatKind.CHANNEL_REACTION -> ChatChannelReaction.channelPayload(dg.payload)?.let {
                ReceivedMessage(dg.source, dg.id, dg.protocol, ChatKind.CHANNEL_REACTION, channelReactionPayload = it, path = dg.path)
            }
            else -> null
        } ?: return
        appendMessage(msg)
        maybeNotify(msg)
    }

    private fun maybeNotify(msg: ReceivedMessage) {
        val text = msg.text ?: return
        if (msg.isTyping) return
        val peerHex = msg.fromNodeId.toHex()
        if (MessageNotifier.isConversationActive(peerHex)) return
        val sender = peerHex.take(8)
        MessageNotifier.show(this, "BLEEdge · $sender", text, sender.hashCode())
    }

    // ---- trace ---------------------------------------------------------------

    private fun respondToTrace(req: Datagram, ctrl: ControlMessage) {
        val body = runCatching { TraceRequestBody.decode(ctrl.body) }.getOrNull() ?: return
        // The request's path is the forward path; build a reverse source route to the origin.
        val forwardPath = req.path
        val hopsBack = forwardPath.dropLast(1).reversed() // exclude us (last path entry)
        val route = hopsBack + req.source
        val resp = TraceResponseBody(
            tag = body.tag, metric = body.metric, forwardPath = forwardPath,
            forwardSamples = body.forwardSamples, returnSamples = emptyList(),
        )
        val dg = Datagram(
            source = _nodeId.value, destination = req.source, ttl = route.size, route = route, routeCursor = 0,
            protocol = PayloadProtocol.BLEEDGE_CONTROL, payload = resp.toControl().encode(),
        )
        router.markOriginated(dg.id)
        transmitToFirstHop(dg)
    }

    /** Sends a source-routed trace request. Returns the tag, or null if no route is known. */
    fun sendTrace(destination: NodeId, explicitRoute: List<NodeId> = emptyList()): Int? {
        val route = if (explicitRoute.isNotEmpty()) {
            explicitRoute.toMutableList().also { if (it.lastOrNull() != destination) it += destination }
        } else router.selectRoute(destination) ?: return null
        val tag = Random.nextInt().toLong() and 0xFFFFFFFFL
        val body = TraceRequestBody(tag, TraceMetric.RSSI_DBM, emptyList())
        val dg = Datagram(
            source = _nodeId.value, destination = destination, ttl = route.size, route = route, routeCursor = 0,
            protocol = PayloadProtocol.BLEEDGE_CONTROL, payload = body.toControl().encode(),
        )
        router.markOriginated(dg.id)
        transmitToFirstHop(dg)
        _stats.update { it.copy(tracesSent = it.tracesSent + 1) }
        return tag.toInt()
    }

    // ---- transmit ------------------------------------------------------------

    private fun framesFor(dg: Datagram): List<Frame> = Frame.fragment(dg.encode(), BLEEdge.MAX_FRAME_SIZE)

    private fun sendFramesToAll(frames: List<Frame>, exclude: NodeId? = null) {
        for ((addrHex, link) in peers) {
            if (link.peerId != null && link.peerId == exclude) continue
            if (!link.isUsable) {
                continue
            }
            for (f in frames) {
                if (!link.sendFrame(f.encode())) {
                    removePeerLink(addrHex, link, "send rejected")
                    break
                }
            }
        }
        val server = bleGattServer ?: return
        for ((addrHex, nid) in serverPeers) {
            if (nid == null || nid == exclude) continue
            val device = serverPeerDevices[addrHex] ?: continue
            for (f in frames) {
                if (!server.notifyFrameTo(f.encode(), device)) {
                    removeServerPeer(device, "notify failed")
                    break
                }
            }
        }
    }

    private fun sendFramesToPeer(frames: List<Frame>, nodeId: NodeId): Boolean {
        peers.entries.firstOrNull { it.value.peerId == nodeId }?.let { (addrHex, link) ->
            if (!link.isUsable) {
                log("send-to-peer: peer ${nodeId.toHex()} not ready addr=$addrHex", LogTag.ROUTER)
                return false
            }
            for (f in frames) {
                if (!link.sendFrame(f.encode())) {
                    removePeerLink(addrHex, link, "send rejected")
                    return false
                }
            }
            return true
        }
        val server = bleGattServer ?: return false
        val entry = serverPeers.entries.firstOrNull { it.value == nodeId } ?: return false
        val device = serverPeerDevices[entry.key] ?: return false
        for (f in frames) {
            if (!server.notifyFrameTo(f.encode(), device)) {
                removeServerPeer(device, "notify failed")
                return false
            }
        }
        return true
    }

    private fun relayFlood(action: Action) {
        sendFramesToAll(framesFor(action.datagram), exclude = action.excludePeer)
        _stats.update { it.copy(floodRelays = it.floodRelays + 1) }
    }

    private fun relayNextHop(action: Action) {
        val nh = action.nextHop ?: return
        if (!sendFramesToPeer(framesFor(action.datagram), nh)) {
            log("relay-next-hop: peer ${nh.toHex()} not connected — dropping", LogTag.ROUTER)
        }
    }

    private fun sendBuiltAck(action: Action) {
        val nh = action.nextHop ?: return
        val frames = framesFor(action.datagram)
        if (!sendFramesToPeer(frames, nh)) sendFramesToAll(frames)
        _stats.update { it.copy(acksSent = it.acksSent + 1) }
    }

    private fun transmitToFirstHop(dg: Datagram) {
        val firstHop = dg.route.firstOrNull() ?: return
        if (!sendFramesToPeer(framesFor(dg), firstHop)) {
            log("source-route: first hop ${firstHop.toHex()} not connected — dropping", LogTag.ROUTER)
        }
    }

    /** Originates and transmits a datagram (flood or source-routed). */
    private fun transmit(dg: Datagram, trackFloodRepeat: Boolean = false) {
        // Cache the raw bytes of everything we originate so the app can show our outgoing packet.
        if (originatedRaw.size > 400) originatedRaw.keys.take(150).forEach { originatedRaw.remove(it) }
        originatedRaw[dg.id.toHex()] = dg.encode().toHex()
        if (trackFloodRepeat && !dg.isSourceRouted) {
            if (originatedFloodIds.size > 300) {
                originatedFloodIds.entries.sortedBy { it.value }.take(100).forEach { originatedFloodIds.remove(it.key) }
            }
            originatedFloodIds[dg.id.toHex()] = System.currentTimeMillis()
        }
        if (dg.isSourceRouted) transmitToFirstHop(dg) else sendFramesToAll(framesFor(dg))
        _stats.update { it.copy(packetsSent = it.packetsSent + 1) }
    }

    private fun sendAnnounce() {
        val id = identity ?: return
        if (!::router.isInitialized) return
        announceSeq++
        val body = AnnounceBody.create(
            identity = id, epoch = epoch, seq = announceSeq, timestamp = System.currentTimeMillis() / 1000,
            caps = ANDROID_CAPABILITIES, neighbors = router.neighbors.ids(),
            name = nodeName, description = nodeDescription, platform = nodePlatform,
        )
        val dg = router.newBroadcast(PayloadProtocol.BLEEDGE_CONTROL, body.toControl().encode(), BLEEdge.ANNOUNCE_TTL)
        log("ANNOUNCE epoch=$epoch seq=$announceSeq neighbors=${router.neighbors.ids().size}", LogTag.ROUTER)
        sendFramesToAll(framesFor(dg))
    }

    // ---- public send API -----------------------------------------------------

    /**
     * Sends a chat text. Broadcast → signed PUBLIC_TEXT. Unicast → encrypted DIRECT_TEXT
     * (ACK_REQUESTED, with retry). Returns the originated datagram id, or null if a DM
     * recipient's public key isn't known.
     */
    fun sendChat(text: String, destination: NodeId, recipientPub: ByteArray? = null, floodTtl: Int = BLEEdge.DEFAULT_FLOOD_TTL): ByteArray? {
        if (!Chat.run { text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES }) return null
        val id = identity ?: return null
        if (destination.isBroadcast()) {
            val dgId = Datagram.newDatagramId()
            val ctx = ChatContext(dgId, id.nodeId, NodeId.BROADCAST)
            val payload = ChatPublicText.build(id, ctx, text, System.currentTimeMillis() / 1000)
            val dg = Datagram(id = dgId, source = id.nodeId, destination = NodeId.BROADCAST, ttl = floodTtl,
                protocol = PayloadProtocol.BLEEDGE_CHAT, payload = payload)
            router.markOriginated(dg.id)
            transmit(dg, trackFloodRepeat = true)
            return dg.id
        }
        val pub = recipientPub?.takeIf { it.size == 32 } ?: router.publicKeyFor(destination)
        if (pub == null || pub.size != 32) {
            log("sendChat: no public key for ${destination.toHex()} — cannot encrypt", LogTag.MSG)
            return null
        }
        return startDmDelivery(text, pub, destination, floodTtl)
    }

    /** Sends a MeshCore-compatible channel message sealed with [secret]. Returns the datagram id. */
    fun sendChannel(secret: ByteArray, senderLabel: String, text: String, floodTtl: Int = BLEEdge.DEFAULT_FLOOD_TTL): ByteArray {
        val id = identity!!
        val dgId = Datagram.newDatagramId()
        val payload = ChatChannel.build(secret, senderLabel, text, System.currentTimeMillis() / 1000)
        // Remember this message's channel_payload so we can match the echo that returns over the
        // MeshCore bridge (the bridge re-floods the same payload onto MeshCore). Bounded.
        ChatChannel.channelPayload(payload)?.let { cp ->
            if (originatedChannelCp.size > 500) {
                originatedChannelCp.keys.take(200).forEach { originatedChannelCp.remove(it) }
            }
            originatedChannelCp[cp.toHex()] = dgId.toHex()
        }
        val dg = Datagram(id = dgId, source = id.nodeId, destination = NodeId.BROADCAST, ttl = floodTtl,
            protocol = PayloadProtocol.BLEEDGE_CHAT, payload = payload)
        router.markOriginated(dg.id)
        transmit(dg, trackFloodRepeat = true)
        return dg.id
    }

    /**
     * Sends an encrypted DIRECT_REACTION (add/remove an emoji on [targetRef], the target message's
     * id). One-shot, no ACK/retry. Returns true if sent, false if the recipient key is unknown.
     */
    fun sendDirectReaction(
        destination: NodeId,
        recipientPub: ByteArray?,
        targetRef: String,
        emoji: String,
        remove: Boolean,
        floodTtl: Int = BLEEdge.DEFAULT_FLOOD_TTL,
    ): Boolean {
        val id = identity ?: return false
        val pub = recipientPub?.takeIf { it.size == 32 } ?: router.publicKeyFor(destination)
        if (pub == null || pub.size != 32) return false
        val route = router.selectRoute(destination)
        val dgId = Datagram.newDatagramId()
        val ctx = ChatContext(dgId, id.nodeId, destination)
        val payload = ChatDirectReaction.seal(id, pub, ctx, targetRef, emoji, remove, System.currentTimeMillis() / 1000)
        val dg = if (route != null)
            Datagram(id = dgId, source = id.nodeId, destination = destination, ttl = route.size, route = route,
                protocol = PayloadProtocol.BLEEDGE_CHAT, payload = payload)
        else
            Datagram(id = dgId, source = id.nodeId, destination = destination, ttl = floodTtl,
                protocol = PayloadProtocol.BLEEDGE_CHAT, payload = payload)
        router.markOriginated(dg.id)
        transmit(dg)
        return true
    }

    /** Sends a native CHANNEL_REACTION (add/remove an emoji on [targetRef]) sealed with [secret]. */
    fun sendChannelReaction(
        secret: ByteArray,
        senderLabel: String,
        targetRef: String,
        emoji: String,
        remove: Boolean,
        floodTtl: Int = BLEEdge.DEFAULT_FLOOD_TTL,
    ) {
        val id = identity ?: return
        val dgId = Datagram.newDatagramId()
        val payload = ChatChannelReaction.build(secret, senderLabel, targetRef, emoji, remove, System.currentTimeMillis() / 1000)
        val dg = Datagram(id = dgId, source = id.nodeId, destination = NodeId.BROADCAST, ttl = floodTtl,
            protocol = PayloadProtocol.BLEEDGE_CHAT, payload = payload)
        router.markOriginated(dg.id)
        transmit(dg)
    }

    /** Sends an ephemeral signed typing hint (no ACK). Best-effort. */
    fun sendTyping(destination: NodeId) {
        if (destination.isBroadcast()) return
        val id = identity ?: return
        val route = router.selectRoute(destination)
        val dgId = Datagram.newDatagramId()
        val ctx = ChatContext(dgId, id.nodeId, destination)
        val payload = ChatTyping.build(id, ctx, System.currentTimeMillis() / 1000)
        val dg = if (route != null)
            Datagram(id = dgId, source = id.nodeId, destination = destination, ttl = route.size, route = route,
                protocol = PayloadProtocol.BLEEDGE_CHAT, payload = payload)
        else
            Datagram(id = dgId, source = id.nodeId, destination = destination, ttl = BLEEdge.DEFAULT_FLOOD_TTL,
                protocol = PayloadProtocol.BLEEDGE_CHAT, payload = payload)
        router.markOriginated(dg.id)
        transmit(dg)
    }

    fun setDmRetry(retryDelayMs: Long, maxTries: Int) {
        dmRetryDelayMs = retryDelayMs.coerceAtLeast(500)
        dmMaxTries = maxTries.coerceIn(1, 10)
    }

    /** Seals + sends one DIRECT_TEXT attempt (fresh id each time; AAD binds to it). Returns the id. */
    private fun sendDirectAttempt(text: String, recipientPub: ByteArray, dest: NodeId, floodTtl: Int): ByteArray {
        val id = identity!!
        val route = router.selectRoute(dest)
        val dgId = Datagram.newDatagramId()
        val ctx = ChatContext(dgId, id.nodeId, dest)
        val payload = ChatDirectText.seal(id, recipientPub, ctx, text, System.currentTimeMillis() / 1000)
        val dg = if (route != null)
            Datagram(id = dgId, source = id.nodeId, destination = dest, ttl = route.size, route = route,
                protocol = PayloadProtocol.BLEEDGE_CHAT, flags = DatagramFlags.ACK_REQUESTED, payload = payload)
        else
            Datagram(id = dgId, source = id.nodeId, destination = dest, ttl = floodTtl,
                protocol = PayloadProtocol.BLEEDGE_CHAT, flags = DatagramFlags.ACK_REQUESTED, payload = payload)
        router.markOriginated(dg.id)
        transmit(dg)
        return dg.id
    }

    private fun startDmDelivery(text: String, recipientPub: ByteArray, dest: NodeId, floodTtl: Int): ByteArray {
        val firstId = sendDirectAttempt(text, recipientPub, dest, floodTtl)
        val origHex = firstId.toHex()
        val pending = PendingDm(origHex, text, recipientPub, dest, floodTtl, attemptsSent = 1)
        pendingDms[origHex] = pending
        attemptToOriginal[origHex] = origHex
        _dmDeliveries.update { m ->
            (m + (origHex to DmDelivery(attemptsSent = 1, maxTries = dmMaxTries))).entries.toList().takeLast(300).associate { it.key to it.value }
        }
        scheduleDmRetry(pending)
        return firstId
    }

    private fun scheduleDmRetry(p: PendingDm) {
        if (dmMaxTries <= 1) return
        p.job = scope.launch {
            delay(dmRetryDelayMs)
            if (pendingDms[p.originalIdHex] !== p) return@launch
            if (p.attemptsSent >= dmMaxTries) {
                log("DM ${p.originalIdHex.take(8)} unacked after ${p.attemptsSent} tries — giving up", LogTag.MSG)
                pendingDms.remove(p.originalIdHex)
                attemptToOriginal.entries.removeAll { it.value == p.originalIdHex }
                updateDelivery(p.originalIdHex) { it.copy(failed = true) }
                return@launch
            }
            val newId = sendDirectAttempt(p.text, p.recipientPub, p.dest, p.floodTtl)
            p.attemptsSent += 1
            attemptToOriginal[newId.toHex()] = p.originalIdHex
            updateDelivery(p.originalIdHex) { it.copy(attemptsSent = p.attemptsSent) }
            scheduleDmRetry(p)
        }
    }

    private fun resolveDmAck(ackedRaw: ByteArray?): ByteArray? {
        if (ackedRaw == null) return null
        val origHex = attemptToOriginal[ackedRaw.toHex()] ?: return ackedRaw
        pendingDms.remove(origHex)?.job?.cancel()
        attemptToOriginal.entries.removeAll { it.value == origHex }
        updateDelivery(origHex) { it.copy(acked = true) }
        return origHex.hexToByteArray()
    }

    fun setDescription(description: String) {
        if (!::router.isInitialized) return
        nodeDescription = description
        scope.launch { sendAnnounce() }
    }

    fun setName(name: String) {
        if (!::router.isInitialized) return
        val pub = identity?.publicKey ?: return
        nodeName = name.ifBlank { defaultNodeName(pub) }
        scope.launch { sendAnnounce() }
    }

    fun clearData() {
        val id = identity ?: return
        router = Router(id)
        router.allowlist.addAll(allowlist)
        _receivedMessages.value = emptyList()
        _routingLog.value = emptyList()
        _neighborTable.value = emptyList()
        _knownTopology.value = emptyList()
        log("all data cleared", LogTag.SYS)
    }

    fun setAllowlist(ids: Set<String>) {
        allowlist = ids.toMutableSet()
        router.allowlist.clear()
        router.allowlist.addAll(ids)
    }

    fun setPhyMode(mode: PHYMode) {
        if (_phyMode.value == mode) return
        _phyMode.value = mode
        log("PHY mode changed to ${mode.value}", LogTag.SYS)
        if (_isRunning.value) { stopBLE(); startBLE() }
    }

    fun stopBLE() {
        _isRunning.value = false
        resetConnectionManager()
        bleAdvertiser?.stopAdvertising()
        bleScanner?.stopScan()
        peers.values.forEach { it.disconnect() }
        peers.clear(); serverPeers.clear(); serverPeerDevices.clear()
        _connectedPeers.value = emptyList()
        _advertisingActive.value = false; _scanningActive.value = false
        log("BLE stopped", LogTag.SYS)
    }

    fun startBLE() {
        val localId = _nodeId.value
        val phyMode = _phyMode.value
        bleAdvertiser?.startAdvertising(localId, phyMode) { msg -> log("advertiser: $msg", LogTag.SYS) }
        _advertisingActive.value = true
        bleScanner?.startScan(
            phyMode = phyMode,
            onFound = { device, rssi, advNodeId -> handleFoundDevice(device, rssi, advNodeId) },
            onFailed = { errorCode -> log("scan FAILED errorCode=$errorCode", LogTag.SCAN) },
        )
        _scanningActive.value = true; _isRunning.value = true
        scheduleConnectionDrain()
        log("BLE started", LogTag.SYS)
    }

    // ---- state helpers -------------------------------------------------------

    private fun updatePeersState() {
        val seen = mutableSetOf<String>()
        val list = mutableListOf<PeerInfo>()
        val now = System.currentTimeMillis()
        fun since(hex: String): Long = connectedSince.getOrPut(hex) { now }
        for (link in peers.values) {
            val pid = link.peerId ?: continue
            if (!link.isUsable) {
                if (!seen.add(pid.toHex())) continue
                router.neighbors.remove(pid)
                list += PeerInfo(pid, link.rssi, link.txPhy, link.rxPhy, link.caps, degraded = true,
                    name = router.nameFor(pid), publicKey = link.publicKey, connectedSinceMs = since(pid.toHex()))
                continue
            }
            if (!seen.add(pid.toHex())) continue
            router.neighbors.upsert(ProtoNeighbor(id = pid, publicKey = link.publicKey, rssi = link.rssi, provisionalCaps = link.caps))
            list += PeerInfo(pid, link.rssi, link.txPhy, link.rxPhy, link.caps, name = router.nameFor(pid),
                publicKey = link.publicKey, connectedSinceMs = since(pid.toHex()))
        }
        for (nid in serverPeers.values) {
            if (nid == null || nid.isBroadcast()) continue
            if (!seen.add(nid.toHex())) continue
            val nb = router.neighbors.get(nid)
            val caps = nb?.provisionalCaps ?: router.topology.getNode(nid)?.caps ?: Capabilities(0)
            list += PeerInfo(nid, nb?.rssi ?: RSSI_UNKNOWN, PHY.UNKNOWN, PHY.UNKNOWN, caps, incoming = true,
                name = router.nameFor(nid), publicKey = router.publicKeyFor(nid) ?: ByteArray(0),
                connectedSinceMs = since(nid.toHex()))
        }
        connectedSince.keys.retainAll(seen)
        _connectedPeers.value = list
    }

    private fun refreshTopologyState() {
        router.neighbors.reap(); router.topology.reap()
        _neighborTable.value = router.neighbors.all().map {
            NeighborEntry(it.id, it.rssi, it.provisionalCaps, router.nameFor(it.id))
        }
        _knownTopology.value = router.topology.allNodes().map { tn ->
            TopologyEntry(tn.id, tn.caps, tn.neighbors, tn.receivedAtMs, router.nameFor(tn.id), tn.description, tn.platform, tn.publicKey)
        }
        updatePeersState()
    }

    private fun appendMessage(msg: ReceivedMessage) {
        _receivedMessages.value = (_receivedMessages.value + msg).takeLast(200)
    }

    private fun log(msg: String, tag: LogTag = LogTag.SYS) {
        Log.d(TAG, "[$tag] $msg")
        _routingLog.value = (_routingLog.value + LogEntry(tag = tag, message = msg)).takeLast(1000)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL, "BLEEdge", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("BLEEdge").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info).build()
}

// ---- Extensions -------------------------------------------------------------

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
private fun ByteArray.take(n: Int) = copyOfRange(0, minOf(n, size))
private fun String.hexToByteArray() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
