package cz.meshcore.sidepath.chat

import com.upokecenter.cbor.CBORObject
import cz.meshcore.sidepath.protocol.Identity
import cz.meshcore.sidepath.protocol.NodeId
import cz.meshcore.sidepath.protocol.Wire
import java.io.ByteArrayOutputStream
import org.bouncycastle.math.ec.rfc8032.Ed25519

/**
 * Signed ephemeral typing notifications (§6): sent to one node, signed but not
 * encrypted, never ACKed, never persisted. Body: 1 sender_public_key, 2 sent_at,
 * 3 signature.
 */
object ChatTyping {
    data class Verified(val sentAt: Long, val senderPublicKey: ByteArray)

    fun build(identity: Identity, ctx: ChatContext, sentAt: Long): ByteArray {
        val msg = signedBytes(ctx, identity.publicKey, sentAt)
        val sig = ByteArray(Ed25519.SIGNATURE_SIZE)
        Ed25519.sign(identity.seed, 0, msg, 0, msg.size, sig, 0)
        val body = CBORObject.NewOrderedMap()
        body[CBORObject.FromObject(1)] = CBORObject.FromObject(identity.publicKey)
        body[CBORObject.FromObject(2)] = CBORObject.FromObject(sentAt)
        body[CBORObject.FromObject(3)] = CBORObject.FromObject(sig)
        return ChatEnvelope(Chat.VERSION, ChatKind.TYPING, body).encode()
    }

    fun open(payload: ByteArray, ctx: ChatContext): Verified? = runCatching {
        val env = ChatEnvelope.decode(payload)
        if (env.version != Chat.VERSION || env.kind != ChatKind.TYPING) return null
        val pub = env.body[CBORObject.FromObject(1)].GetByteString()
        val sentAt = env.body[CBORObject.FromObject(2)].AsInt64Value()
        val sig = env.body[CBORObject.FromObject(3)].GetByteString()
        if (pub.size != 32) return null
        if (NodeId.fromPublicKey(pub) != ctx.source) return null
        val msg = signedBytes(ctx, pub, sentAt)
        if (!Ed25519.verify(sig, 0, pub, 0, msg, 0, msg.size)) return null
        Verified(sentAt, pub)
    }.getOrNull()

    private fun signedBytes(ctx: ChatContext, senderPub: ByteArray, sentAt: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(Wire.asciiNul("SIDEPATH-CHAT-TYPING-V1"))
        out.write(ctx.datagramId)
        out.write(ctx.source.bytes)
        out.write(ctx.destination.bytes)
        out.write(senderPub)
        out.write(Wire.le64(sentAt))
        return out.toByteArray()
    }
}
