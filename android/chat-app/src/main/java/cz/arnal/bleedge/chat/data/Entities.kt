package cz.arnal.bleedge.chat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A conversation key for a channel is "ch:" + the channel's PSK hex. */
fun channelPeerId(pskHex: String): String = "ch:$pskHex"
fun isChannelPeer(peerHex: String): Boolean = peerHex.startsWith("ch:")
fun channelPskHexOf(peerHex: String): String = peerHex.removePrefix("ch:")

/** Channel kinds offered in the Join dialog. */
object ChannelKind {
    const val PUBLIC = "public"
    const val NAMED = "named"  // PSK derived from the name (SHA-256(name)[:16])
    const val SECRET = "secret"
}

/**
 * A joined MeshCore-compatible channel, identified by its 16-byte PSK ([pskHex], 32 hex
 * chars). [hashByte] is the 1-byte channel hash (0..255) used to match inbound packets.
 */
@Entity(tableName = "channels")
data class Channel(
    @PrimaryKey val pskHex: String,
    val name: String,
    val hashByte: Int,
    val kind: String,
)

/** Where a discovered contact was heard. */
object DiscoverySource {
    const val BLEEDGE = "bleedge" // a BLEEdge node's signed ANNOUNCE (topology)
    const val MESHCORE = "meshcore" // a MeshCore ADVERT bridged onto the mesh
}

/**
 * A node we've heard advertise but haven't added to our contacts. Surfaced on the Explore tab.
 * Keyed by [pubKeyHex] (32-byte Ed25519 key) which both BLEEdge nodes and MeshCore nodes carry.
 * [nodeType] is the MeshCore node type (0=unknown 1=chat 2=repeater 3=room 4=sensor; 0 for
 * BLEEdge). [lastAdvertisedMs] is updated each time we hear the node advertise.
 */
@Entity(tableName = "discovered_contacts")
data class DiscoveredContact(
    @PrimaryKey val pubKeyHex: String,
    val nodeHex: String,        // BLEEdge NodeId hex (pubkey[:10]); derived for MeshCore too
    val name: String,
    val source: String,         // DiscoverySource.*
    val nodeType: Int = 0,
    val hasGps: Boolean = false,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val sigVerified: Boolean = false,
    val lastAdvertisedMs: Long = 0L,
    val firstSeenMs: Long = 0L,
)

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
    // A user-chosen local alias (from Rename). Overrides the node's wire/derived name in all
    // views; empty means "use the node's advertised name". Distinct from [description].
    val localName: String = "",
)

/**
 * One chat message. [id] is the mesh packet id (hex) so an inbound delivery ACK can be
 * matched back to the outgoing message it confirms. [peerHex] is the conversation:
 * the other node's id for a direct chat, or "ch:"+pskHex for a channel (see [channelPeerId]).
 * [routeHex] is a comma-separated hop path (the trace the packet took, or for a
 * delivered DM the route the ACK returned along).
 */
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val id: String,
    val peerHex: String,
    val senderHex: String = "",  // originating node id (hex)
    val senderName: String = "", // display name of the sender (from a channel message's plaintext)
    val incoming: Boolean,
    val text: String,
    val timestampMs: Long,
    val status: Int = MsgStatus.SENT,
    val routeHex: String = "",
    val read: Boolean = false,
    val viaMeshCore: Boolean = false, // true if this message arrived over the MeshCore bridge
    // MeshCore carrier details (only set when viaMeshCore), shown in message details.
    val meshCoreType: String = "",
    val meshCoreRoute: String = "",
    val meshCoreHops: Int = 0,
    val meshCorePacketId: String = "",
)
