package io.github.funnyhell.daymark.data.sync

import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Runs both sides of an exchange over a real socket on the loopback interface. */
class SyncProtocolTest {
    private val phoneData = "phone data ".repeat(1000).toByteArray()
    private val tabletData = "tablet data".toByteArray()

    private class Result(val peer: String, val received: ByteArray?)

    private fun exchange(hostCode: String, joinCode: String): Pair<Result, Result> = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            var hostReceived: ByteArray? = null
            var joinReceived: ByteArray? = null
            val host = async(Dispatchers.IO) {
                runCatching {
                    val socket = withContext(Dispatchers.IO) { server.accept() }
                    SyncProtocol.host(socket, hostCode, "Phone", { phoneData }, { hostReceived = it })
                }
            }
            val join = async(Dispatchers.IO) {
                runCatching {
                    SyncProtocol.join("127.0.0.1", server.localPort, joinCode, "Tablet", { tabletData }, { joinReceived = it })
                }
            }
            val hostPeer = host.await()
            val joinPeer = join.await()
            Result(hostPeer.getOrElse { throw it }, hostReceived) to Result(joinPeer.getOrElse { throw it }, joinReceived)
        }
    }

    @Test
    fun matchingCode_exchangesDataBothWays() {
        val (host, join) = exchange("123456", "123456")
        assertEquals("Tablet", host.peer)
        assertEquals("Phone", join.peer)
        assertArrayEquals(tabletData, host.received)
        assertArrayEquals(phoneData, join.received)
    }

    @Test
    fun wrongCode_isRejected() {
        try {
            exchange("123456", "654321")
            fail("Expected a wrong-code error")
        } catch (e: SyncException) {
            assertEquals(SyncException.Reason.WRONG_CODE, e.reason)
        }
    }

    @Test
    fun codes_areSixDigits() {
        repeat(20) { assertTrue(SyncProtocol.newCode().matches(Regex("\\d{6}"))) }
    }
}
