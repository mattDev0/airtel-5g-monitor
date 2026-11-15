package com.airtel.monitor.core.network

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RouterSessionAndWriterTest {

    private class FakeTransport : RouterTransport {
        val sent = mutableListOf<JSONObject>()
        val handlers = mutableMapOf<String, (JSONObject) -> String>()
        private var tokenSeq = 0

        init {
            on(232) { """{"success":true,"token":"CHALLENGE"}""" }
            on(100) { """{"success":true,"sessionId":"SID1"}""" }
            on(233) { """{"success":true,"token":"WTOK${++tokenSeq}"}""" }
        }

        fun on(cmd: Int, method: String? = null, handler: (JSONObject) -> String) {
            handlers[if (method == null) "$cmd" else "$cmd:$method"] = handler
        }

        override fun post(url: String, body: String, timeoutSeconds: Long): String {
            val req = JSONObject(body)
            sent.add(req)
            val key = "${req.getInt("cmd")}:${req.optString("method")}"
            val handler = handlers[key] ?: handlers["${req.getInt("cmd")}"]
            return handler?.invoke(req) ?: """{"success":true}"""
        }
    }

    private val settings = object : RouterSettings {
        override val routerHost = "192.168.1.1"
        override val username = "root"
        override val password = "admin"
    }

    @Test
    fun `wrong password returns NotLoggedIn`() {
        val transport = FakeTransport().apply {
            on(100, "POST") { """{"success":false,"message":"LOGIN_FAIL"}""" }
        }
        val session = RouterSession(settings, transport)
        val res = session.login()
        assertTrue(res is RouterResult.NotLoggedIn)
    }

    @Test
    fun `LIMITED_ACCESS returns LimitedAccess without re-login`() {
        val transport = FakeTransport().apply {
            on(25, "GET") { """{"success":false,"message":"LIMITED_ACCESS"}""" }
        }
        val session = RouterSession(settings, transport)
        val res = session.execute(25)
        assertTrue(res is RouterResult.LimitedAccess)
        assertEquals(1, transport.sent.count { it.getInt("cmd") == 100 })
    }

    @Test
    fun `SettingsWriter reads current, merges changes, posts with fresh token, and verifies`() = runTest {
        val state = JSONObject("""{"success":true,"a":"1","b":"old"}""")
        val transport = FakeTransport().apply {
            on(300, "GET") { state.toString() }
            on(300, "POST") { req ->
                state.put("b", req.getString("b"))
                """{"success":true}"""
            }
        }
        val session = RouterSession(settings, transport)
        val writer = SettingsWriter(session, delayFn = {})

        val res = writer.write(
            WriteSpec(
                cmd = 300,
                buildPayload = { cur -> mapOf("a" to cur.getString("a"), "b" to "new") },
                verify = { it.optString("b") == "new" },
                parseResult = { it.getString("b") }
            )
        )

        assertEquals(RouterResult.Ok("new"), res)
        val post = transport.sent.single { it.getInt("cmd") == 300 && it.getString("method") == "POST" }
        assertEquals("1", post.getString("a"))
        assertEquals("new", post.getString("b"))
        assertTrue(post.getString("token").startsWith("WTOK"))
    }

    @Test
    fun `SettingsWriter surfaces raw response on verify failure without retrying POST`() = runTest {
        val state = JSONObject("""{"success":true,"b":"kept"}""")
        val transport = FakeTransport().apply {
            on(300, "GET") { state.toString() }
            on(300, "POST") { """{"success":true}""" }
        }
        val session = RouterSession(settings, transport)
        val writer = SettingsWriter(session, delayFn = {})

        val res = writer.write(
            WriteSpec(
                cmd = 300,
                buildPayload = { mapOf("b" to "desired") },
                verify = { it.optString("b") == "desired" },
                parseResult = { it.getString("b") },
                verifyRetries = 2
            )
        )

        assertTrue(res is RouterResult.BadResponse)
        val bad = res as RouterResult.BadResponse
        assertTrue(bad.rawBody.contains("kept"))
        assertEquals(1, transport.sent.count { it.getInt("cmd") == 300 && it.getString("method") == "POST" })
    }

    @Test
    fun `POST IOException returns Unreachable and never retries`() = runTest {
        val transport = FakeTransport().apply {
            on(300, "GET") { """{"success":true}""" }
            on(300, "POST") { throw IOException("socket reset") }
        }
        val session = RouterSession(settings, transport)
        val writer = SettingsWriter(session, delayFn = {})

        val res = writer.write(
            WriteSpec(
                cmd = 300,
                buildPayload = { mapOf("x" to "1") },
                verify = { true },
                parseResult = { Unit }
            )
        )
        assertTrue(res is RouterResult.Unreachable)
        assertEquals(1, transport.sent.count { it.getInt("cmd") == 300 && it.getString("method") == "POST" })
    }

    @Test
    fun `onLoginSuccess callback fires only after successful login`() {
        var successCount = 0
        val failTransport = FakeTransport().apply {
            on(100, "POST") { """{"success":false,"message":"LOGIN_FAIL"}""" }
        }
        val failSession = RouterSession(settings, failTransport, onLoginSuccess = { successCount++ })
        assertTrue(failSession.login() is RouterResult.NotLoggedIn)
        assertEquals(0, successCount)

        val okSession = RouterSession(settings, FakeTransport(), onLoginSuccess = { successCount++ })
        assertTrue(okSession.login() is RouterResult.Ok)
        assertEquals(1, successCount)
    }
}
