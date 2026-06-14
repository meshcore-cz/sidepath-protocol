package cz.meshcore.sidepath.protocol

import com.upokecenter.cbor.CBORObject

/**
 * The canonical definition of a Meshcore Network, owned by `sidepath-protocol`. A network is keyed by
 * its short [code] (e.g. "CZ", the same code carried in a v2 ANNOUNCE [BridgeAd]) and bundles the
 * canonical LoRa radio params plus presentation metadata. Radio params are integer Hz to match the
 * wire format and avoid float drift. [geoJson] is the raw GeoJSON geometry (Polygon / MultiPolygon)
 * as a JSON string, or empty when the network has no mapped territory.
 *
 * Definitions ship bundled in the library resource `networks.json` ([NetworkDefs.builtins]) and may
 * be refreshed at runtime from a URL: download the JSON, [NetworkDefs.parse] + validate it, cache it,
 * and prefer the cached copy over the bundled resource.
 */
data class NetworkDef(
    val code: String,
    val name: String,
    val freqHz: Long,
    val bandwidthHz: Long,
    val sf: Int,
    val cr: Int,
    val analyzerUrls: List<String> = emptyList(),
    val mqtt: List<String> = emptyList(),
    val description: String = "",
    val geoJson: String = "",
)

object NetworkDefs {
    private const val RESOURCE = "/networks.json"

    /**
     * The network definitions bundled in this library at build time. Returns an empty list (never
     * throws) if the resource is missing or malformed, so a packaging slip can't crash a consumer.
     */
    fun builtins(): List<NetworkDef> = runCatching {
        val text = NetworkDefs::class.java.getResourceAsStream(RESOURCE)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: return emptyList()
        parse(text)
    }.getOrDefault(emptyList())

    /**
     * Parses a definitions JSON document (the same schema as the bundled resource) into validated
     * [NetworkDef]s. Entries with a missing/blank code or invalid radio params are skipped rather
     * than failing the whole document, so one bad record in a refreshed dataset can't wipe the rest.
     * Throws only if [json] is not valid JSON at all (callers should catch and fall back).
     */
    fun parse(json: String): List<NetworkDef> {
        val arr = CBORObject.FromJSONString(json)
        if (arr.type != com.upokecenter.cbor.CBORType.Array) return emptyList()
        return (0 until arr.size()).mapNotNull { i -> runCatching { parseOne(arr[i]) }.getOrNull() }
    }

    private fun parseOne(o: CBORObject): NetworkDef? {
        val code = o.stringOr("code").trim()
        if (code.isEmpty() || code.toByteArray(Charsets.UTF_8).size > Sidepath.MAX_NETWORK_CODE_BYTES) return null
        val def = NetworkDef(
            code = code,
            name = o.stringOr("name").ifEmpty { code },
            freqHz = o.longOr("freqHz"),
            bandwidthHz = o.longOr("bandwidthHz"),
            sf = o.intOr("sf"),
            cr = o.intOr("cr"),
            analyzerUrls = o.stringList("analyzerUrls"),
            mqtt = o.stringList("mqtt"),
            description = o.stringOr("description"),
            // Keep the geometry as a JSON string; consumers parse it lazily for offline rendering.
            geoJson = o["geoJson"]?.takeIf { !it.isNull }?.ToJSONString() ?: "",
        )
        return def
    }

    private fun CBORObject.stringOr(key: String): String =
        this[key]?.takeIf { !it.isNull && it.type == com.upokecenter.cbor.CBORType.TextString }?.AsString() ?: ""

    private fun CBORObject.longOr(key: String): Long =
        this[key]?.takeIf { !it.isNull && it.isNumber }?.AsInt64Value() ?: 0L

    private fun CBORObject.intOr(key: String): Int =
        this[key]?.takeIf { !it.isNull && it.isNumber }?.AsInt32() ?: 0

    private fun CBORObject.stringList(key: String): List<String> {
        val v = this[key] ?: return emptyList()
        if (v.isNull || v.type != com.upokecenter.cbor.CBORType.Array) return emptyList()
        return (0 until v.size()).mapNotNull { v[it]?.takeIf { e -> e.type == com.upokecenter.cbor.CBORType.TextString }?.AsString() }
    }
}
