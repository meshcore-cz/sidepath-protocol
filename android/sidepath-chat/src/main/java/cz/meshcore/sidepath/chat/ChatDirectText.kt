package cz.meshcore.sidepath.chat

import com.upokecenter.cbor.CBORObject
import cz.meshcore.sidepath.protocol.Identity
import cz.meshcore.sidepath.protocol.NodeId
import cz.meshcore.sidepath.protocol.Wire
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/**
 * Encrypted direct text messages (§5): authenticated pairwise AES-256-GCM between
 * sender and recipient using converted static identity keys. Body: 1
 * sender_public_key, 2 nonce(12), 3 ciphertext (16-byte GCM tag appended).
 */
object ChatDirectText {
    private const val GCM_TAG_BITS = 128

    data class Opened(val text: String, val sentAt: Long, val senderPublicKey: ByteArray)

    /** Seals a DIRECT_TEXT payload for the holder of [recipientPublicKey] (their Ed25519 key). */
    fun seal(
        sender: Identity,
        recipientPublicKey: ByteArray,
        ctx: ChatContext,
        text: String,
        sentAt: Long,
    ): ByteArray {
        require(Chat.textWithinLimit(text)) { "text exceeds ${Chat.MAX_TEXT_BYTES} bytes" }
        val key = pairwiseKey(sender.sharedSecretWith(recipientPublicKey), sender.publicKey, recipientPublicKey)
        val nonce = ByteArray(Chat.DIRECT_NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val aad = aad(ctx, sender.publicKey)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintextCbor(sentAt, text))

        val body = CBORObject.NewOrderedMap()
        body[CBORObject.FromObject(1)] = CBORObject.FromObject(sender.publicKey)
        body[CBORObject.FromObject(2)] = CBORObject.FromObject(nonce)
        body[CBORObject.FromObject(3)] = CBORObject.FromObject(ct)
        return ChatEnvelope(Chat.VERSION, ChatKind.DIRECT_TEXT, body).encode()
    }

    /** Decrypts and authenticates a DIRECT_TEXT payload addressed to [recipient] (§5.9). */
    fun open(recipient: Identity, payload: ByteArray, ctx: ChatContext): Opened? = runCatching {
        if (ctx.destination != recipient.nodeId) return null
        val env = ChatEnvelope.decode(payload)
        if (env.version != Chat.VERSION || env.kind != ChatKind.DIRECT_TEXT) return null
        val senderPub = env.body[CBORObject.FromObject(1)].GetByteString()
        val nonce = env.body[CBORObject.FromObject(2)].GetByteString()
        val ct = env.body[CBORObject.FromObject(3)].GetByteString()
        if (senderPub.size != 32) return null
        if (NodeId.fromPublicKey(senderPub) != ctx.source) return null
        if (nonce.size != Chat.DIRECT_NONCE_BYTES) return null

        val key = pairwiseKey(recipient.sharedSecretWith(senderPub), senderPub, recipient.publicKey)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad(ctx, senderPub))
        val pt = cipher.doFinal(ct)
        val map = CBORObject.DecodeFromBytes(pt)
        val sentAt = map[CBORObject.FromObject(1)].AsInt64Value()
        val text = map[CBORObject.FromObject(2)].AsString()
        if (!Chat.textWithinLimit(text)) return null
        Opened(text, sentAt, senderPub)
    }.getOrNull()

    /** Returns the sender's Ed25519 public key carried in a DIRECT_TEXT payload, or null. */
    fun senderPublicKey(payload: ByteArray): ByteArray? = runCatching {
        val env = ChatEnvelope.decode(payload)
        if (env.kind != ChatKind.DIRECT_TEXT) return null
        env.body[CBORObject.FromObject(1)].GetByteString().takeIf { it.size == 32 }
    }.getOrNull()

    // §5.5 pairwise key: info = prefix || pub_low || pub_high (Ed25519 keys sorted).
    private fun pairwiseKey(sharedSecret: ByteArray, pubA: ByteArray, pubB: ByteArray): ByteArray {
        val (low, high) = if (compareBytes(pubA, pubB) <= 0) pubA to pubB else pubB to pubA
        val info = ByteArrayOutputStream().apply {
            write(Wire.asciiNul("SIDEPATH-CHAT-DIRECT-V1")); write(low); write(high)
        }.toByteArray()
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(sharedSecret, null, info))
        return ByteArray(32).also { gen.generateBytes(it, 0, it.size) }
    }

    // §5.7 additional authenticated data.
    private fun aad(ctx: ChatContext, senderPub: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(Wire.asciiNul("SIDEPATH-CHAT-DIRECT-AAD-V1"))
        out.write(ctx.datagramId)
        out.write(ctx.source.bytes)
        out.write(ctx.destination.bytes)
        out.write(senderPub)
        out.write(Wire.le16(Chat.PROTOCOL_ID))
        out.write(Chat.VERSION)
        out.write(ChatKind.DIRECT_TEXT)
        return out.toByteArray()
    }

    private fun plaintextCbor(sentAt: Long, text: String): ByteArray {
        val map = CBORObject.NewOrderedMap()
        map[CBORObject.FromObject(1)] = CBORObject.FromObject(sentAt)
        map[CBORObject.FromObject(2)] = CBORObject.FromObject(text)
        return map.EncodeToBytes()
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
