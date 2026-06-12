package cz.arnal.bleedge.protocol

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519

/**
 * A BLEEdge node identity: an Ed25519 keypair (RFC 8032) derived deterministically
 * from a persisted 32-byte seed (§3). The same seed always yields the same public
 * key and signatures, so identity is portable across platforms.
 *
 * The identity also exposes its libsodium-compatible X25519 conversion, used by
 * payload protocols (e.g. BLEEdge Chat direct messages) for key agreement.
 */
class Identity private constructor(
    val seed: ByteArray,      // 32 bytes
    val publicKey: ByteArray, // 32 bytes Ed25519
) {
    val nodeId: NodeId get() = NodeId.fromPublicKey(publicKey)

    /** X25519 private scalar derived from this identity's Ed25519 seed. */
    fun x25519Private(): ByteArray = ed25519SeedToX25519Private(seed)

    /** X25519 ECDH shared secret with [peerEd25519Public] (their Ed25519 public key). */
    fun sharedSecretWith(peerEd25519Public: ByteArray): ByteArray {
        val theirX = ed25519PublicToX25519(peerEd25519Public)
        val out = ByteArray(X25519.POINT_SIZE)
        X25519.scalarMult(x25519Private(), 0, theirX, 0, out, 0)
        return out
    }

    /**
     * Signs an ANNOUNCE over the exact fixed byte layout (§8.3). [epoch], [seq],
     * [timestamp], [caps], and the metadata strings must be the same values that
     * will be placed in the announce body.
     */
    fun signAnnounce(
        epoch: Long,
        seq: Long,
        timestamp: Long,
        caps: Capabilities,
        neighbors: List<NodeId>,
        name: String,
        description: String,
        platform: String,
    ): ByteArray {
        val msg = announceSignedMessage(publicKey, epoch, seq, timestamp, caps, neighbors, name, description, platform)
        val sig = ByteArray(Ed25519.SIGNATURE_SIZE)
        Ed25519.sign(seed, 0, msg, 0, msg.size, sig, 0)
        return sig
    }

    companion object {
        fun generate(): Identity =
            fromSeed(ByteArray(BLEEdge.SEED_BYTES).also { SecureRandom().nextBytes(it) })

        fun fromSeed(seed: ByteArray): Identity {
            require(seed.size == BLEEdge.SEED_BYTES) { "seed must be ${BLEEdge.SEED_BYTES} bytes" }
            val pub = ByteArray(Ed25519.PUBLIC_KEY_SIZE)
            Ed25519.generatePublicKey(seed, 0, pub, 0)
            return Identity(seed.copyOf(), pub)
        }

        /**
         * Reconstructs the exact signed byte sequence for an ANNOUNCE (§8.3):
         *
         *   ascii("BLEEDGE-ANNOUNCE-V1\0") | announce_version[1] | public_key[32]
         *   | epoch[8 LE] | seq[4 LE] | timestamp[8 LE] | caps[2 LE]
         *   | neighbor_count[2 LE] | neighbors[count*10]
         *   | name_len[2 LE] | name | desc_len[2 LE] | desc | platform_len[2 LE] | platform
         */
        fun announceSignedMessage(
            publicKey: ByteArray,
            epoch: Long,
            seq: Long,
            timestamp: Long,
            caps: Capabilities,
            neighbors: List<NodeId>,
            name: String,
            description: String,
            platform: String,
        ): ByteArray {
            val nameB = name.toByteArray(Charsets.UTF_8)
            val descB = description.toByteArray(Charsets.UTF_8)
            val platB = platform.toByteArray(Charsets.UTF_8)
            val out = ByteArrayOutputStream()
            out.write(Wire.asciiNul("BLEEDGE-ANNOUNCE-V1"))
            out.write(BLEEdge.ANNOUNCE_VERSION)
            out.write(publicKey)
            out.write(Wire.le64(epoch))
            out.write(Wire.le32(seq))
            out.write(Wire.le64(timestamp))
            out.write(Wire.le16(caps.value))
            out.write(Wire.le16(neighbors.size))
            neighbors.forEach { out.write(it.bytes) }
            out.write(Wire.le16(nameB.size)); out.write(nameB)
            out.write(Wire.le16(descB.size)); out.write(descB)
            out.write(Wire.le16(platB.size)); out.write(platB)
            return out.toByteArray()
        }

        /** Verifies an announce signature against the carried public key and metadata. */
        fun verifyAnnounce(
            publicKey: ByteArray,
            signature: ByteArray,
            epoch: Long,
            seq: Long,
            timestamp: Long,
            caps: Capabilities,
            neighbors: List<NodeId>,
            name: String,
            description: String,
            platform: String,
        ): Boolean {
            if (publicKey.size != Ed25519.PUBLIC_KEY_SIZE || signature.size != Ed25519.SIGNATURE_SIZE) return false
            val msg = announceSignedMessage(publicKey, epoch, seq, timestamp, caps, neighbors, name, description, platform)
            return runCatching { Ed25519.verify(signature, 0, publicKey, 0, msg, 0, msg.size) }.getOrDefault(false)
        }

        // ---- Ed25519 -> X25519 (libsodium-compatible) -----------------------------

        // Curve25519 field prime p = 2^255 - 19.
        private val P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))

        /** a = clamp(SHA-512(seed)[0:32]) — the X25519 private scalar for an Ed25519 seed. */
        fun ed25519SeedToX25519Private(seed: ByteArray): ByteArray {
            require(seed.size == BLEEdge.SEED_BYTES) { "seed must be ${BLEEdge.SEED_BYTES} bytes" }
            val h = MessageDigest.getInstance("SHA-512").digest(seed)
            val a = h.copyOfRange(0, 32)
            a[0] = (a[0].toInt() and 248).toByte()
            a[31] = (a[31].toInt() and 127).toByte()
            a[31] = (a[31].toInt() or 64).toByte()
            return a
        }

        /** Converts an Ed25519 public key to its Montgomery X25519 form: u = (1+y)/(1-y) mod p. */
        fun ed25519PublicToX25519(edPub: ByteArray): ByteArray {
            require(edPub.size == 32) { "ed25519 public key must be 32 bytes" }
            val le = edPub.copyOf()
            le[31] = (le[31].toInt() and 0x7F).toByte() // clear sign bit
            val y = leToBig(le)
            val oneMinusY = BigInteger.ONE.subtract(y).mod(P)
            require(oneMinusY.signum() != 0) { "invalid ed25519 public key (y == 1)" }
            val u = BigInteger.ONE.add(y).mod(P).multiply(oneMinusY.modInverse(P)).mod(P)
            return bigToLe(u)
        }

        private fun leToBig(le: ByteArray): BigInteger = BigInteger(1, le.reversedArray())

        private fun bigToLe(v: BigInteger): ByteArray {
            val be = v.toByteArray()
            val out = ByteArray(32)
            var copyLen = be.size
            var srcOff = 0
            if (copyLen > 32) { srcOff = copyLen - 32; copyLen = 32 }
            System.arraycopy(be, srcOff, out, 32 - copyLen, copyLen)
            return out.reversedArray()
        }
    }
}
