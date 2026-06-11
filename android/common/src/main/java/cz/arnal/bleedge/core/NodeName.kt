package cz.arnal.bleedge.core

import java.security.MessageDigest

// Deterministic "bootstrap" node names. A node's name is a real wire field
// (AnnouncePayload key 9 / NODE_INFO), user-overridable, but a fresh identity gets a
// default derived purely from its public key — so a node always has a friendly, stable
// label and peers can show the same default until they receive the on-wire name (e.g.
// the ESP32 relay sends an empty name on purpose and everyone derives this).
//
// The algorithm MUST match the Go (core/nodename.go) and C++ ports. Input is MD5 of the
// lowercase hex of the 32-byte public key. Specified in docs/PROTOCOL.md.
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

/**
 * Deterministic, varied three-token bootstrap name derived from a 32-byte Ed25519 public
 * key (e.g. "barrel-two-return", "five-meadow-one", "cedar-pine-eight"). Returns "" if
 * [publicKey] is not 32 bytes.
 */
fun defaultNodeName(publicKey: ByteArray): String {
    if (publicKey.size != 32) return ""
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
