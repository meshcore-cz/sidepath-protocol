package cz.arnal.bleedge.protocol

import java.security.MessageDigest

/**
 * Deterministic human-readable fallback name derived purely from a node's
 * 32-byte Ed25519 public key (§3.1). This is a UI fallback only — never used for
 * routing, authorization, or trust. A user-configured name distributed through a
 * signed ANNOUNCE takes precedence.
 *
 * Algorithm: MD5 of the lowercase ASCII hex of the public key, indexed into word
 * tables to produce a varied three-token name. Must match other platforms.
 */
private val nameWords = listOf(
    "barrel", "cedar", "ember", "harbor", "lantern", "meadow", "pebble", "quartz",
    "ripple", "summit", "thistle", "willow", "anchor", "brook", "copper", "dune",
    "fable", "garnet", "hollow", "ivy", "juniper", "kettle", "lichen", "marble",
    "nectar", "orchard", "pine", "raven", "sparrow", "timber", "velvet", "walnut",
    "amber", "birch", "comet", "delta", "echo", "ferry", "glade", "heron",
    "indigo", "jasper", "kelp", "lotus", "moss", "north", "opal", "prairie",
    "return", "saffron", "tundra", "umber", "violet", "wander", "yarrow", "zephyr",
    "basalt", "cinder", "dapple", "fennel", "gully", "hazel", "isle", "kestrel",
)

private val numberWords = listOf(
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
)

/** Deterministic three-token fallback name, or "" if [publicKey] is not 32 bytes. */
fun defaultNodeName(publicKey: ByteArray): String {
    if (publicKey.size != BLEEdge.PUBLIC_KEY_BYTES) return ""
    return defaultNodeNameFromHex(publicKey.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
}

/** As [defaultNodeName] but from a hex string (returns "" unless it's a full 64-char key). */
fun defaultNodeNameFromHex(pubKeyHex: String): String {
    if (pubKeyHex.length < 64) return ""
    val d = MessageDigest.getInstance("MD5").digest(pubKeyHex.lowercase().toByteArray(Charsets.US_ASCII))
    val w0 = nameWords[(d[0].toInt() and 0xFF) % nameWords.size]
    val num = numberWords[(d[1].toInt() and 0xFF) % numberWords.size]
    val w2 = nameWords[(d[2].toInt() and 0xFF) % nameWords.size]
    return when ((d[3].toInt() and 0xFF) % 3) {
        1 -> {
            val num2 = numberWords[(d[4].toInt() and 0xFF) % numberWords.size]
            "$num-$w0-$num2"
        }
        2 -> "$w0-$w2-$num"
        else -> "$w0-$num-$w2"
    }
}
