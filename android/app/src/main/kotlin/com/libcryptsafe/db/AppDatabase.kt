package com.libcryptsafe.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [MessageEntity::class, ContactEntity::class, PrekeyEntity::class],
    version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun prekeyDao(): PrekeyDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        private const val DB_NAME = "libcryptsafe_messages.db"

        // Миграция v3->v4: добавляем таблицу prekeys (X3DH). Данные v3 целы.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS prekeys (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "keyType TEXT NOT NULL, " +
                    "keyId INTEGER NOT NULL, " +
                    "privateKey BLOB NOT NULL, " +
                    "publicKey BLOB NOT NULL, " +
                    "signature BLOB, " +
                    "timestamp INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_prekeys_keyType ON prekeys(keyType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_prekeys_keyId ON prekeys(keyId)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                System.loadLibrary("sqlcipher")

                // Путь A: удаляем старую НЕзашифрованную базу (одноразовая миграция)
                val prefs = context.getSharedPreferences("libcryptsafe_secure_prefs", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("sqlcipher_migrated", false)) {
                    context.getDatabasePath(DB_NAME).let { if (it.exists()) it.delete() }
                    context.getDatabasePath("$DB_NAME-wal").let { if (it.exists()) it.delete() }
                    context.getDatabasePath("$DB_NAME-shm").let { if (it.exists()) it.delete() }
                    prefs.edit().putBoolean("sqlcipher_migrated", true).apply()
                }

                val passphrase = KeyStoreManager.getDatabasePassphrase(context)
                val factory = SupportOpenHelperFactory(passphrase)

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                ).openHelperFactory(factory)
                      .addMigrations(MIGRATION_3_4)  // путь B: данные сохраняются
                      .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
