package de.skyslycer.briefkasten

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import kotlinx.coroutines.flow.map
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

val Context.settingsStore by preferencesDataStore("settings")

data class Settings(
    val polling: Boolean = true, val lock: Boolean = false, val pictures: Boolean = true,
    val interval: Long = 30, val lastCheck: Long = 0, val status: String = "Sign in to start saving your letters.",
    val notifications: Boolean = true
)

class Preferences(context: Context,
    private val store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> = context.settingsStore) {
    val flow = store.data.map { p -> Settings(
        p[POLLING] ?: true, p[LOCK] ?: false, p[PICTURES] ?: true,
        p[INTERVAL] ?: 30, p[LAST_CHECK] ?: 0, p[STATUS] ?: "Sign in to start saving your letters.",
        p[NOTIFICATIONS] ?: true
    ) }
    suspend fun polling(value: Boolean) { store.edit { it[POLLING] = value } }
    suspend fun lock(value: Boolean) { store.edit { it[LOCK] = value } }
    suspend fun pictures(value: Boolean) { store.edit { it[PICTURES] = value } }
    suspend fun notifications(value: Boolean) { store.edit { it[NOTIFICATIONS] = value } }
    suspend fun interval(value: Long) { require(value in listOf(15L, 30L, 60L, 180L)); store.edit { it[INTERVAL] = value } }
    suspend fun status(message: String, success: Boolean = false) { store.edit {
        it[STATUS] = message
        if (success) it[LAST_CHECK] = System.currentTimeMillis()
    } }
    companion object {
        private val POLLING = booleanPreferencesKey("polling")
        private val LOCK = booleanPreferencesKey("lock")
        private val PICTURES = booleanPreferencesKey("pictures")
        private val NOTIFICATIONS = booleanPreferencesKey("notifications")
        private val INTERVAL = longPreferencesKey("interval")
        private val LAST_CHECK = longPreferencesKey("last_check")
        private val STATUS = stringPreferencesKey("status")
    }
}

/** Device-bound encryption; deliberately does not require biometric presence for background sync. */
class Vault(private val context: Context) {
    private val directory = File(context.filesDir, "vault").apply { mkdirs() }
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("briefkasten.storage.v1", null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply { init(KeyGenParameterSpec.Builder("briefkasten.storage.v1",
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build()) }.generateKey()
    }
    @Synchronized fun write(name: String, bytes: ByteArray) {
        require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(name.toByteArray()) }
        val output = cipher.iv + cipher.doFinal(bytes)
        val target = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        temporary.outputStream().use { it.write(output); it.fd.sync() }
        check(temporary.renameTo(target)) { "Could not save encrypted file." }
    }
    @Synchronized fun read(name: String): ByteArray? {
        require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
        val file = File(directory, name)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        require(bytes.size >= 28) { "Invalid archive file." }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(name.toByteArray()); doFinal(bytes.copyOfRange(12, bytes.size))
        }
    }
    @Synchronized fun remove(name: String) { require(name.matches(Regex("[a-zA-Z0-9_.-]+"))); File(directory, name).delete() }
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

@Entity(tableName = "letters")
data class Letter(
    @PrimaryKey val id: String,
    val account: String,
    val date: String,
    val firstSeen: Long,
    val fileName: String,
    val contentHash: String,
    val occurrence: Int,
    val notificationPending: Boolean,
    val groupId: String? = null
)
@Entity(tableName = "sources")
data class Source(@PrimaryKey val source: String, val letterId: String)
@Entity(tableName = "accounts")
data class Account(@PrimaryKey val id: String, val initialized: Boolean)

@Dao
interface ArchiveDao {
    @Query("SELECT * FROM sources ORDER BY source") suspend fun allSources(): List<Source>
    @Query("SELECT * FROM accounts ORDER BY id") suspend fun allAccounts(): List<Account>
    @Query("UPDATE letters SET groupId = :groupId WHERE id = :id") suspend fun assignGroup(id: String, groupId: String?)
    @Query("SELECT * FROM letter_groups ORDER BY name COLLATE NOCASE") fun observeGroups(): kotlinx.coroutines.flow.Flow<List<LetterGroup>>
    @Query("SELECT * FROM letter_groups ORDER BY id") suspend fun groups(): List<LetterGroup>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveGroup(group: LetterGroup)
    @Query("DELETE FROM letter_groups WHERE id = :id") suspend fun deleteGroup(id: String)
    @Query("SELECT * FROM letters ORDER BY firstSeen DESC, id ASC") fun observe(): kotlinx.coroutines.flow.Flow<List<Letter>>
    @Query("SELECT * FROM letters ORDER BY firstSeen ASC, id ASC") suspend fun all(): List<Letter>
    @Query("SELECT * FROM letters WHERE id = :id") suspend fun find(id: String): Letter?
    @Query("SELECT * FROM sources WHERE source = :source") suspend fun source(source: String): Source?
    @Query("SELECT initialized FROM accounts WHERE id = :account") suspend fun initialized(account: String): Boolean?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(letter: Letter): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun source(source: Source)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun account(account: Account)
    @Query("UPDATE letters SET notificationPending = 0 WHERE id = :id") suspend fun notified(id: String)
    @Query("SELECT * FROM letters WHERE notificationPending = 1 AND account = :account") suspend fun pending(account: String): List<Letter>
}

@Database(entities = [Letter::class, Source::class, Account::class, LetterGroup::class], version = 2, exportSchema = false)
abstract class ArchiveDatabase : RoomDatabase() {
    abstract fun archive(): ArchiveDao
}
