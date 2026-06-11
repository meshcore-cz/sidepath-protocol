package cz.arnal.bleedge.core

import com.upokecenter.cbor.CBORObject
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.math.ec.rfc7748.X25519

/**
 * End-to-end encryption for direct chat messages.
 *
 * Each node's identity is an Ed25519 keypair (see [Identity]); peers learn each
 * other's 32-byte Ed25519 public key from the signed ANNOUNCE (stored on
 * [TopoNode.publicKey]). To encrypt we convert both keys to their birationally
 * equivalent X25519 (Curve25519) form — the standard libsodium
 * `crypto_sign_ed25519_*_to_curve25519` transform — do a static↔static X25519
 * ECDH, derive an AES-256 key with HKDF-SHA256, and seal with AES-256-GCM.
 *
 * Because the shared secret is symmetric (X25519(myPriv, theirPub) ==
 * X25519(theirPriv, myPub)), the recipient re-derives the same key from the
 * sender's public key carried in the envelope.
 */
object Crypto {
    private const val HKDF_INFO = "bleedge-chat-v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    // p = 2^255 - 19, the Curve25519 field prime.
    private val P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))

    /**
     * Converts an Ed25519 public key (32-byte compressed Edwards point) to the
     * equivalent X25519 (Montgomery) public key. u = (1 + y) / (1 - y) mod p.
     */
    fun ed25519PubToX25519(edPub: ByteArray): ByteArray {
        require(edPub.size == 32) { "ed25519 public key must be 32 bytes" }
        // Decode y: little-endian, top (sign) bit of the last byte is the x-sign and is cleared.
        val le = edPub.copyOf()
        le[31] = (le[31].toInt() and 0x7F).toByte()
        val y = leToBigInteger(le)
        val oneMinusY = BigInteger.ONE.subtract(y).mod(P)
        val onePlusY = BigInteger.ONE.add(y).mod(P)
        val u = onePlusY.multiply(oneMinusY.modInverse(P)).mod(P)
        return bigIntegerToLe(u)
    }

    /**
     * Derives the X25519 private scalar from an Ed25519 seed:
     * a = clamp(SHA-512(seed)[0..31]).
     */
    fun ed25519SeedToX25519Priv(seed: ByteArray): ByteArray {
        require(seed.size == SEED_SIZE) { "seed must be $SEED_SIZE bytes" }
        val h = MessageDigest.getInstance("SHA-512").digest(seed)
        val a = h.copyOfRange(0, 32)
        a[0] = (a[0].toInt() and 248).toByte()
        a[31] = (a[31].toInt() and 127).toByte()
        a[31] = (a[31].toInt() or 64).toByte()
        return a
    }

    /** X25519 ECDH shared secret (32 bytes). */
    private fun sharedSecret(myX25519Priv: ByteArray, theirX25519Pub: ByteArray): ByteArray {
        val out = ByteArray(X25519.POINT_SIZE)
        X25519.scalarMult(myX25519Priv, 0, theirX25519Pub, 0, out, 0)
        return out
    }

    private fun deriveKey(secret: ByteArray): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(secret, null, HKDF_INFO.toByteArray()))
        val key = ByteArray(32)
        gen.generateBytes(key, 0, key.size)
        return key
    }

    /**
     * Encrypts [plain] for the holder of [recipientEdPub] (their Ed25519 public key).
     * Returns a CBOR envelope { 1: senderEdPub(32), 2: iv(12), 3: ciphertext }.
     */
    fun sealChat(plain: String, sender: Identity, recipientEdPub: ByteArray): ByteArray {
        val myPriv = ed25519SeedToX25519Priv(sender.seed)
        val theirPub = ed25519PubToX25519(recipientEdPub)
        val key = deriveKey(sharedSecret(myPriv, theirPub))

        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))

        val map = CBORObject.NewOrderedMap()
        map[CBORObject.FromObject(1)] = CBORObject.FromObject(sender.publicKey)
        map[CBORObject.FromObject(2)] = CBORObject.FromObject(iv)
        map[CBORObject.FromObject(3)] = CBORObject.FromObject(ct)
        return map.EncodeToBytes()
    }

    /**
     * Decrypts a [sealChat] envelope addressed to [me]. Returns the plaintext, or
     * null if the envelope is malformed / not decryptable with this identity.
     */
    fun openChat(envelope: ByteArray, me: Identity): String? = runCatching {
        val map = CBORObject.DecodeFromBytes(envelope)
        val senderEdPub = map[CBORObject.FromObject(1)].GetByteString()
        val iv = map[CBORObject.FromObject(2)].GetByteString()
        val ct = map[CBORObject.FromObject(3)].GetByteString()

        val myPriv = ed25519SeedToX25519Priv(me.seed)
        val theirPub = ed25519PubToX25519(senderEdPub)
        val key = deriveKey(sharedSecret(myPriv, theirPub))

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    /** Returns the sender's Ed25519 public key carried in a [sealChat] envelope, or null. */
    fun envelopeSenderPubKey(envelope: ByteArray): ByteArray? = runCatching {
        CBORObject.DecodeFromBytes(envelope)[CBORObject.FromObject(1)].GetByteString()
    }.getOrNull()

    // ---- helpers -------------------------------------------------------------

    private fun leToBigInteger(le: ByteArray): BigInteger {
        val be = le.reversedArray()
        return BigInteger(1, be)
    }

    private fun bigIntegerToLe(v: BigInteger): ByteArray {
        val be = v.toByteArray() // big-endian, may have a leading sign byte or be short
        val out = ByteArray(32)
        // Copy the low 32 bytes (strip any leading sign byte) into a fixed 32-byte big-endian buffer.
        var copyLen = be.size
        var srcOff = 0
        if (copyLen > 32) { srcOff = copyLen - 32; copyLen = 32 }
        System.arraycopy(be, srcOff, out, 32 - copyLen, copyLen)
        return out.reversedArray() // little-endian
    }
}
