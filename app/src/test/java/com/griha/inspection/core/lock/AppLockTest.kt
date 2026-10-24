package com.griha.inspection.core.lock

import android.content.SharedPreferences
import com.griha.inspection.core.network.ApiClient
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/** The lock must move only on the exact server signals; everything else leaves it where it was. */
class AppLockTest {
    private lateinit var server: HttpServer
    @Volatile private var status = 200
    @Volatile private var body = """{"locked":false}"""

    private val prefs = FakePrefs()
    private lateinit var lock: AppLock
    private lateinit var api: ApiClient

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.start()
        lock = AppLock(prefs)
        api = ApiClient("http://127.0.0.1:${server.address.port}", token = { null }, onAppLocked = lock::onLockedByServer)
    }

    @After
    fun stop() = server.stop(0)

    private fun answer(status: Int, body: String) { this.status = status; this.body = body }
    private fun refresh() = runBlocking { lock.refresh(api) }
    private fun lockedBody(msg: String) = """{"error":{"code":"APP_LOCKED","message":"$msg"}}"""

    @Test
    fun unlockedStaysUnlockedOnAnythingButTheLockSignal() {
        for ((s, b) in listOf(
            200 to """{"locked":false}""",
            423 to """{"error":{"code":"LOCKED","message":"webdav"}}""", // right status, wrong code
            503 to lockedBody("x"),                                      // right code, wrong status
            403 to lockedBody("x"),
            500 to "",
            200 to "<html>captive portal</html>",
            200 to """{"locked":true}""",                                // status endpoint never locks by itself
        )) {
            answer(s, b)
            assertFalse("$s $b", refresh())
        }
        assertNull(lock.message.value)
    }

    @Test
    fun locksOnSignalFromAnyCallAndSurvivesRestart() {
        answer(423, lockedBody("Contact Ronik"))
        assertTrue(refresh())
        assertEquals("Contact Ronik", lock.message.value)

        // Any other API call carrying the signal locks too (sync, login, …).
        val fresh = AppLock(FakePrefs())
        val other = ApiClient("http://127.0.0.1:${server.address.port}", token = { null }, onAppLocked = fresh::onLockedByServer)
        runCatching { runBlocking { other.post("/v1/sync/push", null) } }
        assertTrue(fresh.isLocked)

        // Process restart: read back from preferences, still locked, without any network.
        server.stop(0)
        val restarted = AppLock(prefs)
        assertTrue(restarted.isLocked)
        assertEquals("Contact Ronik", restarted.message.value)
    }

    @Test
    fun lockedStaysLockedUntilAnExplicitFalse() {
        answer(423, lockedBody("locked"))
        assertTrue(refresh())
        for ((s, b) in listOf(
            500 to "",
            502 to "<html>bad gateway</html>",
            200 to "{}",                       // field missing
            200 to """{"locked":"false"}""",   // a string, not a boolean
            200 to """{"locked":null}""",
            200 to """{"locked":0}""",
            200 to "not json",
            401 to """{"error":{"code":"SESSION_INVALID","message":"x"}}""",
        )) {
            answer(s, b)
            assertTrue("$s $b", refresh())
        }
        server.stop(0) // offline
        assertTrue(refresh())
        assertTrue(AppLock(prefs).isLocked)
    }

    @Test
    fun explicitFalseUnlocksAndIsRemembered() {
        answer(423, lockedBody("locked"))
        assertTrue(refresh())
        answer(200, """{"locked":false}""")
        assertFalse(refresh())
        assertNull(lock.message.value)
        assertFalse(AppLock(prefs).isLocked)
    }
}

/** In-memory SharedPreferences: enough for AppLock (booleans and strings, commit). */
private class FakePrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()
    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = map[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in map
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val puts = HashMap<String, Any?>()
        private val removes = HashSet<String>()
        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun remove(key: String) = apply { removes += key }
        override fun clear() = apply { removes += map.keys }
        override fun commit(): Boolean { removes.forEach(map::remove); map.putAll(puts); return true }
        override fun apply() { commit() }
    }
}
