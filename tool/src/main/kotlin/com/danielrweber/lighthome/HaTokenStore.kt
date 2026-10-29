package com.danielrweber.lighthome

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SavedConnection(
    val endpoint: HaEndpoint,
    val token: String,
) {
    override fun toString(): String = "SavedConnection"
}

internal data class DeviceListPrefs(
    val chosenIds: Set<String> = emptySet(),
    val category: String? = null,
)

internal sealed interface HaTokenRead {
    data class Ready(val connection: SavedConnection) : HaTokenRead
    data object Missing : HaTokenRead
    data object Unreadable : HaTokenRead
}

internal class HaTokenStore(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun read(): HaTokenRead {
        return try {
            val prefs = dataStore.data.first()
            val url = prefs[URL]
            val ciphertext = prefs[CIPHERTEXT]
            val iv = prefs[IV]
            if (url == null && ciphertext == null && iv == null) return HaTokenRead.Missing
            if (url == null || ciphertext == null || iv == null) return HaTokenRead.Unreadable
            val endpoint = (parseHaBaseUrl(url) as? HaUrlResult.Ok)?.endpoint ?: return HaTokenRead.Unreadable
            val token = cipher().decrypt(ciphertext, iv)
            when (val parsed = normalizeToken(token)) {
                is TokenResult.Ok -> HaTokenRead.Ready(SavedConnection(endpoint, parsed.token))
                is TokenResult.Invalid -> HaTokenRead.Unreadable
            }
        } catch (_: Exception) {
            HaTokenRead.Unreadable
        }
    }

    suspend fun save(endpoint: HaEndpoint, token: String): Boolean {
        return try {
            val sealed = cipher().encrypt(token)
            dataStore.edit { prefs ->
                prefs[URL] = endpoint.baseUrl
                prefs[CIPHERTEXT] = sealed.ciphertext
                prefs[IV] = sealed.iv
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun readList(): DeviceListPrefs {
        return try {
            val prefs = dataStore.data.first()
            DeviceListPrefs(
                chosenIds = sanitizeChosenIds(prefs[CHOSEN] ?: emptySet()),
                category = sanitizeCategory(prefs[CATEGORY]),
            )
        } catch (_: Exception) {
            DeviceListPrefs()
        }
    }

    suspend fun saveList(chosenIds: Set<String>, category: String?): Boolean {
        return try {
            val ids = sanitizeChosenIds(chosenIds)
            val storedCategory = sanitizeCategory(category)
            dataStore.edit { prefs ->
                if (ids.isEmpty()) prefs.remove(CHOSEN) else prefs[CHOSEN] = ids
                if (storedCategory == null) prefs.remove(CATEGORY) else prefs[CATEGORY] = storedCategory
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun clear(): Boolean {
        return try {
            dataStore.edit { prefs ->
                prefs.remove(URL)
                prefs.remove(CIPHERTEXT)
                prefs.remove(IV)
                prefs.remove(CHOSEN)
                prefs.remove(CATEGORY)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun cipher(): HaTokenCipher = HaTokenCipher()

    private companion object {
        val URL = stringPreferencesKey("ha_base_url")
        val CIPHERTEXT = stringPreferencesKey("ha_token_ciphertext")
        val IV = stringPreferencesKey("ha_token_iv")
        val CHOSEN = stringSetPreferencesKey("ha_chosen_entities")
        val CATEGORY = stringPreferencesKey("ha_list_category")
    }
}

internal class HaTokenCipher {
    private val keystore = HaTokenKeystore()

    fun encrypt(plaintext: String): SealedToken {
        keystore.ensureKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystore.secretKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return SealedToken(
            ciphertext = Base64.getEncoder().encodeToString(encrypted),
            iv = Base64.getEncoder().encodeToString(cipher.iv),
        )
    }

    fun decrypt(ciphertext: String, iv: String): String {
        keystore.ensureKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keystore.secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.getDecoder().decode(iv)),
        )
        val plain = cipher.doFinal(Base64.getDecoder().decode(ciphertext))
        return plain.toString(Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

internal data class SealedToken(
    val ciphertext: String,
    val iv: String,
)

internal class HaTokenKeystore {
    fun ensureKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(ALIAS)) return
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        generator.generateKey()
    }

    fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: error("LightHome token key is missing")
        return entry.secretKey
    }

    private companion object {
        const val ALIAS = "lighthome_ha_token"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
