package cz.arnal.bleedge.chat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// v3: NodeIDs widened 8→10 bytes (protocol v3), so old peer/contact ids are stale —
// destructive migration wipes the v2 store. See docs/PROTOCOL.md migration §17.
@Database(
    entities = [Message::class, Contact::class, Channel::class, DiscoveredContact::class],
    version = 6,
    exportSchema = false,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                "bleedge_chat.db",
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
