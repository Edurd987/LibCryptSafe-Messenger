package com.libcryptsafe.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        private const val DB_NAME = "libcryptsafe_messages.db"

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
                ).openHelperFactory(factory).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
