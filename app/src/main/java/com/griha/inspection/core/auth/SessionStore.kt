package com.griha.inspection.core.auth

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

@Serializable
data class SessionUser(
    val id: String,
    val employeeCode: String,
    val displayName: String,
    val role: String,
    val mustChangePassword: Boolean = false,
) {
    val isAdmin get() = role == "ADMIN"
}

/** What lets this user unlock offline: a salted PBKDF2 hash, never the password itself (PRD §10). */
@Serializable
data class OfflineVerifier(val employeeCode: String, val salt: String, val hash: String, val iterations: Int) {
    fun matches(code: String, password: String): Boolean =
        code.trim().equals(employeeCode, ignoreCase = true) &&
            MessageDigest.isEqual(pbkdf2(password, Base64.decode(salt, Base64.NO_WRAP), iterations), Base64.decode(hash, Base64.NO_WRAP))

    companion object {
        /** Local-only check, so fewer rounds than the server (which uses 600k) keep sign-in quick. */
        private const val ITERATIONS = 210_000

        fun create(code: String, password: String): OfflineVerifier {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return OfflineVerifier(
                code.trim().uppercase(),
                Base64.encodeToString(salt, Base64.NO_WRAP),
                Base64.encodeToString(pbkdf2(password, salt, ITERATIONS), Base64.NO_WRAP),
                ITERATIONS,
            )
        }

        private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }
    }
}

/**
 * Persists the session. The bearer token and offline verifier are encrypted with an AES-GCM key
 * that lives in the Android Keystore (hardware-backed where the phone supports it), so a copy of
 * the app's files alone cannot replay the session or brute-force the verifier.
 */
class SessionStore(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true }

    var token: String?
        get() = decrypt(prefs.getString(K_TOKEN, null))
        set(value) = prefs.edit().putString(K_TOKEN, value?.let(::encrypt)).apply()

    var user: SessionUser?
        get() = prefs.getString(K_USER, null)?.let { runCatching { json.decodeFromString(SessionUser.serializer(), it) }.getOrNull() }
        set(value) = prefs.edit().putString(K_USER, value?.let { json.encodeToString(SessionUser.serializer(), it) }).apply()

    var verifier: OfflineVerifier?
        get() = decrypt(prefs.getString(K_VERIFIER, null))?.let { runCatching { json.decodeFromString(OfflineVerifier.serializer(), it) }.getOrNull() }
        set(value) = prefs.edit().putString(K_VERIFIER, value?.let { encrypt(json.encodeToString(OfflineVerifier.serializer(), it)) }).apply()

    /** Last time the server confirmed this session (epoch millis); starts the 7-day offline window. */
    var lastServerCheck: Long
        get() = prefs.getLong(K_CHECK, 0)
        set(value) = prefs.edit().putLong(K_CHECK, value).apply()

    /** Stable id for this install, registered with the server at sign-in (PRD §7.2). */
    val deviceId: String
        get() = prefs.getString(K_DEVICE, null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString(K_DEVICE, it).apply()
        }

    var lastSyncAt: Long
        get() = prefs.getLong(K_LAST_SYNC, 0)
        set(value) = prefs.edit().putLong(K_LAST_SYNC, value).apply()

    /**
     * Tokens of sessions signed out while offline. Still valid on the server until revoked, so they
     * are kept (encrypted) and ended at the next server contact, then forgotten.
     */
    var pendingRevocations: List<String>
        get() = decrypt(prefs.getString(K_REVOKE, null))?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        set(value) = prefs.edit().putString(K_REVOKE, value.takeIf { it.isNotEmpty() }?.let { encrypt(it.joinToString("\n")) }).apply()

    var syncCursor: Long
        get() = prefs.getLong(K_CURSOR, 0)
        set(value) = prefs.edit().putLong(K_CURSOR, value).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** Null if absent or unreadable (e.g. the key was wiped): the user simply signs in again. */
    private fun decrypt(stored: String?): String? {
        if (stored == null) return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            cipher.doFinal(bytes, 12, bytes.size - 12).decodeToString()
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val KEY_ALIAS = "griha_session_key"
        const val K_TOKEN = "token"
        const val K_USER = "user"
        const val K_VERIFIER = "verifier"
        const val K_CHECK = "last_server_check"
        const val K_DEVICE = "device_id"
        const val K_LAST_SYNC = "last_sync_at"
        const val K_CURSOR = "sync_cursor"
        const val K_REVOKE = "pending_revocations"
    }
}
