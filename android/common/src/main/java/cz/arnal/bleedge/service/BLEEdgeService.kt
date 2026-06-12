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
import cz.arnal.bleedge.chatproto.ChatContext
import cz.arnal.bleedge.chatproto.ChatDirectText
import cz.arnal.bleedge.chatproto.ChatKind
import cz.arnal.bleedge.chatproto.ChatPublicText
import cz.arnal.bleedge.chatproto.ChatTyping
import cz.arnal.bleedge.protocol.AckBody
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
    val name: String = "",
    val publicKey: ByteArray = ByteArray(0),
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
    val traceResponse: TraceResponseBody? = null,
    val path: List<NodeId> = emptyList(),
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
)

data class RepeatSample(
    val timestampMs: Long,
    val rssi: Int,
    val forwarderId: NodeId? = null,
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
    // Incoming connections (peer is client to our server): BLE MAC hex -> direct peer NodeId (or null).
    private val serverPeers = ConcurrentHashMap<String, NodeId?>()
    private val serverPeerDevices = ConcurrentHashMap<String, BluetoothDevice>()

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

    private val originatedFloodIds = ConcurrentHashMap<String, Long>()
    private val _floodRepeats = MutableStateFlow<Map<String, List<RepeatSample>>>(emptyMap())
    val floodRepeats: StateFlow<Map<String, List<RepeatSample>>> = _floodRepeats.asStateFlow()

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

        peers.clear(); serverPeers.clear(); serverPeerDevices.clear()
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
                val addrHex = device.address.replace(":", "")
                val nid = serverPeers.remove(addrHex)
                serverPeerDevices.remove(addrHex)
                nid?.let { router.neighbors.remove(it) }
                log("device disconnected addr=${device.address} node=${nid?.toHex() ?: "unknown"}", LogTag.SERVER)
                updatePeersState()
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

    private fun handleFoundDevice(device: BluetoothDevice, rssi: Int, advNodeId: NodeId?) {
        val peerHex = device.address.replace(":", "")
        if (peers.containsKey(peerHex)) return
        val localId = _nodeId.value

        if (advNodeId != null) {
            if (advNodeId == localId) return
            if (peers.values.any { it.peerId == advNodeId }) return
            if (serverPeers.values.any { it == advNodeId }) return
            log("scan found node=${advNodeId.toHex()} addr=${device.address} rssi=$rssi — connecting", LogTag.SCAN)
        } else {
            log("scan found addr=${device.address} rssi=$rssi (no adv nodeId) — connecting", LogTag.SCAN)
        }

        val client = BLEEdgeGattClient(
            context = this,
            phyMode = _phyMode.value,
            initialRssi = rssi,
            onPhyUpdate = { _, _ -> updatePeersState() },
            onFrameReceived = { frame -> handleIncomingFrame(frame, device) },
            onDisconnected = {
                val link = peers.remove(peerHex)
                if (link != null) {
                    link.peerId?.let { router.neighbors.remove(it) }
                    log("peer disconnected addr=$peerHex node=${link.peerId?.toHex()}", LogTag.PEER)
                    updatePeersState()
                }
            },
            onLog = { addr, msg -> log("gatt addr=$addr $msg", LogTag.GATT) },
            onNodeInfoRead = { peerId, peerPubKey, caps ->
                router.neighbors.upsert(ProtoNeighbor(id = peerId, publicKey = peerPubKey, rssi = rssi, provisionalCaps = caps))
                refreshTopologyState()
                val dupOutgoing = peers.entries.any { (key, link) -> key != peerHex && link.peerId == peerId }
                val haveInbound = serverPeers.values.any { it == peerId }
                when {
                    dupOutgoing -> {
                        log("peer=${peerId.toHex()} already outbound on another link; dropping addr=$peerHex", LogTag.PEER)
                        peers.remove(peerHex); false
                    }
                    haveInbound && localId >= peerId -> {
                        log("peer=${peerId.toHex()} inbound and we are larger — keeping inbound", LogTag.PEER)
                        peers.remove(peerHex); false
                    }
                    else -> {
                        log("connected to peer=${peerId.toHex()} caps=$caps", LogTag.PEER)
                        scope.launch { sendAnnounce() }
                        updatePeersState(); true
                    }
                }
            },
        )
        peers[peerHex] = BLEPeerLink(client)
        scope.launch(Dispatchers.Main) { client.connect(device) }
    }

    // ---- receive path --------------------------------------------------------

    private fun handleIncomingFrame(frameBytes: ByteArray, device: BluetoothDevice) {
        if (!_isRunning.value) return
        val frame = runCatching { Frame.decode(frameBytes) }.getOrElse { log("decode frame error: ${it.message}"); return }
        val addrHex = device.address.replace(":", "")
        val data = runCatching { reassembler.addFrame(addrHex, frame) }.getOrElse { return } ?: return
        val dg = runCatching { Datagram.decode(data) }.getOrElse { log("decode datagram error: ${it.message}"); return }

        // The directly connected peer that relayed this to us is the last path hop, or the
        // source when the originator is our direct neighbor (originators don't append themselves).
        val directPeer = dg.path.lastOrNull() ?: dg.source.takeIf { !it.isBroadcast() && it != _nodeId.value }
        learnNeighbor(directPeer, addrHex, device)

        val forUs = dg.isBroadcast || dg.destination == _nodeId.value
        val chatKind = if (dg.protocol == PayloadProtocol.BLEEDGE_CHAT) Chat.peekKind(dg.payload) else null
        val controlKind = if (dg.protocol == PayloadProtocol.BLEEDGE_CONTROL)
            runCatching { ControlMessage.decode(dg.payload).kind }.getOrNull() else null
        _rxPackets.update {
            (listOf(RxPacket(
                timestampMs = System.currentTimeMillis(),
                protocol = dg.protocol, chatKind = chatKind, controlKind = controlKind,
                id = dg.id, source = dg.source, destination = dg.destination,
                sourceRouted = dg.isSourceRouted, routeCursor = dg.routeCursor, ttl = dg.ttl,
                flags = dg.flags, ackRequested = dg.ackRequested(),
                path = dg.path, route = dg.route, payloadSize = dg.payload.size, raw = data, forUs = forUs,
            )) + it).take(maxRxPackets)
        }

        // A reception of one of our own flooded messages is a repeat (a relay rebroadcast it).
        val idHex = dg.id.toHex()
        if (originatedFloodIds.containsKey(idHex)) {
            val rssi = peers[addrHex]?.rssi ?: RSSI_UNKNOWN
            _floodRepeats.update { m -> m + (idHex to ((m[idHex] ?: emptyList()) + RepeatSample(System.currentTimeMillis(), rssi, directPeer))) }
        }

        val actions = router.handle(dg, directPeer)
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
        val isOutgoing = peers[addrHex]?.peerId == directPeer || peers.values.any { it.peerId == directPeer }
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
            else -> appendMessage(ReceivedMessage(dg.source, dg.id, dg.protocol, path = dg.path))
        }
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
            else -> Unit
        }
    }

    private fun deliverChat(dg: Datagram) {
        val id = identity ?: return
        val ctx = ChatContext(dg.id, dg.source, dg.destination)
        val msg = when (Chat.peekKind(dg.payload)) {
            ChatKind.PUBLIC_TEXT -> ChatPublicText.open(dg.payload, ctx)?.let {
                ReceivedMessage(dg.source, dg.id, dg.protocol, ChatKind.PUBLIC_TEXT, it.text, it.senderPublicKey, path = dg.path)
            }
            ChatKind.DIRECT_TEXT -> ChatDirectText.open(id, dg.payload, ctx)?.let {
                ReceivedMessage(dg.source, dg.id, dg.protocol, ChatKind.DIRECT_TEXT, it.text, it.senderPublicKey, path = dg.path)
            }
            ChatKind.TYPING -> ChatTyping.open(dg.payload, ctx)?.let {
                ReceivedMessage(dg.source, dg.id, dg.protocol, ChatKind.TYPING, isTyping = true, senderPublicKey = it.senderPublicKey, path = dg.path)
            }
            ChatKind.CHANNEL_TEXT -> ChatChannel.channelPayload(dg.payload)?.let {
                ReceivedMessage(dg.source, dg.id, dg.protocol, ChatKind.CHANNEL_TEXT, channelPayload = it, path = dg.path)
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
        for (link in peers.values) {
            if (link.peerId != null && link.peerId == exclude) continue
            for (f in frames) link.sendFrame(f.encode())
        }
        val server = bleGattServer ?: return
        for ((addrHex, nid) in serverPeers) {
            if (nid == null || nid == exclude) continue
            val device = serverPeerDevices[addrHex] ?: continue
            for (f in frames) server.notifyFrameTo(f.encode(), device)
        }
    }

    private fun sendFramesToPeer(frames: List<Frame>, nodeId: NodeId): Boolean {
        peers.values.firstOrNull { it.peerId == nodeId }?.let { link ->
            for (f in frames) link.sendFrame(f.encode()); return true
        }
        val server = bleGattServer ?: return false
        val entry = serverPeers.entries.firstOrNull { it.value == nodeId } ?: return false
        val device = serverPeerDevices[entry.key] ?: return false
        for (f in frames) server.notifyFrameTo(f.encode(), device)
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
        val dg = Datagram(id = dgId, source = id.nodeId, destination = NodeId.BROADCAST, ttl = floodTtl,
            protocol = PayloadProtocol.BLEEDGE_CHAT, payload = payload)
        router.markOriginated(dg.id)
        transmit(dg, trackFloodRepeat = true)
        return dg.id
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
        bleAdvertiser?.stopAdvertising()
        bleScanner?.stopScan()
        peers.values.forEach { it.disconnect() }
        peers.clear(); serverPeers.clear(); serverPeerDevices.clear()
        _connectedPeers.value = emptyList()
        _advertisingActive.value = false; _scanningActive.value = false; _isRunning.value = false
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
        log("BLE started", LogTag.SYS)
    }

    // ---- state helpers -------------------------------------------------------

    private fun updatePeersState() {
        val seen = mutableSetOf<String>()
        val list = mutableListOf<PeerInfo>()
        for (link in peers.values) {
            val pid = link.peerId ?: continue
            if (!seen.add(pid.toHex())) continue
            router.neighbors.upsert(ProtoNeighbor(id = pid, publicKey = link.publicKey, rssi = link.rssi, provisionalCaps = link.caps))
            list += PeerInfo(pid, link.rssi, link.txPhy, link.rxPhy, link.caps, name = router.nameFor(pid), publicKey = link.publicKey)
        }
        for (nid in serverPeers.values) {
            if (nid == null || nid.isBroadcast()) continue
            if (!seen.add(nid.toHex())) continue
            val nb = router.neighbors.get(nid)
            val caps = nb?.provisionalCaps ?: router.topology.getNode(nid)?.caps ?: Capabilities(0)
            list += PeerInfo(nid, nb?.rssi ?: RSSI_UNKNOWN, PHY.UNKNOWN, PHY.UNKNOWN, caps, incoming = true,
                name = router.nameFor(nid), publicKey = router.publicKeyFor(nid) ?: ByteArray(0))
        }
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
