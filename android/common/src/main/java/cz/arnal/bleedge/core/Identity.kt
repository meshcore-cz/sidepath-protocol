package cz.arnal.bleedge.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import org.bouncycastle.math.ec.rfc8032.Ed25519

/**
 * Ed25519 node identity (RFC 8032), MeshCore-compatible and wire-identical to
 * the Go `core.Identity` and the ESP32 orlp/ed25519. The keypair is derived
 * deterministically from a 32-byte seed, so the same seed yields the same
 * public key and signatures on every platform.
 *
 * NodeID (the 8-byte routing address) = the first 8 bytes of the public key.
 */
class Identity private constructor(
    val seed: ByteArray,   // 32 bytes
    val publicKey: ByteArray, // 32 bytes
) {
    val nodeId: NodeID get() = NodeID.fromPubKey(publicKey)

    /** Signs the canonical announce message (see [announceSignedMessage]). */
    fun signAnnounce(timestamp: Int, caps: Capabilities, seq: Int, neighbors: List<NodeID>): ByteArray {
        val msg = announceSignedMessage(publicKey, timestamp, caps, seq, neighbors)
        val sig = ByteArray(Ed25519.SIGNATURE_SIZE)
        Ed25519.sign(seed, 0, msg, 0, msg.size, sig, 0)
        return sig
    }

    companion object {
        fun generate(): Identity = fromSeed(ByteArray(SEED_SIZE).also { SecureRandom().nextBytes(it) })

        fun fromSeed(seed: ByteArray): Identity {
            require(seed.size == SEED_SIZE) { "seed must be $SEED_SIZE bytes" }
            val pub = ByteArray(Ed25519.PUBLIC_KEY_SIZE)
            Ed25519.generatePublicKey(seed, 0, pub, 0)
            return Identity(seed.copyOf(), pub)
        }

        /**
         * Builds the canonical byte string that an ANNOUNCE signature covers.
         * FIXED explicit layout (NOT the CBOR — CBOR is not byte-stable across
         * libraries). Must match core.AnnounceSignedMessage (Go) and
         * mesh::announceSignedMessage (firmware):
         *
         *   pubkey[32] | timestamp[4 LE] | caps[1] | seq[4 LE] | count[1] | neighbors[count*8]
         */
        fun announceSignedMessage(
            pub: ByteArray,
            timestamp: Int,
            caps: Capabilities,
            seq: Int,
            neighbors: List<NodeID>,
        ): ByteArray {
            val buf = ByteBuffer
                .allocate(32 + 4 + 1 + 4 + 1 + neighbors.size * 8)
                .order(ByteOrder.LITTLE_ENDIAN)
            buf.put(pub)
            buf.putInt(timestamp)
            buf.put(caps.value.toByte())
            buf.putInt(seq)
            buf.put(neighbors.size.toByte())
            neighbors.forEach { buf.put(it.bytes) }
            return buf.array()
        }

        /** Verifies an announce signature against the carried public key. */
        fun verifyAnnounce(
            pub: ByteArray,
            sig: ByteArray,
            timestamp: Int,
            caps: Capabilities,
            seq: Int,
            neighbors: List<NodeID>,
        ): Boolean {
            if (pub.size != Ed25519.PUBLIC_KEY_SIZE || sig.size != Ed25519.SIGNATURE_SIZE) return false
            val msg = announceSignedMessage(pub, timestamp, caps, seq, neighbors)
            return Ed25519.verify(sig, 0, pub, 0, msg, 0, msg.size)
        }
    }
}
