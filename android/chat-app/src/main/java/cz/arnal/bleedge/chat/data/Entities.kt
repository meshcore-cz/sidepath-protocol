package cz.arnal.bleedge.chat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Conversation key used for the single public broadcast channel. */
const val CHANNEL_PEER = "channel"

/** Outgoing-message delivery state. */
object MsgStatus {
    const val SENDING = 0
    const val SENT = 1       // transmitted to the mesh
    const val DELIVERED = 2  // recipient ACKed (direct messages only)
    const val FAILED = 3     // could not be sent (e.g. unknown public key)
}

/**
 * A peer we have learned about (from its ANNOUNCE) or chatted with.
 * [pubKeyHex] is the 32-byte Ed25519 key used to encrypt direct messages.
 */
@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey val nodeHex: String,
    val pubKeyHex: String,
    val description: String,
)

/**
 * One chat message. [id] is the mesh packet id (hex) so an inbound delivery ACK can be
 * matched back to the outgoing message it confirms. [peerHex] is the conversation:
 * the other node's id for a direct chat, or [CHANNEL_PEER] for the public channel.
 * [routeHex] is a comma-separated hop path (the trace the packet took, or for a
 * delivered DM the route the ACK returned along).
 */
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val id: String,
    val peerHex: String,
    val senderHex: String = "", // originating node id (hex); identifies who posted on the channel
    val incoming: Boolean,
    val text: String,
    val timestampMs: Long,
    val status: Int = MsgStatus.SENT,
    val routeHex: String = "",
    val read: Boolean = false,
)
