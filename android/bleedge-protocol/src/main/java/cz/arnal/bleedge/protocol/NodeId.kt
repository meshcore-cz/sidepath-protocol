package cz.arnal.bleedge.protocol

import java.security.SecureRandom

/**
 * NodeID is the compact 10-byte BLEEdge routing address, derived from the first
 * 10 bytes of an Ed25519 public key (§3). It is only a routing address — the
 * 32-byte public key is authoritative.
 *
 * Equality and hashing are value-based (by content), so `NodeId` can safely be
 * used in sets and maps and compared with `==` (unlike the v2 inline-class
 * NodeID whose equality was referential).
 */
class NodeId(val bytes: ByteArray) {
    init { require(bytes.size == BLEEdge.NODE_ID_BYTES) { "NodeID must be ${BLEEdge.NODE_ID_BYTES} bytes, got ${bytes.size}" } }

    fun toHex(): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun isBroadcast(): Boolean = bytes.all { it == 0.toByte() }

    /** Lexicographic unsigned byte comparison (§3). */
    operator fun compareTo(other: NodeId): Int {
        for (i in bytes.indices) {
            val a = bytes[i].toInt() and 0xFF
            val b = other.bytes[i].toInt() and 0xFF
            if (a != b) return a - b
        }
        return 0
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is NodeId && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = toHex()

    companion object {
        val BROADCAST = NodeId(ByteArray(BLEEdge.NODE_ID_BYTES))

        fun fromHex(hex: String): NodeId {
            require(hex.length == BLEEdge.NODE_ID_BYTES * 2) {
                "NodeID hex must be ${BLEEdge.NODE_ID_BYTES * 2} chars, got: $hex"
            }
            return NodeId(ByteArray(BLEEdge.NODE_ID_BYTES) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        }

        fun random(): NodeId =
            NodeId(ByteArray(BLEEdge.NODE_ID_BYTES).also { SecureRandom().nextBytes(it) })

        /** NodeID = public_key[0:10] (§3). */
        fun fromPublicKey(publicKey: ByteArray): NodeId {
            require(publicKey.size == BLEEdge.PUBLIC_KEY_BYTES) { "public key must be ${BLEEdge.PUBLIC_KEY_BYTES} bytes" }
            return NodeId(publicKey.copyOfRange(0, BLEEdge.NODE_ID_BYTES))
        }
    }
}
