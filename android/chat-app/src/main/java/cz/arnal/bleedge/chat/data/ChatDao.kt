package cz.arnal.bleedge.chat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    // ---- messages ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(msg: Message): Long

    @Query("SELECT * FROM messages ORDER BY timestampMs ASC")
    fun allMessages(): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE peerHex = :peer ORDER BY timestampMs ASC")
    fun messagesFor(peer: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun messageById(id: String): Message?

    @Query("UPDATE messages SET status = :status, routeHex = :route WHERE id = :id")
    suspend fun updateDelivery(id: String, status: Int, route: String)

    @Query("UPDATE messages SET read = 1 WHERE peerHex = :peer AND incoming = 1 AND read = 0")
    suspend fun markRead(peer: String)

    @Query("DELETE FROM messages WHERE peerHex = :peer")
    suspend fun deleteMessagesFor(peer: String)

    // ---- contacts ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContact(contact: Contact)

    @Query("SELECT * FROM contacts")
    fun contacts(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts WHERE nodeHex = :nodeHex LIMIT 1")
    suspend fun contactByNode(nodeHex: String): Contact?

    @Query("DELETE FROM contacts WHERE nodeHex = :nodeHex")
    suspend fun deleteContact(nodeHex: String)

    // ---- channels ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannel(channel: Channel)

    @Query("SELECT * FROM channels ORDER BY name")
    fun channels(): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE pskHex = :pskHex LIMIT 1")
    suspend fun channelByPsk(pskHex: String): Channel?

    @Query("SELECT * FROM channels WHERE hashByte = :hashByte")
    suspend fun channelsByHash(hashByte: Int): List<Channel>

    @Query("DELETE FROM channels WHERE pskHex = :pskHex")
    suspend fun deleteChannel(pskHex: String)

    // ---- discovered contacts ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDiscovered(contact: DiscoveredContact)

    @Query("SELECT * FROM discovered_contacts ORDER BY lastAdvertisedMs DESC")
    fun discoveredContacts(): Flow<List<DiscoveredContact>>

    @Query("SELECT * FROM discovered_contacts WHERE pubKeyHex = :pubKeyHex LIMIT 1")
    suspend fun discoveredByPubKey(pubKeyHex: String): DiscoveredContact?

    @Query("DELETE FROM discovered_contacts WHERE pubKeyHex = :pubKeyHex")
    suspend fun deleteDiscovered(pubKeyHex: String)

    @Query("DELETE FROM discovered_contacts")
    suspend fun clearDiscovered()

    /** Drops discovered contacts not heard from since [cutoffMs] (their TTL has elapsed). */
    @Query("DELETE FROM discovered_contacts WHERE lastAdvertisedMs < :cutoffMs")
    suspend fun pruneDiscovered(cutoffMs: Long)
}
