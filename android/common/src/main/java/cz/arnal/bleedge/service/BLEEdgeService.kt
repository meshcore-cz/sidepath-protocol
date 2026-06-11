package cz.arnal.bleedge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
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
import cz.arnal.bleedge.core.Action
import cz.arnal.bleedge.core.ActionType
import cz.arnal.bleedge.core.AnnouncePayload
import cz.arnal.bleedge.core.ANDROID_CAPABILITIES
import cz.arnal.bleedge.core.Capabilities
import cz.arnal.bleedge.core.Crypto
import cz.arnal.bleedge.core.Frame
import cz.arnal.bleedge.core.Identity
import cz.arnal.bleedge.core.NodeID
import cz.arnal.bleedge.core.isBroadcast
import cz.arnal.bleedge.core.Packet
import cz.arnal.bleedge.core.PacketType
import cz.arnal.bleedge.core.PayloadType
import cz.arnal.bleedge.core.PHY
import cz.arnal.bleedge.core.PHYMode
import cz.arnal.bleedge.core.PROTOCOL_VERSION
import cz.arnal.bleedge.core.Reassembler
import cz.arnal.bleedge.core.Router
import cz.arnal.bleedge.core.RoutingMode
import cz.arnal.bleedge.core.TRACE_HASH_WIDTH_8
import cz.arnal.bleedge.core.TRACE_METRIC_RSSI
import cz.arnal.bleedge.core.TracePayload
import cz.arnal.bleedge.core.TraceResult
import cz.arnal.bleedge.core.decodeTracePayload
import cz.arnal.bleedge.core.encodeTracePayload
import cz.arnal.bleedge.core.fragmentPacket
import cz.arnal.bleedge.core.newPacketID
import cz.arnal.bleedge.core.randomTraceTag
import cz.arnal.bleedge.core.traceFlagsForHashWidth
import cz.arnal.bleedge.core.traceRouteData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "BLEEdgeService"
private const val NOTIFICATION_CHANNEL = "bleedge"
private const val NOTIFICATION_ID = 1
// Sentinel for "RSSI not measurable" (e.g. inbound GATT-server peers).
const val RSSI_UNKNOWN = Int.MIN_VALUE

// ---- UI data models ---------------------------------------------------------

data class PeerInfo(
    val nodeId: NodeID,
    val rssi: Int,
    val txPhy: PHY,
    val rxPhy: PHY,
    val caps: Capabilities,
    val phyInvalid: Boolean = false,
    val incoming: Boolean = false,  // true = peer connected TO us (server side)
    val description: String = "",   // diagnostic label (NODE_INFO or ANNOUNCE)
)

data class ReceivedMessage(
    val fromNodeId: NodeID,
    val payload: ByteArray,
    val timestampMs: Long = System.currentTimeMillis(),
    val isAck: Boolean = false,
    val payloadType: PayloadType = PayloadType.TEXT_TEST,
    val trace: List<NodeID> = emptyList(), // hop path the packet took to reach us (route detail)
    val text: String? = null,              // decoded/decrypted text for chat payloads
    val ackedId: ByteArray? = null,        // for ACKs: id of the DATA packet being acked
    val packetId: ByteArray = ByteArray(0),
)

enum class LogTag { SYS, SCAN, PEER, SERVER, GATT, PHY, ROUTER, MSG }

data class LogEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val tag: LogTag = LogTag.SYS,
    val message: String,
)

data class NeighborEntry(
    val nodeId: NodeID,
    val rssi: Int,
    val txPhy: PHY,
    val rxPhy: PHY,
    val caps: Capabilities,
    val description: String = "",
)

data class TopologyEntry(
    val nodeId: NodeID,
    val caps: Capabilities,
    val neighborIds: List<NodeID>,
    val lastAnnounceMs: Long = 0L, // wall-clock time the last ANNOUNCE from this node was seen
    val description: String = "",
    val publicKey: ByteArray = ByteArray(0), // 32-byte Ed25519 key (for chat encryption)
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
    // Outgoing connections: BLE MAC hex → link (we are GattClient)
    private val peers = ConcurrentHashMap<String, BLEPeerLink>()
    // Incoming connections: BLE MAC hex → NodeID (peer is GattClient to our GattServer)
    // NodeID is null until the peer's first packet arrives and reveals pkt.source.
    private val serverPeers = ConcurrentHashMap<String, NodeID?>()
    // BluetoothDevice objects for server-side peers (needed to send notifications back)
    private val serverPeerDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private var announceSeq = 0
    private var bleAdvertiser: BLEEdgeAdvertiser? = null
    private var bleScanner: BLEEdgeScanner? = null

    // Local Ed25519 identity (set in initialize); NodeID = identity.nodeId.
    private var identity: Identity? = null

    // StateFlows consumed by the UI
    private val _nodeId = MutableStateFlow(NodeID(ByteArray(8)))
    val nodeId: StateFlow<NodeID> = _nodeId.asStateFlow()

    private val _bleMacAddress = MutableStateFlow("")
    val bleMacAddress: StateFlow<String> = _bleMacAddress.asStateFlow()

    private val _phyMode = MutableStateFlow(PHYMode.DEBUG_1M)
    val phyMode: StateFlow<PHYMode> = _phyMode.asStateFlow()

    private val _codedPhySupported = MutableStateFlow(false)
    val codedPhySupported: StateFlow<Boolean> = _codedPhySupported.asStateFlow()

    // True when the device doesn't support the requested PHY and 1M fallback is active
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

    private val _receivedMessages = MutableStateFlow<List<ReceivedMessage>>(emptyList())
    val receivedMessages: StateFlow<List<ReceivedMessage>> = _receivedMessages.asStateFlow()

    private val _routingLog = MutableStateFlow<List<LogEntry>>(emptyList())
    val routingLog: StateFlow<List<LogEntry>> = _routingLog.asStateFlow()

    private val _neighborTable = MutableStateFlow<List<NeighborEntry>>(emptyList())
    val neighborTable: StateFlow<List<NeighborEntry>> = _neighborTable.asStateFlow()

    private val _knownTopology = MutableStateFlow<List<TopologyEntry>>(emptyList())
    val knownTopology: StateFlow<List<TopologyEntry>> = _knownTopology.asStateFlow()

    private var allowlist = mutableSetOf<String>() // hex NodeID strings

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("BLEEdge running"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        bleManager.stopAll()
        scope.cancel()
        super.onDestroy()
    }

    /** Default diagnostic label for this device, used when no description is configured. */
    fun defaultDescription(): String = "Android ${Build.VERSION.RELEASE} (${Build.MODEL})"

    /** Initialize the BLE node with the given config. Call once after binding. */
    fun initialize(
        identity: Identity,
        requestedPhyMode: PHYMode,
        allowlist: Set<String>,
        description: String = defaultDescription(),
    ) {
        this.identity = identity
        val nodeId = identity.nodeId
        // Reset all runtime state so the UI starts clean every time the service initialises.
        peers.clear()
        serverPeers.clear()
        serverPeerDevices.clear()
        _connectedPeers.value = emptyList()
        _receivedMessages.value = emptyList()
        _routingLog.value = emptyList()
        _neighborTable.value = emptyList()
        _knownTopology.value = emptyList()

        _nodeId.value = nodeId
        this.allowlist = allowlist.toMutableSet()

        router = Router(identity)
        router.description = description.ifBlank { defaultDescription() }
        router.allowlist.addAll(allowlist)
        reassembler = Reassembler(scope)

        bleManager = BLEManager(this, requestedPhyMode)
        bleManager.logCapabilities()
        _codedPhySupported.value = bleManager.isLeCodedPhySupported
        _bleMacAddress.value = bleManager.adapter.address ?: ""
        log(
            "BLE caps: codedPhy=${bleManager.isLeCodedPhySupported} " +
                "extAdv=${bleManager.isLeExtendedAdvertisingSupported} " +
                "multiAdv=${bleManager.isMultipleAdvertisementSupported} " +
                "maxAdvDataLen=${bleManager.adapter.leMaximumAdvertisingDataLength}",
            LogTag.SYS,
        )

        // Auto-fallback: if Coded PHY was requested but this hardware doesn't support it,
        // drop to 1m so advertising and scanning actually work.
        val effectivePhy = if (!bleManager.isLeCodedPhySupported && requestedPhyMode != PHYMode.DEBUG_1M) {
            log("WARNING: LE Coded PHY not supported — falling back to 1m", LogTag.PHY)
            PHYMode.DEBUG_1M
        } else {
            requestedPhyMode
        }
        _phyMode.value = effectivePhy
        _phyFallback.value = effectivePhy != requestedPhyMode

        val caps = ANDROID_CAPABILITIES
        val gattServer = bleManager.createGattServer(
            pubKey = identity.publicKey,
            caps = caps,
            description = router.description,
            onFrameReceived = { frame, device -> handleIncomingFrame(frame, device) },
            onDeviceConnected = { device ->
                val addrHex = device.address.replace(":", "")
                serverPeers[addrHex] = null // connected, NodeID not yet known
                serverPeerDevices[addrHex] = device
                log("device connected addr=${device.address} (NodeID unknown until first packet)", LogTag.SERVER)
            },
            onDeviceDisconnected = { device ->
                val addrHex = device.address.replace(":", "")
                val nodeId = serverPeers.remove(addrHex)
                serverPeerDevices.remove(addrHex)
                log("device disconnected addr=${device.address} node=${nodeId?.toHexString() ?: "unknown"}", LogTag.SERVER)
                updatePeersState()
            },
            onLog = { msg -> log(msg, LogTag.SERVER) },
        )
        gattServer.start()
        bleGattServer = gattServer

        bleAdvertiser = bleManager.createAdvertiser(nodeId)
        bleAdvertiser!!.startAdvertising(nodeId, effectivePhy) { msg ->
            log("advertiser: $msg", LogTag.SYS)
        }
        _advertisingActive.value = true

        bleScanner = bleManager.createScanner()
        log("scanner starting phyMode=$effectivePhy", LogTag.SCAN)
        bleScanner!!.startScan(
            phyMode = effectivePhy,
            onFound = { device, rssi, advNodeId -> handleFoundDevice(device, rssi, advNodeId) },
            onFailed = { errorCode -> log("scan FAILED errorCode=$errorCode", LogTag.SCAN) },
        )
        _scanningActive.value = true
        _isRunning.value = true

        // Periodic ANNOUNCE
        scope.launch {
            while (true) {
                delay(15_000)
                sendAnnounce()
            }
        }

        // Periodic UI refresh
        scope.launch {
            while (true) {
                delay(2_000)
                refreshTopologyState()
            }
        }

        log("node started id=${nodeId.toHexString()} requested-phy=$requestedPhyMode effective-phy=$effectivePhy codedPhy=${_codedPhySupported.value}", LogTag.SYS)
    }

    private fun handleFoundDevice(device: BluetoothDevice, rssi: Int, advNodeId: NodeID?) {
        val peerHex = device.address.replace(":", "")
        if (peers.containsKey(peerHex)) return // already connecting / connected to this MAC

        val localId = _nodeId.value

        // We learn the peer's NodeID directly from its advertisement. Discovery over
        // Coded PHY is often asymmetric (one side hears the other but not vice-versa),
        // so we do NOT gate on the NodeID ordering here: whoever discovers a peer simply
        // connects. If both sides happen to connect, the duplicate link is collapsed
        // deterministically (the larger NodeID drops its outgoing) in collapseDuplicateLink().
        if (advNodeId != null) {
            val advHex = advNodeId.toHexString()
            if (advHex == localId.toHexString()) return // our own advertisement
            // Already connected to this node on some other MAC (outgoing or incoming)?
            if (peers.values.any { it.peerId.toHexString() == advHex }) return
            if (serverPeers.values.any { it?.toHexString() == advHex }) return
            log("scan found node=$advHex addr=${device.address} rssi=$rssi — connecting", LogTag.SCAN)
        } else {
            log("scan found addr=${device.address} rssi=$rssi (no adv nodeId) — connecting", LogTag.SCAN)
        }

        val client = BLEEdgeGattClient(
            context = this,
            phyMode = _phyMode.value,
            initialRssi = rssi,
            onPhyUpdate = { txPhy, rxPhy ->
                updatePeerPhy(peerHex, txPhy, rxPhy)
            },
            onFrameReceived = { frame ->
                handleIncomingFrame(frame, device)
            },
            onDisconnected = {
                val link = peers.remove(peerHex)
                if (link != null) {
                    log("peer disconnected addr=$peerHex node=${link.peerId.toHexString()}", LogTag.PEER)
                    updatePeersState()
                }
            },
            onLog = { addr, msg ->
                log("gatt addr=$addr $msg", LogTag.GATT)
            },
            onNodeInfoRead = { peerId, caps ->
                val peerHex16 = peerId.toHexString()
                // Reject only a genuine duplicate of THIS same outgoing link (same NodeID
                // already connected out on a different MAC — e.g. address rotation).
                val dupOutgoing = peers.entries.any { (key, link) ->
                    key != peerHex && link.peerId.toHexString() == peerHex16
                }
                // If we ALSO have an inbound link from this peer and we are the larger
                // NodeID, the inbound link wins — drop this redundant outgoing one.
                val haveInbound = serverPeers.values.any { it?.toHexString() == peerHex16 }
                val keepNew: Boolean
                when {
                    dupOutgoing -> {
                        log("peer=$peerHex16 already connected outbound on another link; dropping addr=$peerHex", LogTag.PEER)
                        peers.remove(peerHex)
                        keepNew = false
                    }
                    haveInbound && !localId.isLessThan(peerId) -> {
                        log("peer=$peerHex16 already inbound and we are larger — keeping inbound, dropping outgoing", LogTag.PEER)
                        peers.remove(peerHex)
                        keepNew = false
                    }
                    else -> {
                        log("connected to peer=$peerHex16 caps=$caps", LogTag.PEER)
                        // Send an announce immediately so the server side learns our NodeID
                        // without waiting for the 15-second periodic cycle.
                        scope.launch { sendAnnounce() }
                        updatePeersState()
                        keepNew = true
                    }
                }
                keepNew
            },
        )
        val link = BLEPeerLink(client)

        // Add to map before connecting so duplicate scan results for the same MAC
        // are suppressed. UI is NOT updated here — peer only appears once NodeID
        // is known (inside onNodeInfoRead below).
        peers[peerHex] = link

        scope.launch(Dispatchers.Main) {
            client.connect(device)
        }
    }

    private fun handleIncomingFrame(frame: ByteArray, device: BluetoothDevice) {
        if (!_isRunning.value) return
        val f = runCatching { cz.arnal.bleedge.core.Frame.decode(frame) }.getOrElse {
            log("decode frame error: ${it.message}")
            return
        }
        val data = runCatching { reassembler.addFrame(f) }.getOrElse {
            log("reassemble error: ${it.message}")
            return
        } ?: return // incomplete

        var pkt = runCatching { Packet.decode(data) }.getOrElse {
            log("decode packet error: ${it.message}")
            return
        }

        log("rx id=${pkt.id.take(4).toHexString()} type=${pkt.type} src=${pkt.source.toHexString()} dst=${pkt.destination.toHexString()} ttl=${pkt.ttl} mode=${pkt.mode} trace=${pkt.trace.map { it.toHexString().take(8) }}", LogTag.ROUTER)

        // Register server-side peers from their first packet.
        // Also handles devices that connected before this session started
        // (onDeviceConnected was never called for them, so they're absent from serverPeers).
        val addrHex = device.address.replace(":", "")
        val isOutgoing = peers.containsKey(addrHex) ||
            peers.values.any { it.peerId.toHexString() == pkt.source.toHexString() }
        if (!isOutgoing && !pkt.source.isBroadcast()) {
            if (!serverPeers.containsKey(addrHex)) {
                // Device connected before our session — register it now.
                serverPeers[addrHex] = pkt.source
                serverPeerDevices[addrHex] = device
                log("late-registered server peer addr=${device.address} node=${pkt.source.toHexString()}", LogTag.SERVER)
                updatePeersState()
                collapseDuplicateLink(pkt.source)
            } else if (serverPeers[addrHex] == null) {
                serverPeers[addrHex] = pkt.source
                log("identified server peer addr=${device.address} node=${pkt.source.toHexString()}", LogTag.SERVER)
                updatePeersState()
                collapseDuplicateLink(pkt.source)
            }
        }

        // Identify the incoming peer's NodeID for loop prevention in the router
        val incomingPeer = peers.values.firstOrNull {
            it.peerId.toHexString() == pkt.source.toHexString()
        }?.peerId ?: serverPeers[addrHex]

        pkt = recordTraceMetric(pkt, incomingPeer)
        val actions = router.handlePacket(pkt, incomingPeer)
        executeActions(actions)
    }

    /**
     * When we end up with both an outgoing (client) link AND an inbound (server) link to
     * the same peer — which can happen if both sides discover each other — collapse them
     * to one. The node with the larger NodeID drops its outgoing client link and relies on
     * the inbound one, so both sides deterministically converge on the same single link.
     */
    private fun collapseDuplicateLink(peerId: NodeID) {
        val hex = peerId.toHexString()
        val hasInbound = serverPeers.values.any { it?.toHexString() == hex }
        val outEntry = peers.entries.firstOrNull { it.value.peerId.toHexString() == hex }
        if (hasInbound && outEntry != null && !_nodeId.value.isLessThan(peerId)) {
            log("collapsing duplicate link to peer=$hex — dropping our outgoing (we are larger)", LogTag.PEER)
            outEntry.value.disconnect()
            peers.remove(outEntry.key)
            updatePeersState()
        }
    }

    private fun executeActions(actions: List<Action>) {
        for (action in actions) {
            when (action.type) {
                ActionType.DELIVER_LOCAL -> deliverLocal(action.packet)
                ActionType.RELAY_FLOOD   -> {
                    val jitter = router.floodJitterMs()
                    scope.launch {
                        delay(jitter)
                        relayFlood(action)
                    }
                }
                ActionType.RELAY_NEXT_HOP -> relayNextHop(action)
                ActionType.SEND_ACK       -> sendAck(action)
                ActionType.DROP           -> log("drop reason=${action.reason} src=${action.packet.source.toHexString()} id=${action.packet.id.take(4).toHexString()}", LogTag.ROUTER)
            }
        }
    }

    private fun deliverLocal(pkt: Packet) {
        if (pkt.type == PacketType.ACK) {
            log("ACK received from=${pkt.source.toHexString()} acks=${pkt.payload.take(4).toHexString()}", LogTag.MSG)
            appendMessage(
                ReceivedMessage(
                    fromNodeId = pkt.source,
                    payload = pkt.payload,
                    isAck = true,
                    trace = pkt.trace,
                    ackedId = pkt.payload.takeIf { it.isNotEmpty() },
                    packetId = pkt.id,
                )
            )
            return
        }
        if (pkt.type == PacketType.ANNOUNCE) {
            log("ANNOUNCE delivered locally from=${pkt.source.toHexString()}", LogTag.ROUTER)
            return
        }
        if (pkt.payloadType == PayloadType.TRACE_REQUEST) {
            returnTrace(pkt)
            return
        }
        val deliveredPkt = if (pkt.payloadType == PayloadType.TRACE_RESPONSE) {
            runCatching {
                val result = TraceResult.decode(pkt.payload).copy(
                    returnNodes = pkt.trace,
                    returnSamples = pkt.traceMetric,
                )
                pkt.copy(payload = result.encode())
            }.getOrDefault(pkt)
        } else {
            pkt
        }
        // Decode chat payloads to text. Encrypted DMs are opened with our identity;
        // an undecryptable envelope (not for us / corrupt) yields null text.
        val text: String? = when (deliveredPkt.payloadType) {
            PayloadType.CHAT_ENCRYPTED -> identity?.let { Crypto.openChat(deliveredPkt.payload, it) }
            PayloadType.CHAT_PLAIN, PayloadType.TEXT_TEST ->
                runCatching { String(deliveredPkt.payload, Charsets.UTF_8) }.getOrNull()
            else -> null
        }
        log("MSG delivered from=${deliveredPkt.source.toHexString()} type=${deliveredPkt.payloadType} len=${deliveredPkt.payload.size} hops=${deliveredPkt.trace.size} trace=${deliveredPkt.trace.map { it.toHexString().take(8) }}", LogTag.MSG)
        appendMessage(
            ReceivedMessage(
                fromNodeId = deliveredPkt.source,
                payload = deliveredPkt.payload,
                payloadType = deliveredPkt.payloadType,
                trace = deliveredPkt.trace,
                text = text,
                packetId = deliveredPkt.id,
            )
        )
    }

    private fun recordTraceMetric(pkt: Packet, incomingPeer: NodeID?): Packet {
        if (pkt.type != PacketType.DATA) return pkt
        if (pkt.payloadType != PayloadType.TRACE_REQUEST && pkt.payloadType != PayloadType.TRACE_RESPONSE) return pkt
        return pkt.copy(traceMetric = pkt.traceMetric + rssiForPeer(incomingPeer).coerceIn(-128, 127).toByte())
    }

    private fun rssiForPeer(id: NodeID?): Int {
        val hex = id?.toHexString() ?: return 0
        peers.values.firstOrNull { it.peerId.toHexString() == hex }?.let { return it.rssi }
        neighborTable.value.firstOrNull { it.nodeId.toHexString() == hex }?.let {
            return if (it.rssi == RSSI_UNKNOWN) 0 else it.rssi
        }
        return 0
    }

    private fun returnTrace(req: Packet) {
        val tracePayload = runCatching { decodeTracePayload(req.payload) }.getOrElse {
            log("trace response error: ${it.message}", LogTag.ROUTER)
            return
        }
        val result = TraceResult(
            tag = tracePayload.tag,
            authCode = tracePayload.authCode,
            route = req.route,
            forwardNodes = req.trace,
            forwardSamples = req.traceMetric,
            metric = TRACE_METRIC_RSSI,
        )
        val route = reverseTraceRoute(req.trace, req.source)
        val pkt = Packet(
            version = PROTOCOL_VERSION,
            type = PacketType.DATA,
            id = newPacketID(),
            source = _nodeId.value,
            destination = req.source,
            mode = RoutingMode.SOURCE_ROUTE,
            ttl = (route.size + 1).toByte(),
            route = route,
            payloadType = PayloadType.TRACE_RESPONSE,
            payload = result.encode(),
        )
        router.markOriginated(pkt.id)
        transmitToRoute(pkt)
    }

    private fun reverseTraceRoute(trace: List<NodeID>, dst: NodeID): List<NodeID> {
        val hops = if (trace.isNotEmpty()) trace.dropLast(1) else emptyList()
        val route = hops.reversed().toMutableList()
        if (route.lastOrNull()?.toHexString() != dst.toHexString()) route += dst
        return route
    }

    // Send frames to all connected peers (both outgoing and server-side), optionally
    // excluding one NodeID (used to avoid echoing back to the source of a flood).
    private fun sendFramesToAll(frames: List<cz.arnal.bleedge.core.Frame>, exclude: NodeID? = null) {
        val excludeHex = exclude?.toHexString()
        for ((_, link) in peers) {
            if (link.peerId.toHexString() == excludeHex) continue
            for (frame in frames) link.sendFrame(frame.encode())
        }
        val server = bleGattServer ?: return
        for ((addrHex, nodeId) in serverPeers) {
            if (nodeId == null) continue
            if (nodeId.toHexString() == excludeHex) continue
            val device = serverPeerDevices[addrHex] ?: continue
            for (frame in frames) server.notifyFrameTo(frame.encode(), device)
        }
    }

    // Send frames to a specific peer by NodeID, checking both outgoing and server-side maps.
    private fun sendFramesToPeer(frames: List<cz.arnal.bleedge.core.Frame>, nodeId: NodeID): Boolean {
        val nodeHex = nodeId.toHexString()
        val link = peers.values.firstOrNull { it.peerId.toHexString() == nodeHex }
        if (link != null) {
            for (frame in frames) link.sendFrame(frame.encode())
            return true
        }
        val server = bleGattServer ?: return false
        val entry = serverPeers.entries.firstOrNull { (_, nid) -> nid?.toHexString() == nodeHex }
        if (entry != null) {
            val device = serverPeerDevices[entry.key] ?: return false
            for (frame in frames) server.notifyFrameTo(frame.encode(), device)
            return true
        }
        return false
    }

    private fun relayFlood(action: Action) {
        val data = runCatching { action.packet.encode() }.getOrElse { return }
        val frames = fragmentPacket(data, 512, action.packet.id)
        sendFramesToAll(frames, exclude = action.nextHop)
    }

    private fun relayNextHop(action: Action) {
        val nh = action.nextHop ?: return
        val data = runCatching { action.packet.encode() }.getOrElse { return }
        val frames = fragmentPacket(data, 512, action.packet.id)
        if (!sendFramesToPeer(frames, nh)) {
            log("relay-next-hop: peer ${nh.toHexString()} not connected — dropping", LogTag.ROUTER)
        } else {
            log("relay-next-hop → ${nh.toHexString().take(8)} frames=${frames.size}", LogTag.ROUTER)
        }
    }

    private fun sendAck(action: Action) {
        val nh = action.nextHop ?: return
        val data = runCatching { action.packet.encode() }.getOrElse { return }
        val frames = fragmentPacket(data, 512, action.packet.id)
        if (!sendFramesToPeer(frames, nh)) {
            // Flood as fallback
            sendFramesToAll(frames)
        }
        log("send ACK to=${nh.toHexString().take(8)} route=${action.packet.route.map { it.toHexString().take(8) }}", LogTag.MSG)
    }

    private fun sendAnnounce() {
        announceSeq++
        val pkt = router.buildAnnounce(ANDROID_CAPABILITIES, announceSeq)
        // Record our own packet so a flood echo doesn't get re-flooded back out.
        router.markOriginated(pkt.id)
        val data = runCatching { pkt.encode() }.getOrElse { return }
        val frames = fragmentPacket(data, 512, pkt.id)
        val nOut = peers.size
        val nIn = serverPeers.count { it.value != null }
        log("ANNOUNCE seq=$announceSeq → $nOut outgoing + $nIn server peers frames=${frames.size}", LogTag.ROUTER)
        sendFramesToAll(frames)
    }

    /** Send a text message to destination (or broadcast if dst is zero). */
    fun sendMessage(text: String, destination: NodeID, ttl: Byte = 4) {
        sendData(text.toByteArray(Charsets.UTF_8), PayloadType.TEXT_TEST, destination, ttl)
    }

    /**
     * Sends a chat message. Direct messages (non-broadcast) are end-to-end encrypted to
     * the recipient's public key (learned from its ANNOUNCE); broadcast goes out as
     * plaintext on the public channel. Returns the originated packet id (16 bytes) so the
     * caller can match the later delivery ACK, or null if it couldn't be sent (e.g. an
     * encrypted DM to a node whose public key we haven't learned yet).
     */
    fun sendChat(text: String, destination: NodeID, recipientPub: ByteArray? = null, ttl: Byte = 4): ByteArray? {
        if (destination.isBroadcast()) {
            return sendData(text.toByteArray(Charsets.UTF_8), PayloadType.CHAT_PLAIN, destination, ttl)
        }
        val id = identity ?: return null
        // Prefer an explicitly supplied key (e.g. learned from a received DM envelope or a
        // saved contact); fall back to the key from the peer's signed ANNOUNCE (topology).
        val pub = recipientPub?.takeIf { it.size == 32 } ?: router.topology.getNode(destination)?.publicKey
        if (pub == null || pub.size != 32) {
            log("sendChat: no public key known for ${destination.toHexString()} — cannot encrypt", LogTag.MSG)
            return null
        }
        val envelope = runCatching { Crypto.sealChat(text, id, pub) }.getOrElse {
            log("sendChat: encryption failed: ${it.message}", LogTag.MSG)
            return null
        }
        return sendData(envelope, PayloadType.CHAT_ENCRYPTED, destination, ttl)
    }

    /**
     * Broadcasts a pre-built MeshCore-compatible channel payload (GRP_TXT, see
     * core.ChannelCrypto) to the whole mesh. The caller seals the message with the
     * channel PSK; the transport just floods it. Returns the packet id.
     */
    fun sendChannelMessage(payload: ByteArray, ttl: Byte = 4): ByteArray =
        sendData(payload, PayloadType.CHANNEL, NodeID.BROADCAST, ttl)

    /** Sends a source-routed trace request. Returns the trace tag, or null if no route is known. */
    fun sendTrace(destination: NodeID, explicitRoute: List<NodeID> = emptyList()): Int? {
        val route = if (explicitRoute.isNotEmpty()) {
            val r = explicitRoute.toMutableList()
            if (r.lastOrNull()?.toHexString() != destination.toHexString()) r += destination
            r
        } else {
            val (selected, found) = router.selectRoute(destination)
            if (!found) return null
            selected ?: listOf(destination)
        }
        val tag = randomTraceTag()
        val payload = encodeTracePayload(
            TracePayload(
                tag = tag,
                authCode = 0,
                flags = traceFlagsForHashWidth(TRACE_HASH_WIDTH_8),
                routeData = traceRouteData(route, TRACE_HASH_WIDTH_8),
            )
        )
        val pkt = Packet(
            version = PROTOCOL_VERSION,
            type = PacketType.DATA,
            id = newPacketID(),
            source = _nodeId.value,
            destination = destination,
            mode = RoutingMode.SOURCE_ROUTE,
            ttl = (route.size + 2).toByte(),
            route = route,
            routeCursor = 0,
            payloadType = PayloadType.TRACE_REQUEST,
            payload = payload,
        )
        router.markOriginated(pkt.id)
        transmitToRoute(pkt)
        return tag
    }

    /** Updates this node's diagnostic description and re-announces it immediately. */
    fun setDescription(description: String) {
        if (!::router.isInitialized) return
        router.description = description.ifBlank { defaultDescription() }
        log("description set to '${router.description}'", LogTag.SYS)
        scope.launch { sendAnnounce() }
    }

    /** Builds, originates and transmits a DATA packet. Returns the packet id. */
    private fun sendData(payload: ByteArray, payloadType: PayloadType, destination: NodeID, ttl: Byte): ByteArray {
        val (route, found) = router.selectRoute(destination)

        val pkt = if (!destination.isBroadcast() && found && route != null) {
            Packet(
                version = PROTOCOL_VERSION,
                type = PacketType.DATA,
                id = newPacketID(),
                source = _nodeId.value,
                destination = destination,
                mode = RoutingMode.SOURCE_ROUTE,
                ttl = ttl,
                route = route,
                routeCursor = 0,
                payloadType = payloadType,
                payload = payload,
            )
        } else {
            Packet(
                version = PROTOCOL_VERSION,
                type = PacketType.DATA,
                id = newPacketID(),
                source = _nodeId.value,
                destination = destination,
                mode = RoutingMode.FLOOD,
                ttl = ttl,
                payloadType = payloadType,
                payload = payload,
            )
        }

        // Record our own packet so a flood echo doesn't get re-flooded back out.
        router.markOriginated(pkt.id)
        val data = runCatching { pkt.encode() }.getOrElse { return pkt.id }
        val frames = fragmentPacket(data, 512, pkt.id)
        if (pkt.mode == RoutingMode.SOURCE_ROUTE) {
            val firstHop = pkt.route.firstOrNull()
            if (firstHop != null) sendFramesToPeer(frames, firstHop) else sendFramesToAll(frames)
        } else {
            sendFramesToAll(frames)
        }
        log("MSG sent dst=${destination.toHexString()} mode=${pkt.mode} type=$payloadType len=${payload.size}", LogTag.MSG)
        return pkt.id
    }

    private fun transmitToRoute(pkt: Packet) {
        val data = runCatching { pkt.encode() }.getOrElse { return }
        val frames = fragmentPacket(data, 512, pkt.id)
        val firstHop = pkt.route.firstOrNull()
        if (firstHop != null) {
            if (!sendFramesToPeer(frames, firstHop)) {
                log("trace/source-route: first hop ${firstHop.toHexString()} not connected — dropping", LogTag.ROUTER)
            }
        }
    }

    fun clearData() {
        // Recreate the router to wipe dedup cache, neighbor table, and topology.
        val id = identity ?: return
        router = Router(id)
        router.allowlist.addAll(allowlist)
        // Clear all displayed state.
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
        // Re-apply immediately if the radios are running, so the user doesn't have to
        // manually stop/start the service for the new PHY to take effect.
        if (_isRunning.value) {
            stopBLE()
            startBLE()
        }
    }

    fun stopBLE() {
        bleAdvertiser?.stopAdvertising()
        bleScanner?.stopScan()
        peers.values.forEach { it.disconnect() }
        peers.clear()
        serverPeers.clear()
        serverPeerDevices.clear()
        _connectedPeers.value = emptyList()
        _advertisingActive.value = false
        _scanningActive.value = false
        _isRunning.value = false
        log("BLE stopped", LogTag.SYS)
    }

    fun startBLE() {
        val nodeId = _nodeId.value
        val phyMode = _phyMode.value
        bleAdvertiser?.startAdvertising(nodeId, phyMode) { msg ->
            log("advertiser: $msg", LogTag.SYS)
        }
        _advertisingActive.value = true
        log("scanner starting phyMode=$phyMode", LogTag.SCAN)
        bleScanner?.startScan(
            phyMode = phyMode,
            onFound = { device, rssi, advNodeId -> handleFoundDevice(device, rssi, advNodeId) },
            onFailed = { errorCode -> log("scan FAILED errorCode=$errorCode", LogTag.SCAN) },
        )
        _scanningActive.value = true
        _isRunning.value = true
        log("BLE started", LogTag.SYS)
    }

    // ---- State helpers -------------------------------------------------------

    private fun updatePeerPhy(peerHex: String, txPhy: Int, rxPhy: Int) {
        val txP = PHY.fromAndroid(txPhy)
        val rxP = PHY.fromAndroid(rxPhy)
        log("peer=$peerHex phy-update tx=$txP rx=$rxP", LogTag.PHY)

        if (_phyMode.value == PHYMode.CODED_ONLY &&
            (txP != PHY.PHY_CODED || rxP != PHY.PHY_CODED)
        ) {
            log("WARNING: peer=$peerHex not on Coded PHY (coded-only mode) — marking INVALID", LogTag.PHY)
        }
        updatePeersState()
    }

    private fun updatePeersState() {
        val seenNodeIds = mutableSetOf<String>()
        val list = mutableListOf<PeerInfo>()

        // Outgoing connections (we are GattClient) — full info available.
        for (link in peers.values) {
            val nodeHex = link.peerId.toHexString()
            if (link.peerId.isBroadcast()) continue // NodeID not yet read
            if (seenNodeIds.add(nodeHex)) {
                val phyKnown = link.txPhy != PHY.UNKNOWN && link.rxPhy != PHY.UNKNOWN
                val phyInvalid = phyKnown && _phyMode.value == PHYMode.CODED_ONLY &&
                    (link.txPhy != PHY.PHY_CODED || link.rxPhy != PHY.PHY_CODED)
                // Keep the neighbor table populated from the rich client-side link info so
                // the Neighbors tab and route selection have real data to work with.
                router.neighbors.upsert(
                    cz.arnal.bleedge.core.NeighborEntry(
                        id = link.peerId,
                        rssi = link.rssi,
                        txPhy = link.txPhy,
                        rxPhy = link.rxPhy,
                        caps = link.caps,
                        phyInvalid = phyInvalid,
                        description = link.description,
                    )
                )
                list.add(PeerInfo(
                    nodeId = link.peerId,
                    rssi = link.rssi,
                    txPhy = link.txPhy,
                    rxPhy = link.rxPhy,
                    caps = link.caps,
                    phyInvalid = phyInvalid,
                    description = router.descriptionFor(link.peerId),
                ))
            }
        }

        // Incoming connections (peer is GattClient to our GattServer). The server side
        // can't read NODE_INFO or RSSI, so caps come from the peer's ANNOUNCE (topology)
        // and RSSI is reported as unknown (Int.MIN_VALUE → shown as "n/a" in the UI).
        for ((_, nodeId) in serverPeers) {
            if (nodeId == null || nodeId.isBroadcast()) continue // NodeID not yet known
            val nodeHex = nodeId.toHexString()
            if (!seenNodeIds.add(nodeHex)) continue // already listed via outgoing connection
            val nb = router.neighbors.get(nodeId)
            val topoCaps = router.topology.allNodes()
                .firstOrNull { it.id.toHexString() == nodeHex }?.caps
            list.add(PeerInfo(
                nodeId = nodeId,
                rssi = nb?.rssi ?: RSSI_UNKNOWN,
                txPhy = nb?.txPhy ?: PHY.UNKNOWN,
                rxPhy = nb?.rxPhy ?: PHY.UNKNOWN,
                caps = nb?.caps ?: topoCaps ?: Capabilities(0),
                phyInvalid = false,
                incoming = true,
                description = router.descriptionFor(nodeId),
            ))
        }

        // Show only the list once we have at least one known node (suppresses transient zeros)
        _connectedPeers.value = list.filter { !it.nodeId.isBroadcast() }
    }

    private fun refreshTopologyState() {
        _neighborTable.value = router.neighbors.all().map { n ->
            NeighborEntry(n.id, n.rssi, n.txPhy, n.rxPhy, n.caps, router.descriptionFor(n.id))
        }
        _knownTopology.value = router.topology.allNodes().map { tn ->
            TopologyEntry(tn.id, tn.caps, tn.neighbors, tn.lastSeenMs, tn.description, tn.publicKey)
        }
        // Refresh peer RSSI values which are updated by the GattClient keepalive
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
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                "BLEEdge",
                NotificationManager.IMPORTANCE_LOW,
            )
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("BLEEdge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
}

// ---- Extensions -------------------------------------------------------------

private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
private fun List<Byte>.toHexString() = toByteArray().toHexString()
private fun ByteArray.take(n: Int) = copyOfRange(0, minOf(n, size))
