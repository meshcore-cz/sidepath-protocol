package cz.meshcore.sidepath.chat

import com.upokecenter.cbor.CBORObject
import cz.meshcore.sidepath.protocol.Identity
import cz.meshcore.sidepath.protocol.NodeId
import cz.meshcore.sidepath.protocol.Wire
import java.io.ByteArrayOutputStream
import org.bouncycastle.math.ec.rfc8032.Ed25519

/**
 * Signed public text messages (§4): readable by every receiving node, signed but
 * not encrypted. Body keys: 1 sender_public_key, 2 sent_at, 3 text, 4 signature.
 */
object ChatPublicText {
    data class Verified(val text: String, val sentAt: Long, val senderPublicKey: ByteArray)

    /** Builds a signed PUBLIC_TEXT chat payload for [ctx] (whose destination is broadcast). */
    fun build(identity: Identity, ctx: ChatContext, text: String, sentAt: Long): ByteArray {
        require(Chat.textWithinLimit(text)) { "text exceeds ${Chat.MAX_TEXT_BYTES} bytes" }
        val sig = ByteArray(Ed25519.SIGNATURE_SIZE)
        val msg = signedBytes(ctx, identity.publicKey, sentAt, text)
        Ed25519.sign(identity.seed, 0, msg, 0, msg.size, sig, 0)
        val body = CBORObject.NewOrderedMap()
        body[CBORObject.FromObject(1)] = CBORObject.FromObject(identity.publicKey)
        body[CBORObject.FromObject(2)] = CBORObject.FromObject(sentAt)
        body[CBORObject.FromObject(3)] = CBORObject.FromObject(text)
        body[CBORObject.FromObject(4)] = CBORObject.FromObject(sig)
        return ChatEnvelope(Chat.VERSION, ChatKind.PUBLIC_TEXT, body).encode()
    }

    /** Verifies and parses a PUBLIC_TEXT payload (§4.4). Returns null on any failure. */
    fun open(payload: ByteArray, ctx: ChatContext): Verified? = runCatching {
        if (!ctx.destination.isBroadcast()) return null
        val env = ChatEnvelope.decode(payload)
        if (env.version != Chat.VERSION || env.kind != ChatKind.PUBLIC_TEXT) return null
        val pub = env.body[CBORObject.FromObject(1)].GetByteString()
        val sentAt = env.body[CBORObject.FromObject(2)].AsInt64Value()
        val text = env.body[CBORObject.FromObject(3)].AsString()
        val sig = env.body[CBORObject.FromObject(4)].GetByteString()
        if (pub.size != 32) return null
        if (NodeId.fromPublicKey(pub) != ctx.source) return null
        if (!Chat.textWithinLimit(text)) return null
        val msg = signedBytes(ctx, pub, sentAt, text)
        if (!Ed25519.verify(sig, 0, pub, 0, msg, 0, msg.size)) return null
        Verified(text, sentAt, pub)
    }.getOrNull()

    private fun signedBytes(ctx: ChatContext, senderPub: ByteArray, sentAt: Long, text: String): ByteArray {
        val t = text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(Wire.asciiNul("SIDEPATH-CHAT-PUBLIC-TEXT-V1"))
        out.write(ctx.datagramId)
        out.write(ctx.source.bytes)
        out.write(ctx.destination.bytes)
        out.write(senderPub)
        out.write(Wire.le64(sentAt))
        out.write(Wire.le16(t.size)); out.write(t)
        return out.toByteArray()
    }
}
