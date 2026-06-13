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
 * Encrypted direct emoji reactions (§8.1): an authenticated pairwise AES-256-GCM message — same
 * envelope and pairwise key as [ChatDirectText] — that adds or removes one emoji reaction to a
 * previously exchanged direct message. The encrypted plaintext references the target message by
 * its application message id ([targetRef], the Sidepath datagram id hex). Body: 1 sender_public_key,
 * 2 nonce(12), 3 ciphertext.
 */
object ChatDirectReaction {
    private const val GCM_TAG_BITS = 128
    const val MAX_EMOJI_BYTES = 64

    data class Opened(
        val sentAt: Long,
        val targetRef: String,
        val emoji: String,
        val remove: Boolean,
        val senderPublicKey: ByteArray,
    )

    /** Seals a DIRECT_REACTION for the holder of [recipientPublicKey]. */
    fun seal(
        sender: Identity,
        recipientPublicKey: ByteArray,
        ctx: ChatContext,
        targetRef: String,
        emoji: String,
        remove: Boolean,
        sentAt: Long,
    ): ByteArray {
        require(emoji.toByteArray(Charsets.UTF_8).size <= MAX_EMOJI_BYTES) { "emoji too long" }
        val key = pairwiseKey(sender.sharedSecretWith(recipientPublicKey), sender.publicKey, recipientPublicKey)
        val nonce = ByteArray(Chat.DIRECT_NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val aad = aad(ctx, sender.publicKey)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintextCbor(sentAt, targetRef, emoji, remove))

        val body = CBORObject.NewOrderedMap()
        body[CBORObject.FromObject(1)] = CBORObject.FromObject(sender.publicKey)
        body[CBORObject.FromObject(2)] = CBORObject.FromObject(nonce)
        body[CBORObject.FromObject(3)] = CBORObject.FromObject(ct)
        return ChatEnvelope(Chat.VERSION, ChatKind.DIRECT_REACTION, body).encode()
    }

    /** Decrypts and authenticates a DIRECT_REACTION addressed to [recipient]. */
    fun open(recipient: Identity, payload: ByteArray, ctx: ChatContext): Opened? = runCatching {
        if (ctx.destination != recipient.nodeId) return null
        val env = ChatEnvelope.decode(payload)
        if (env.version != Chat.VERSION || env.kind != ChatKind.DIRECT_REACTION) return null
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
        val targetRef = map[CBORObject.FromObject(2)].AsString()
        val emoji = map[CBORObject.FromObject(3)].AsString()
        val remove = map[CBORObject.FromObject(4)].AsBoolean()
        if (emoji.toByteArray(Charsets.UTF_8).size > MAX_EMOJI_BYTES) return null
        Opened(sentAt, targetRef, emoji, remove, senderPub)
    }.getOrNull()

    /** Returns the sender's Ed25519 public key carried in a DIRECT_REACTION payload, or null. */
    fun senderPublicKey(payload: ByteArray): ByteArray? = runCatching {
        val env = ChatEnvelope.decode(payload)
        if (env.kind != ChatKind.DIRECT_REACTION) return null
        env.body[CBORObject.FromObject(1)].GetByteString().takeIf { it.size == 32 }
    }.getOrNull()

    // Same pairwise key as DIRECT_TEXT (§5.5): info = prefix || pub_low || pub_high.
    private fun pairwiseKey(sharedSecret: ByteArray, pubA: ByteArray, pubB: ByteArray): ByteArray {
        val (low, high) = if (compareBytes(pubA, pubB) <= 0) pubA to pubB else pubB to pubA
        val info = ByteArrayOutputStream().apply {
            write(Wire.asciiNul("SIDEPATH-CHAT-DIRECT-V1")); write(low); write(high)
        }.toByteArray()
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(sharedSecret, null, info))
        return ByteArray(32).also { gen.generateBytes(it, 0, it.size) }
    }

    // AAD binds the reaction kind so a DIRECT_TEXT ciphertext can't be replayed as a reaction.
    private fun aad(ctx: ChatContext, senderPub: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(Wire.asciiNul("SIDEPATH-CHAT-REACTION-AAD-V1"))
        out.write(ctx.datagramId)
        out.write(ctx.source.bytes)
        out.write(ctx.destination.bytes)
        out.write(senderPub)
        out.write(Wire.le16(Chat.PROTOCOL_ID))
        out.write(Chat.VERSION)
        out.write(ChatKind.DIRECT_REACTION)
        return out.toByteArray()
    }

    private fun plaintextCbor(sentAt: Long, targetRef: String, emoji: String, remove: Boolean): ByteArray {
        val map = CBORObject.NewOrderedMap()
        map[CBORObject.FromObject(1)] = CBORObject.FromObject(sentAt)
        map[CBORObject.FromObject(2)] = CBORObject.FromObject(targetRef)
        map[CBORObject.FromObject(3)] = CBORObject.FromObject(emoji)
        map[CBORObject.FromObject(4)] = CBORObject.FromObject(remove)
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
