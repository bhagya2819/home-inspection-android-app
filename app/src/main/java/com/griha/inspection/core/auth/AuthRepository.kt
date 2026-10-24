package com.griha.inspection.core.auth

import android.os.Build
import androidx.room.withTransaction
import com.griha.inspection.BuildConfig
import com.griha.inspection.core.database.GrihaDatabase
import com.griha.inspection.core.network.ApiClient
import com.griha.inspection.core.network.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

sealed interface AuthState {
    /** Login screen. [message] explains why (session ended, offline window passed). */
    data class SignedOut(val message: String? = null) : AuthState

    /**
     * Using the app. [canSync] is false after an offline sign-in (no server token yet): local work is
     * fine, and the sync screen asks for an online sign-in.
     */
    data class SignedIn(val user: SessionUser, val canSync: Boolean) : AuthState
}

/**
 * One login for online and offline use (decision 2026-10-20):
 * - First sign-in on a device must be online; the server issues a token, and we keep a local
 *   PBKDF2 verifier so the same person can sign in again without a connection.
 * - The app stays usable offline for [OFFLINE_WINDOW_MS] after the server last confirmed the
 *   session. After that, signing in needs the internet again (that is when revocations apply).
 * - Any 401 from the server (account disabled, session revoked) signs the user out at once;
 *   local data stays on the phone and syncs after the next sign-in.
 */
class AuthRepository(
    private val store: SessionStore,
    private val api: ApiClient,
    private val db: GrihaDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var cachedToken: String? = null

    /**
     * After an offline sign-in, the credentials are held in memory only (never written to disk) so
     * the online sign-in can complete by itself once the server is reachable. Gone when the process ends.
     */
    @Volatile private var pendingOnline: Pair<String, String>? = null
    private val _state = MutableStateFlow(initialState())
    val state = _state.asStateFlow()

    val currentUser: SessionUser? get() = (state.value as? AuthState.SignedIn)?.user

    fun token(): String? = cachedToken

    private fun initialState(): AuthState {
        val user = store.user ?: return AuthState.SignedOut()
        if (clock() - store.lastServerCheck > OFFLINE_WINDOW_MS) {
            return AuthState.SignedOut("It's been more than 7 days since this phone reached the Griha server. Connect to the internet and sign in.")
        }
        cachedToken = store.token
        return AuthState.SignedIn(user, canSync = cachedToken != null)
    }

    /** Online first; if the server can't be reached, falls back to the offline verifier. */
    suspend fun login(employeeCode: String, password: String): Result<SessionUser> = withContext(Dispatchers.IO) {
        val code = employeeCode.trim().uppercase()
        try {
            val res = api.post("/v1/auth/login", buildJsonObject {
                put("employee_code", code)
                put("password", password)
                putJsonObject("device") {
                    put("id", store.deviceId)
                    put("label", "${Build.MANUFACTURER} ${Build.MODEL}".take(100))
                    put("platform", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    put("app_version", BuildConfig.VERSION_NAME)
                }
            })
            val user = parseUser(res["user"]!!.jsonObject)
            val token = res["token"]!!.jsonPrimitive.content
            store.token = token
            store.user = user
            store.verifier = OfflineVerifier.create(code, password)
            store.lastServerCheck = clock()
            cachedToken = token
            pendingOnline = null
            adoptPreLoginRecords(user.id)
            _state.value = AuthState.SignedIn(user, canSync = true)
            Result.success(user)
        } catch (e: ApiException) {
            Result.failure(e)
        } catch (e: IOException) {
            offlineLogin(code, password) ?: Result.failure(
                IOException("Can't reach the Griha server. The first sign-in on a phone, and any sign-in after 7 days offline, needs the internet.", e)
            )
        }
    }

    private fun offlineLogin(code: String, password: String): Result<SessionUser>? {
        val verifier = store.verifier ?: return null
        val user = store.user ?: return null
        if (clock() - store.lastServerCheck > OFFLINE_WINDOW_MS) return null
        if (!verifier.matches(code, password)) return Result.failure(IOException("Wrong employee code or password."))
        cachedToken = store.token
        if (cachedToken == null) pendingOnline = code to password
        _state.value = AuthState.SignedIn(user, canSync = cachedToken != null)
        return Result.success(user)
    }

    /**
     * Completes a sign-in that was done offline, now that the server may be reachable. Returns true
     * if the app now has a server session. Called by the sync engine before it gives up for lack of one.
     */
    suspend fun completeOnlineSignIn(): Boolean {
        val (code, password) = pendingOnline ?: return false
        val result = login(code, password)
        // Wrong password online (changed elsewhere) or account disabled: stop retrying, ask the user.
        // The developer lock is neither: keep the offline sign-in so work resumes after an unlock.
        val error = result.exceptionOrNull()
        if (error is ApiException && !error.isAppLocked) {
            pendingOnline = null
            onSessionInvalid()
        }
        return cachedToken != null
    }

    /** Runs while the session still works, e.g. to stop and upload location sharing (set by AppContainer). */
    var beforeLogout: (suspend () -> Unit)? = null

    /** Signs out on this phone. Local inspections stay and sync after the next sign-in. */
    suspend fun logout() = withContext(Dispatchers.IO) {
        // Bounded: signing out must never hang on a slow network.
        runCatching { kotlinx.coroutines.withTimeoutOrNull(8_000) { beforeLogout?.invoke() } }
        cachedToken?.let { token ->
            try {
                api.post("/v1/auth/logout", null)
            } catch (e: ApiException) {
                // The server already ended this session (401) or refused: nothing left to revoke.
            } catch (_: IOException) {
                // Offline: end the server session at the next contact, so the token isn't left valid.
                store.pendingRevocations = (store.pendingRevocations + token).takeLast(10)
            }
        }
        store.token = null
        cachedToken = null
        pendingOnline = null
        _state.value = AuthState.SignedOut()
    }

    suspend fun changePassword(current: String, new: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            api.post("/v1/auth/password", buildJsonObject {
                put("current_password", current)
                put("new_password", new)
            })
            val user = currentUser ?: return@withContext Result.success(Unit)
            val updated = user.copy(mustChangePassword = false)
            store.user = updated
            store.verifier = OfflineVerifier.create(user.employeeCode, new)
            _state.value = AuthState.SignedIn(updated, canSync = true)
            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /** Ends sessions signed out while offline. Best effort; a token is forgotten once the server answered. */
    suspend fun revokePendingSessions() = withContext(Dispatchers.IO) {
        for (token in store.pendingRevocations) {
            try {
                api.postAs(token, "/v1/auth/logout")
            } catch (e: ApiException) {
                // 401: already expired or revoked — done either way.
            } catch (_: IOException) {
                return@withContext // still offline; keep the rest for next time
            }
            store.pendingRevocations = store.pendingRevocations - token
        }
    }

    /** Any successful authenticated request: renews the offline window, refreshes name/role. */
    fun onServerContact(serverUser: JsonObject? = null) {
        store.lastServerCheck = clock()
        val current = currentUser ?: return
        if (serverUser != null) {
            val fresh = parseUser(serverUser)
            if (fresh != current) {
                store.user = fresh
                _state.value = AuthState.SignedIn(fresh, canSync = cachedToken != null)
            }
        }
    }

    /** The server rejected our token: account disabled, password reset, or session revoked. */
    fun onSessionInvalid() {
        store.token = null
        cachedToken = null
        _state.value = AuthState.SignedOut("Your session has ended. Sign in again to continue. Your work on this phone is safe.")
    }

    /** Inspections started before the very first sign-in carry a placeholder; give them to this user. */
    private suspend fun adoptPreLoginRecords(userId: String) {
        db.withTransaction {
            db.inspectionDao().reassignInspector(PLACEHOLDER_USER, userId)
            db.findingDao().reassignCreator(PLACEHOLDER_USER, userId)
        }
    }

    private fun parseUser(o: JsonObject) = SessionUser(
        id = o["id"]!!.jsonPrimitive.content,
        employeeCode = o["employee_code"]!!.jsonPrimitive.content,
        displayName = o["display_name"]!!.jsonPrimitive.content,
        role = o["role"]!!.jsonPrimitive.content,
        mustChangePassword = o["must_change_password"]?.jsonPrimitive?.boolean ?: false,
    )

    companion object {
        const val OFFLINE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        /** User id recorded on work done before the first sign-in (M1–M3 builds). */
        const val PLACEHOLDER_USER = "local-inspector"
    }
}
