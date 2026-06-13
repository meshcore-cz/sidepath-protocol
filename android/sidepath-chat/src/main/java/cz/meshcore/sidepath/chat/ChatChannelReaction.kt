package cz.meshcore.sidepath.chat

import com.upokecenter.cbor.CBORObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Native channel emoji reactions (§8.2): adds or removes one emoji reaction to a channel message.
 * Broadcast and membership-authenticated with the channel secret (same `channel_hash || mac ||
 * AES-128-ECB` envelope as [ChatChannel], via [ChatChannel.sealRaw]/[ChatChannel.openRaw]), but the
 * encrypted plaintext is a native length-prefixed CBOR map — NOT MeshCore GRP_TXT text — so it is a
 * Sidepath-only extension that gateways do not translate to MeshCore. Body: 1 channel_payload.
 *
 *   plaintext = len[2 LE] || cbor || zero-pad-to-16
 *   cbor      = { 1: sent_at, 2: target_ref, 3: emoji, 4: remove, 5: sender_label }
 */
object ChatChannelReaction {
    const val MAX_EMOJI_BYTES = 64

    data class Decoded(
        val sentAt: Long,
        val targetRef: String,
        val emoji: String,
        val remove: Boolean,
        val senderLabel: String,
    )

    /** Builds a CHANNEL_REACTION chat payload for [secret]. */
    fun build(
        secret: ByteArray,
        senderLabel: String,
        targetRef: String,
        emoji: String,
        remove: Boolean,
        sentAt: Long,
    ): ByteArray {
        require(emoji.toByteArray(Charsets.UTF_8).size <= MAX_EMOJI_BYTES) { "emoji too long" }
        val cbor = CBORObject.NewOrderedMap()
        cbor[CBORObject.FromObject(1)] = CBORObject.FromObject(sentAt)
        cbor[CBORObject.FromObject(2)] = CBORObject.FromObject(targetRef)
        cbor[CBORObject.FromObject(3)] = CBORObject.FromObject(emoji)
        cbor[CBORObject.FromObject(4)] = CBORObject.FromObject(remove)
        cbor[CBORObject.FromObject(5)] = CBORObject.FromObject(senderLabel)
        val cborBytes = cbor.EncodeToBytes()
        val plaintext = ByteBuffer.allocate(2 + cborBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        plaintext.putShort(cborBytes.size.toShort())
        plaintext.put(cborBytes)
        val channelPayload = ChatChannel.sealRaw(secret, plaintext.array())
        val body = CBORObject.NewOrderedMap()
        body[CBORObject.FromObject(1)] = CBORObject.FromObject(channelPayload)
        return ChatEnvelope(Chat.VERSION, ChatKind.CHANNEL_REACTION, body).encode()
    }

    /** Extracts the raw channel_payload from a CHANNEL_REACTION envelope, or null. */
    fun channelPayload(payload: ByteArray): ByteArray? = runCatching {
        val env = ChatEnvelope.decode(payload)
        if (env.kind != ChatKind.CHANNEL_REACTION) return null
        env.body[CBORObject.FromObject(1)].GetByteString()
    }.getOrNull()

    /** Opens a CHANNEL_REACTION payload with [secret]; null on hash/MAC/shape failure. */
    fun open(secret: ByteArray, payload: ByteArray): Decoded? =
        channelPayload(payload)?.let { decodePayload(secret, it) }

    /** Opens a raw channel_payload (already extracted) with [secret]. */
    fun decodePayload(secret: ByteArray, channelPayload: ByteArray): Decoded? = runCatching {
        val pt = ChatChannel.openRaw(secret, channelPayload) ?: return null
        if (pt.size < 2) return null
        val len = ByteBuffer.wrap(pt, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        if (len <= 0 || 2 + len > pt.size) return null
        val map = CBORObject.DecodeFromBytes(pt.copyOfRange(2, 2 + len))
        val emoji = map[CBORObject.FromObject(3)].AsString()
        if (emoji.toByteArray(Charsets.UTF_8).size > MAX_EMOJI_BYTES) return null
        Decoded(
            sentAt = map[CBORObject.FromObject(1)].AsInt64Value(),
            targetRef = map[CBORObject.FromObject(2)].AsString(),
            emoji = emoji,
            remove = map[CBORObject.FromObject(4)].AsBoolean(),
            senderLabel = map[CBORObject.FromObject(5)].AsString(),
        )
    }.getOrNull()
}
