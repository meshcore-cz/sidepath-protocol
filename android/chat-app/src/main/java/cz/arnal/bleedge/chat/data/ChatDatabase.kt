package cz.arnal.bleedge.chat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// v3: NodeIDs widened 8→10 bytes (protocol v3), so old peer/contact ids are stale —
// destructive migration wipes the v2 store. See docs/PROTOCOL.md migration §17.
@Database(
    entities = [Message::class, Contact::class, Channel::class, DiscoveredContact::class],
    version = 7,
    exportSchema = false,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile private var instance: ChatDatabase? = null

        // v6→v7: add MeshCore carrier columns to messages. Additive, so migrate in place
        // (preserve joined channels / contacts / history) rather than wiping the store.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN meshCoreType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN meshCoreRoute TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN meshCoreHops INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN meshCorePacketId TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): ChatDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                "bleedge_chat.db",
            ).addMigrations(MIGRATION_6_7)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
