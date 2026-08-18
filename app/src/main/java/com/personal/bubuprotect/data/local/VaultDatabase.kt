package com.personal.bubuprotect.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.personal.bubuprotect.core.crypto.SecureBytes
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [PasswordEntity::class],
    version = 2,
    exportSchema = false
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun passwordDao(): PasswordDao
}

/**
 * v1 -> v2: the vault stopped being passwords-only.
 *
 * Both statements are additive, which is the whole reason this is safe to run over a file full of
 * secrets: no table is rebuilt, so no ciphertext is copied through a temp table where a crash could
 * strand it. Existing rows were all logins, and `extras_cipher` staying NULL means they have no
 * kind-specific fields - both of which the mappers already treat as valid.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE password_entries ADD COLUMN kind TEXT NOT NULL DEFAULT 'login'")
        db.execSQL("ALTER TABLE password_entries ADD COLUMN extras_cipher BLOB")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_password_entries_kind ON password_entries (kind)")
    }
}

/**
 * Opens the vault database under SQLCipher.
 *
 * The database is *not* a long-lived singleton, which is the whole point. It exists only while the
 * vault is unlocked: [open] is called after a successful authentication and the instance is closed
 * and dropped on lock. A conventional `@Single` Room instance would keep the derived key live inside
 * the native SQLCipher session for as long as the process lived, so backgrounding the app would
 * leave a fully readable database behind a lock screen that only looked locked.
 *
 * The passphrase handed in is an HKDF subkey of the root key, not anything the user typed. SQLCipher
 * still runs its own PBKDF2 over it, which is redundant for a 256-bit random input but costs one
 * few-hundred-millisecond hit at open time and nothing after - so it is left at the default rather
 * than switched to a raw-key pragma, keeping the standard, well-tested code path.
 */
class EncryptedDatabaseFactory(private val context: Context) {

    /**
     * @param passphrase stays owned by the caller ([com.personal.bubuprotect.session.VaultSession]),
     *   which wipes it on lock. `clearPassphrase = false` because SQLCipher would otherwise zero the
     *   array as soon as it keyed the first connection, and Room opens additional connections lazily
     *   - a later read would then be keyed with an all-zero passphrase and fail.
     */
    fun open(passphrase: SecureBytes): VaultDatabase {
        loadNativeLibrary()
        val factory = SupportOpenHelperFactory(passphrase.use(), HardeningHook, false)

        return Room.databaseBuilder(context, VaultDatabase::class.java, DATABASE_NAME)
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2)
            // No fallbackToDestructiveMigration: silently dropping the table on a schema mismatch
            // would erase every password the user owns. A migration failure must be loud.
            .build()
    }

    /**
     * `cipher_memory_security = ON` makes SQLCipher lock its working pages out of swap and wipe
     * them on free, at a measurable cost to throughput. It has been off by default since SQLCipher
     * 4.5 for exactly that reason - which is the right default for a general database and the wrong
     * one for a few dozen rows of passwords, where the cost is invisible.
     *
     * It has to be set before the key is applied, hence `preKey`.
     */
    private object HardeningHook : SQLiteDatabaseHook {
        override fun preKey(connection: SQLiteConnection) {
            connection.execute("PRAGMA cipher_memory_security = ON;", emptyArray(), null)
        }

        /**
         * Two pragmas that only make sense once the database is keyed.
         *
         * **`secure_delete = ON`** overwrites deleted content with zeroes instead of just marking
         * the page free. Without it, deleting the bank entry leaves its ciphertext sitting in the
         * file's free list until something happens to reuse the page - so "delete" would mean
         * "hidden from the list", and anyone who later obtained the file *and* the key would still
         * recover it. It costs extra writes on delete, which for a vault of tens of rows is free.
         *
         * **`temp_store = MEMORY`** keeps SQLite's scratch space - the temp b-trees it builds for
         * sorting and for `ALTER TABLE` - in RAM. On disk those spill into files that do not
         * necessarily inherit the database's encryption, which would put fragments of decrypted
         * rows outside the vault entirely.
         */
        override fun postKey(connection: SQLiteConnection) {
            // The two calls are different on purpose, and mixing them up throws.
            //
            // SQLCipher inherits AOSP's split: `execute` rejects any statement that produces a row
            // ("Queries can be performed using ... query or rawQuery methods only"), while
            // `executeForLong` requires exactly one. Whether an assignment pragma reports its new
            // value back is per-pragma, decided in SQLite's pragma.c: `secure_delete` always calls
            // returnSingleInt, so it yields a row; `temp_store` only does so in its query form, so
            // the assignment yields none.
            val enabled = connection.executeForLong("PRAGMA secure_delete = ON;", emptyArray(), null)
            if (enabled != 1L) {
                // Worth knowing rather than assuming: if this ever silently failed to take, deleted
                // ciphertext would linger in the file's free pages.
                Log.w(TAG, "secure_delete did not take effect (reported $enabled)")
            }
            connection.execute("PRAGMA temp_store = MEMORY;", emptyArray(), null)
        }
    }

    private companion object {
        const val TAG = "EncryptedDatabase"
        const val DATABASE_NAME = "bubu-vault.db"

        @Volatile
        var nativeLibraryLoaded = false

        @Synchronized
        fun loadNativeLibrary() {
            if (!nativeLibraryLoaded) {
                System.loadLibrary("sqlcipher")
                nativeLibraryLoaded = true
            }
        }
    }
}
