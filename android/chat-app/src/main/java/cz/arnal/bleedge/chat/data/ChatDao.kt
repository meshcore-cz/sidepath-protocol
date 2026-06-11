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

    // ---- contacts ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContact(contact: Contact)

    @Query("SELECT * FROM contacts")
    fun contacts(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts WHERE nodeHex = :nodeHex LIMIT 1")
    suspend fun contactByNode(nodeHex: String): Contact?
}
