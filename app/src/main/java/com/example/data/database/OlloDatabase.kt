package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.CardDao
import com.example.data.dao.FolderDao
import com.example.data.dao.ImageDao
import com.example.data.entity.CardEntity
import com.example.data.entity.FolderEntity
import com.example.data.entity.ImageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        FolderEntity::class,
        CardEntity::class,
        ImageEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class OlloDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao
    abstract fun cardDao(): CardDao
    abstract fun imageDao(): ImageDao

    companion object {
        @Volatile
        private var INSTANCE: OlloDatabase? = null

        // Explicit migrations defined from day one (no destructive fallback)
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE images ADD COLUMN format INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): OlloDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    OlloDatabase::class.java,
                    "ollo_database.db"
                )
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Seed first-run state directly on SQLite database
                        val now = System.currentTimeMillis()
                        db.execSQL("INSERT INTO folders (id, name, sort_order, include_in_sync, created_at, updated_at) VALUES (1, 'My cards', 0, 1, $now, $now)")
                        db.execSQL("INSERT INTO cards (folder_id, sort_order, front_text, back_text, created_at, updated_at) VALUES (1, 0, 'Welcome to OllO', 'Smart glasses flashcards', $now, $now)")
                        db.execSQL("INSERT INTO cards (folder_id, sort_order, front_text, back_text, created_at, updated_at) VALUES (1, 1, '1-bit OLED Display', 'Lit pixels are white', $now, $now)")
                        db.execSQL("INSERT INTO cards (folder_id, sort_order, front_text, back_text, created_at, updated_at) VALUES (1, 2, 'Sync over BLE', 'Tap Sync on bottom-left', $now, $now)")
                    }
                })
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
