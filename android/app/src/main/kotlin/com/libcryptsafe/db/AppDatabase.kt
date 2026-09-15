package com.libcryptsafe.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [MessageEntity::class, ContactEntity::class, PrekeyEntity::class, SessionEntity::class, ChannelEntity::class, PostEntity::class, MediaEntity::class],
    version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun prekeyDao(): PrekeyDao
    abstract fun sessionDao(): SessionDao
    abstract fun channelDao(): ChannelDao
    abstract fun postDao(): PostDao
    abstract fun mediaDao(): MediaDao

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
        // Миграция v4->v5: таблица sessions (X3DH SK per-peer). Данные v4 целы.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sessions (" +
                    "peerId TEXT PRIMARY KEY NOT NULL, " +
                    "kEnc BLOB NOT NULL, " +
                    "kAuth BLOB NOT NULL, " +
                    "createdAt INTEGER NOT NULL)"
                )
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS channels (" +
                    "channelId TEXT PRIMARY KEY NOT NULL, " +
                    "title TEXT NOT NULL, " +
                    "isOwned INTEGER NOT NULL, " +
                    "privKey BLOB, " +
                    "createdAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS posts (" +
                    "channelId TEXT NOT NULL, " +
                    "seq INTEGER NOT NULL, " +
                    "timestamp INTEGER NOT NULL, " +
                    "content TEXT NOT NULL, " +
                    "signature BLOB NOT NULL, " +
                    "PRIMARY KEY(channelId, seq))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_posts_channelId ON posts(channelId)"
                )
            }
        }

        // Миграция v6->v7: таблица media (сейф сохранённых фото). Данные v6 целы
        // (CREATE TABLE IF NOT EXISTS, старые таблицы не трогаем).
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS media (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "peerId TEXT, " +
                    "storageKey BLOB NOT NULL, " +
                    "encryptedBlob BLOB NOT NULL, " +
                    "timestamp INTEGER NOT NULL, " +
                    "mediaKind TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_peerId ON media(peerId)")
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

                // BACKUP ПЕРЕД МИГРАЦИЕЙ (урок: сломанная миграция сменила ключ БД ->
                // данные стали шумом). Если БД существует (значит возможна миграция) —
                // копируем .db+wal+shm в .bak ДО build(). build() триггерит миграцию.
                // Миграция упала -> восстанавливаем из .bak (лучше старые данные, чем
                // пустая БД). Успех -> удаляем .bak (минимум следов на диске).
                val dbFile = context.getDatabasePath(DB_NAME)
                val walFile = context.getDatabasePath("$DB_NAME-wal")
                val shmFile = context.getDatabasePath("$DB_NAME-shm")
                val bakDb = context.getDatabasePath("$DB_NAME.bak")
                val bakWal = context.getDatabasePath("$DB_NAME-wal.bak")
                val bakShm = context.getDatabasePath("$DB_NAME-shm.bak")
                val hadDb = dbFile.exists()   // на первом запуске БД нет — бэкапить нечего
                if (hadDb) {
                    try {
                        dbFile.copyTo(bakDb, overwrite = true)
                        if (walFile.exists()) walFile.copyTo(bakWal, overwrite = true)
                        if (shmFile.exists()) shmFile.copyTo(bakShm, overwrite = true)
                        android.util.Log.i("DB_BACKUP", "бэкап БД создан перед миграцией")
                    } catch (e: Exception) {
                        android.util.Log.w("DB_BACKUP", "не удалось создать бэкап: ${e.message}")
                    }
                }

                fun buildDb() = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                ).openHelperFactory(factory)
                      .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)  // путь B: данные сохраняются
                      .build()

                val instance = try {
                    val db = buildDb()
                    db.openHelper.writableDatabase   // ФОРСИРУЕМ открытие -> миграция идёт ЗДЕСЬ, в try
                    db
                } catch (e: Exception) {
                    android.util.Log.e("DB_BACKUP", "МИГРАЦИЯ УПАЛА: ${e.message} -> восстанавливаю из бэкапа")
                    if (hadDb && bakDb.exists()) {
                        // восстановить исходную БД из .bak (перезаписать повреждённую)
                        bakDb.copyTo(dbFile, overwrite = true)
                        if (bakWal.exists()) bakWal.copyTo(walFile, overwrite = true)
                        if (bakShm.exists()) bakShm.copyTo(shmFile, overwrite = true)
                        android.util.Log.w("DB_BACKUP", "БД восстановлена из бэкапа (миграция отменена)")
                    }
                    throw e   // пробрасываем: лучше явный краш «обновление не удалось», чем тихая пустая БД
                }

                // Миграция прошла успешно -> бэкап больше не нужен, удаляем (минимум следов)
                if (hadDb) {
                    bakDb.delete(); bakWal.delete(); bakShm.delete()
                }
                INSTANCE = instance
                instance
            }
        }
    }
}
