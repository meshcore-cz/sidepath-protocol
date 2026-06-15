package cz.meshcore.sidepath.meshcore

import cz.meshcore.sidepath.protocol.NodeId
import org.json.JSONArray
import org.json.JSONObject

/**
 * Decoded outer fields of a raw MeshCore over-the-air packet, as produced by
 * meshpkt's `decodeEnvelope` op (see meshpkt/ops.go). The Sidepath mesh carries
 * these packets opaquely under `PayloadProtocol.MESHCORE_PACKET`; this is the
 * structure we recover once we look inside.
 */
data class MeshCoreEnvelope(
    val route: String,
    val routeCode: Int,
    val type: String,        // e.g. "GRP_TXT", "ADVERT", "TXT_MSG", "ACK", "TRACE"
    val typeCode: Int,
    val version: Int,
    val pathHashSize: Int,
    val hopCount: Int,
    val hops: List<ByteArray>,
    val payload: ByteArray,
    val isTransport: Boolean,
    val transportCodes: IntArray?,
) {
    val isGroupText: Boolean get() = type == MeshCoreType.GRP_TXT
}

/** MeshCore payload-type name strings emitted by meshpkt's `PayloadType.String()`. */
object MeshCoreType {
    const val REQ = "REQ"
    const val RESPONSE = "RESPONSE"
    const val TXT_MSG = "TXT_MSG"
    const val ACK = "ACK"
    const val ADVERT = "ADVERT"
    const val GRP_TXT = "GRP_TXT"
    const val GRP_DATA = "GRP_DATA"
    const val ANON_REQ = "ANON_REQ"
    const val PATH = "PATH"
    const val TRACE = "TRACE"
    const val MULTIPART = "MULTIPART"
    const val CONTROL = "CONTROL"
    const val RAW_CUSTOM = "RAW_CUSTOM"
}

/**
 * Decoded MeshCore ADVERT (node advertisement) payload — all the properties meshpkt's
 * `decodeAdvert` op exposes (§ advert.go). [timestampSec] is the advert's own broadcast time
 * (epoch seconds). [nodeType]: 0=unknown 1=chat 2=repeater 3=room 4=sensor.
 *
 * [networkCode] is not part of the MeshCore advert itself — it's attached by the receiving service
 * from the signed `bridges` of the Sidepath carrier that bridged this advert (§8.3), so each bridged
 * advert carries which MeshCore network it came through. Blank when the carrier advertises no network.
 */
data class MeshCoreAdvert(
    val publicKeyHex: String,
    val timestampSec: Long,
    val name: String,
    val nodeType: Int,
    val hasGps: Boolean,
    val lat: Double,
    val lon: Double,
    val sigVerified: Boolean,
    val networkCode: String = "",
)

/** Decoded MeshCore direct text payload, as returned by meshpkt's direct TXT_MSG ops. */
data class MeshCoreDirectText(
    val timestampSec: Long,
    val attempt: Int,
    val text: String,
)

/**
 * A MeshCore packet observed on the Sidepath mesh, captured for the MeshCore Rx Log.
 * Combines the decoded MeshCore [envelope] with the Sidepath carrier metadata (who
 * relayed it, the datagram id/path) and, when a joined channel matched, the
 * decrypted GRP_TXT [channelSender]/[channelText].
 */
data class MeshCorePacket(
    val timestampMs: Long,
    val source: NodeId,        // Sidepath originator of the carrier datagram
    val datagramId: ByteArray,
    val path: List<NodeId>,
    val directRssi: Int,
    val raw: ByteArray,        // inner MeshCore packet bytes
    val envelope: MeshCoreEnvelope?,
    val contentId: String = "", // short hash of the inner packet bytes (dedup identity)
    val channelSender: String? = null,
    val channelText: String? = null,
    val receiveCount: Int = 1,
    // The external network the bridge embedded in the carrier frame (SPMC, §13.1), or "" when the
    // packet was bridged untagged. Unsigned carrier tag — informational; not authenticated.
    val networkCode: String = "",
)

/**
 * Thin wrapper over the meshpkt gomobile binding. All JNI lives behind
 * [decodeEnvelope]; the JSON→model mapping in [parseEnvelopeJson] is pure so it can
 * be exercised in host JVM unit tests (the native `.so` cannot load off-device).
 */
object MeshCoreCodec {
    /** Decodes the outer envelope of a raw MeshCore packet, or null if undecodable. */
    fun decodeEnvelope(raw: ByteArray): MeshCoreEnvelope? = runCatching {
        val args = JSONArray().put(raw.toHex())
        val json = cz.meshcore.meshpkt.mobile.Mobile.call("decodeEnvelope", args.toString())
        parseEnvelopeJson(json)
    }.getOrNull()

    /**
     * Computes the CoreScope-compatible MeshCore content hash of a raw OTA packet — a
     * route-independent logical packet identifier (16 lowercase hex chars), via meshpkt's
     * `computeContentHash` op. Returns null if the packet can't be decoded.
     */
    fun computeContentHash(raw: ByteArray): String? = runCatching {
        val args = JSONArray().put(raw.toHex())
        val json = cz.meshcore.meshpkt.mobile.Mobile.call("computeContentHash", args.toString())
        val o = JSONObject(json)
        if (o.has("error")) return null
        o.optString("hash", "").takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Decodes an ADVERT payload (e.g. an envelope's payload when type==ADVERT), or null. */
    fun decodeAdvert(payload: ByteArray): MeshCoreAdvert? = runCatching {
        val args = JSONArray().put(payload.toHex())
        val json = cz.meshcore.meshpkt.mobile.Mobile.call("decodeAdvert", args.toString())
        parseAdvertJson(json)
    }.getOrNull()

    /**
     * Builds the MeshCore ACK packet a recipient returns for a received TXT_MSG, via meshpkt's
     * `encodeTextAck` op (CRC = SHA-256(ts|attempt&3|text|senderPub)[:4], FLOOD-routed ACK packet).
     * Returns the raw OTA packet bytes, or null on error.
     */
    fun encodeTextAck(timestampSec: Long, attempt: Int, text: String, senderPub: ByteArray): ByteArray? = runCatching {
        val args = JSONArray()
            .put(timestampSec)
            .put(attempt)
            .put(text)
            .put(senderPub.toHex())
        val json = cz.meshcore.meshpkt.mobile.Mobile.call("encodeTextAck", args.toString())
        val o = JSONObject(json)
        if (o.has("error")) return null
        o.optString("hex", "").takeIf { it.isNotEmpty() }?.hexToBytes()
    }.getOrNull()

    /**
     * Builds a MeshCore TXT_MSG (direct message) OTA packet addressed to [peerPub], encrypted with
     * the firmware-compatible shared secret derived from our identity [seed] (meshpkt op
     * `encodeDirectTextIdentity`). Returns the raw OTA packet bytes, or null on error.
     */
    fun encodeDirectText(seed: ByteArray, peerPub: ByteArray, text: String, timestampSec: Long, attempt: Int): ByteArray? = runCatching {
        val args = JSONArray()
            .put(seed.toHex())
            .put(peerPub.toHex())
            .put(text)
            .put(timestampSec)
            .put(attempt)
        val json = cz.meshcore.meshpkt.mobile.Mobile.call("encodeDirectTextIdentity", args.toString())
        val o = JSONObject(json)
        if (o.has("error")) return null
        o.optString("hex", "").takeIf { it.isNotEmpty() }?.hexToBytes()
    }.getOrNull()

    /**
     * Decrypts a MeshCore TXT_MSG [txtPayload] addressed to us, using our identity [seed] and the
     * sender's 32-byte Ed25519 public key [senderPub], via meshpkt's `decodeDirectTextIdentity` op.
     * Returns null when [senderPub] is not the real sender (MAC fails) or the shape is wrong.
     */
    fun decodeDirectTextIdentity(seed: ByteArray, senderPub: ByteArray, txtPayload: ByteArray): MeshCoreDirectText? = runCatching {
        val args = JSONArray().put(txtPayload.toHex()).put(seed.toHex()).put(senderPub.toHex())
        val json = cz.meshcore.meshpkt.mobile.Mobile.call("decodeDirectTextIdentity", args.toString())
        parseDirectTextJson(json)
    }.getOrNull()

    /**
     * Extracts the embedded ACK CRC from a MeshCore PATH return [pathPayload] addressed to us, sent
     * by [senderPub] in reply to a FLOOD-routed TXT_MSG we sent (meshpkt's `decodePathIdentity` op).
     * Returns the 4-byte ack CRC (little-endian, as a Long) when the PATH carries an ACK, else null.
     */
    fun decodePathAckCrc(seed: ByteArray, senderPub: ByteArray, pathPayload: ByteArray): Long? = runCatching {
        val args = JSONArray().put(pathPayload.toHex()).put(seed.toHex()).put(senderPub.toHex())
        val json = cz.meshcore.meshpkt.mobile.Mobile.call("decodePathIdentity", args.toString())
        parsePathAckCrcJson(json)
    }.getOrNull()

    /**
     * Computes the ACK CRC a recipient returns for a TXT_MSG we send (meshpkt's `textAckCrc` op:
     * SHA-256(ts|attempt&3|text|senderPub)[:4] as a little-endian uint32). [senderPub] is OUR public
     * key (we are the sender). Used to correlate the returning ACK with the sent message.
     */
    fun textAckCrc(timestampSec: Long, attempt: Int, text: String, senderPub: ByteArray): Long? = runCatching {
        val args = JSONArray().put(timestampSec).put(attempt).put(text).put(senderPub.toHex())
        val json = cz.meshcore.meshpkt.mobile.Mobile.call("textAckCrc", args.toString())
        val o = JSONObject(json)
        if (o.has("error")) return null
        o.optLong("crc", -1L).takeIf { it >= 0 }
    }.getOrNull()

    /** Pure JSON→[MeshCoreDirectText] mapping (host-testable). Returns null on error/bad shape. */
    fun parseDirectTextJson(json: String): MeshCoreDirectText? = runCatching {
        val o = JSONObject(json)
        if (o.has("error") || !o.has("text")) return null
        MeshCoreDirectText(
            timestampSec = o.optLong("timestamp", 0L),
            attempt = o.optInt("attempt", 0),
            text = o.optString("text", ""),
        )
    }.getOrNull()

    /**
     * Pure JSON→ack-CRC mapping for a decoded PATH return (host-testable). Returns the embedded ACK
     * CRC only when the PATH's extra payload is a MeshCore ACK (extraType == 3), else null.
     */
    fun parsePathAckCrcJson(json: String): Long? = runCatching {
        val o = JSONObject(json)
        if (o.has("error") || o.optInt("extraType", -1) != PAYLOAD_TYPE_ACK) return null
        o.optLong("ackCrc", -1L).takeIf { it >= 0 }
    }.getOrNull()

    private const val PAYLOAD_TYPE_ACK = 3 // MeshCore PAYLOAD_TYPE_ACK, used as a PATH-return extra_type

    /** Pure JSON→[MeshCoreAdvert] mapping (host-testable). Returns null on error/bad shape. */
    fun parseAdvertJson(json: String): MeshCoreAdvert? = runCatching {
        val o = JSONObject(json)
        if (o.has("error")) return null
        val pub = o.optString("publicKey", "")
        if (pub.isEmpty()) return null
        MeshCoreAdvert(
            publicKeyHex = pub,
            timestampSec = o.optLong("timestamp", 0L),
            name = o.optString("name", ""),
            nodeType = o.optInt("nodeType", 0),
            hasGps = o.optBoolean("hasGPS", false),
            lat = o.optDouble("lat", 0.0),
            lon = o.optDouble("lon", 0.0),
            sigVerified = o.optBoolean("sigVerified", false),
        )
    }.getOrNull()

    /** Pure JSON→[MeshCoreEnvelope] mapping. Returns null on an error result or bad shape. */
    fun parseEnvelopeJson(json: String): MeshCoreEnvelope? = runCatching {
        val o = JSONObject(json)
        if (o.has("error")) return null
        val hopsArr = o.optJSONArray("hops")
        val hops = buildList {
            if (hopsArr != null) for (i in 0 until hopsArr.length()) add(hopsArr.getString(i).hexToBytes())
        }
        val transport = if (o.optBoolean("isTransport", false)) {
            o.optJSONArray("transportCodes")?.let { if (it.length() >= 2) intArrayOf(it.getInt(0), it.getInt(1)) else null }
        } else null
        MeshCoreEnvelope(
            route = o.optString("route", ""),
            routeCode = o.optInt("routeCode", -1),
            type = o.optString("type", ""),
            typeCode = o.optInt("typeCode", -1),
            version = o.optInt("version", 0),
            pathHashSize = o.optInt("pathHashSize", 0),
            hopCount = o.optInt("hopCount", 0),
            hops = hops,
            payload = o.optString("payloadHex", "").hexToBytes(),
            isTransport = o.optBoolean("isTransport", false),
            transportCodes = transport,
        )
    }.getOrNull()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

private fun String.hexToBytes(): ByteArray {
    if (length % 2 != 0) return ByteArray(0)
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
