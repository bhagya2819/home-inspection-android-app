package com.homeinspection.app.core.lock

import android.content.SharedPreferences
import com.homeinspection.app.core.network.ApiClient
import com.homeinspection.app.core.network.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.IOException

/**
 * The developer lock, set only on the server (`home-inspection-api lock`). Rules, in order of importance:
 * - Locks ONLY on the exact server signal (HTTP 423 + code APP_LOCKED, see [ApiException.isAppLocked]),
 *   over HTTPS to our own domain. Timeouts, other errors, captive portals and bad JSON change nothing.
 * - Unlocks ONLY on a 200 from /v1/app/status whose "locked" is the JSON boolean false.
 * - Remembered on the phone, so going offline doesn't lift it; checked again at every app start.
 * - Deletes nothing and signs nobody out: unsynced work stays queued and uploads after an unlock.
 */
class AppLock(private val prefs: SharedPreferences) {

    /** null = not locked; otherwise the server's message for the lock screen. */
    private val _message = MutableStateFlow(if (prefs.getBoolean(K_LOCKED, false)) prefs.getString(K_MESSAGE, null).orEmpty() else null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val isLocked: Boolean get() = _message.value != null

    /** From [ApiClient] whenever any response is the lock signal. Written synchronously (commit) first. */
    fun onLockedByServer(message: String) {
        prefs.edit().putBoolean(K_LOCKED, true).putString(K_MESSAGE, message).commit()
        _message.value = message
    }

    /**
     * Asks the server. Returns true if the app is locked afterwards. A 423 lands in [onLockedByServer]
     * through the ApiClient callback; only an explicit "locked": false unlocks.
     */
    suspend fun refresh(api: ApiClient): Boolean {
        try {
            val res = api.get("/v1/app/status")
            val locked = (res["locked"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            if (locked == false && isLocked) {
                prefs.edit().putBoolean(K_LOCKED, false).remove(K_MESSAGE).commit()
                _message.value = null
            }
        } catch (_: ApiException) {
            // 423 APP_LOCKED was already recorded by the callback; anything else proves nothing.
        } catch (_: IOException) {
            // Offline or unreachable: keep whatever we knew.
        } catch (_: RuntimeException) {
            // Malformed JSON from something in between: keep whatever we knew.
        }
        return isLocked
    }

    private companion object {
        const val K_LOCKED = "locked"
        const val K_MESSAGE = "message"
    }
}
