package app.kikoeru.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.preferences by preferencesDataStore("settings")

class TokenVault {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("kikoeru-session", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("kikoeru-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }
    fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    }
}

class SettingsStore(private val context: Context) {
    private val profileKey = stringPreferencesKey("profile")
    private val tokenKey = stringPreferencesKey("encrypted_token")
    private val themeKey = stringPreferencesKey("theme")
    private val dynamicKey = booleanPreferencesKey("dynamic_color")
    private val subtitlesKey = booleanPreferencesKey("subtitles_enabled")
    val subtitlesEnabled = context.preferences.data.map { it[subtitlesKey] ?: true }
    suspend fun subtitles(value: Boolean) { context.preferences.edit { it[subtitlesKey] = value } }
    private val privacyKey = booleanPreferencesKey("privacy_mode")
    val privacyMode = context.preferences.data.map { it[privacyKey] ?: false }
    suspend fun privacy(value: Boolean) { context.preferences.edit { it[privacyKey] = value } }
    private val searchesKey = stringPreferencesKey("searches")
    val theme = context.preferences.data.map { it[themeKey] ?: "system" }
    val dynamicColor = context.preferences.data.map { it[dynamicKey] ?: true }
    val searches = context.preferences.data.map { prefs ->
        runCatching { AppJson.decodeFromString<List<String>>(prefs[searchesKey] ?: "[]") }.getOrDefault(emptyList())
    }
    suspend fun loadSession(): Session? {
        val prefs = context.preferences.data.first()
        val profile = prefs[profileKey] ?: return null
        return runCatching { Session(AppJson.decodeFromString(profile), TokenVault().decrypt(prefs[tokenKey] ?: "")) }.getOrNull()
    }
    suspend fun saveSession(session: Session?) {
        val token = session?.let { TokenVault().encrypt(it.token) }
        context.preferences.edit {
            if (session == null) { it.remove(profileKey); it.remove(tokenKey) }
            else { it[profileKey] = AppJson.encodeToString(session.profile); it[tokenKey] = token!! }
        }
    }
    suspend fun theme(value: String) { context.preferences.edit { it[themeKey] = value } }
    suspend fun dynamic(value: Boolean) { context.preferences.edit { it[dynamicKey] = value } }
    suspend fun rememberSearch(value: String) {
        if (value.isBlank()) return
        context.preferences.edit {
            val old = runCatching { AppJson.decodeFromString<List<String>>(it[searchesKey] ?: "[]") }.getOrDefault(emptyList())
            it[searchesKey] = AppJson.encodeToString((listOf(value.trim()) + old).distinct().take(12))
        }
    }
}

@Entity(tableName = "history", primaryKeys = ["scope", "workId"])
data class HistoryEntry(val scope: String, val workId: Long, val trackJson: String, val positionMs: Long, val updatedAt: Long)
@Entity(tableName = "queues")
data class QueueRecord(@PrimaryKey val scope: String, val json: String)

@Dao
interface ListeningDao {
    @Query("SELECT * FROM history WHERE scope = :scope ORDER BY updatedAt DESC LIMIT 100")
    fun history(scope: String): Flow<List<HistoryEntry>>
    @Upsert suspend fun history(entry: HistoryEntry)
    @Upsert suspend fun queue(record: QueueRecord)
    @Query("SELECT * FROM queues WHERE scope = :scope") suspend fun queue(scope: String): QueueRecord?
    @Query("DELETE FROM history WHERE scope = :scope") suspend fun clearHistory(scope: String)
    @Query("DELETE FROM queues WHERE scope = :scope") suspend fun clearQueue(scope: String)
}

@Database(entities = [HistoryEntry::class, QueueRecord::class], version = 1, exportSchema = true)
abstract class ListeningDatabase : RoomDatabase() {
    abstract fun listening(): ListeningDao
}
