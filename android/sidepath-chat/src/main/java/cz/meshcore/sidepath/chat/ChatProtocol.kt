package cz.meshcore.sidepath.chat

import com.upokecenter.cbor.CBORObject
import cz.meshcore.sidepath.protocol.NodeId

/**
 * Meshward (docs/CHAT_PROTOCOL.md) — a native messenger payload protocol
 * carried as a SIDEPATH_CHAT (`0x0100`) datagram payload. This package is pure
 * Kotlin/JVM on top of `:sidepath-protocol` and never touches routing.
 */
object Chat {
    const val PROTOCOL_ID: Int = 0x0100
    const val VERSION: Int = 1
    const val MAX_TEXT_BYTES: Int = 2048

    const val DIRECT_NONCE_BYTES: Int = 12
    const val DIRECT_GCM_TAG_BYTES: Int = 16

    /** Returns the chat message kind without fully parsing the body, or null if malformed. */
    fun peekKind(payload: ByteArray): Int? = runCatching { ChatEnvelope.decode(payload).kind }.getOrNull()

    internal fun textWithinLimit(text: String): Boolean =
        text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES
}

/** Chat message kinds (§2). */
object ChatKind {
    const val PUBLIC_TEXT: Int = 1
    const val DIRECT_TEXT: Int = 2
    const val TYPING: Int = 3
    const val CHANNEL_TEXT: Int = 4
    const val DIRECT_REACTION: Int = 5  // encrypted emoji reaction to one node's message (§8.1)
    const val CHANNEL_REACTION: Int = 6 // channel-secret emoji reaction to a channel message (§8.2)
}

/**
 * The chat message envelope (§2): CBOR map { 1: version, 2: kind, 3: body }.
 */
data class ChatEnvelope(val version: Int, val kind: Int, val body: CBORObject) {
    fun encode(): ByteArray {
        val map = CBORObject.NewOrderedMap()
        map[CBORObject.FromObject(1)] = CBORObject.FromObject(version)
        map[CBORObject.FromObject(2)] = CBORObject.FromObject(kind)
        map[CBORObject.FromObject(3)] = body
        return map.EncodeToBytes()
    }

    companion object {
        fun decode(data: ByteArray): ChatEnvelope {
            val map = CBORObject.DecodeFromBytes(data)
            return ChatEnvelope(
                version = map[CBORObject.FromObject(1)].AsInt32(),
                kind = map[CBORObject.FromObject(2)].AsInt32(),
                body = map[CBORObject.FromObject(3)],
            )
        }
    }
}

/**
 * Immutable outer-datagram fields that chat signatures and AEAD AAD bind to.
 * Mutable routing fields (ttl, route, cursor, path) are deliberately excluded.
 */
data class ChatContext(
    val datagramId: ByteArray,   // 16 bytes
    val source: NodeId,
    val destination: NodeId,
)
