package io.github.funnyhell.daymark.data.sync

import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Runs both sides of an exchange over a real socket on the loopback interface. */
class SyncProtocolTest {
    private val phone = SyncIdentity("phone-id", "Phone")
    private val tablet = SyncIdentity("tablet-id", "Tablet")
    private val phoneData = "phone data ".repeat(1000).toByteArray()
    private val tabletData = "tablet data".toByteArray()

    private class Side(val exchange: SyncExchange, val received: ByteArray?)

    /** The phone hosts, the tablet joins. */
    private fun exchange(
        hostCode: String?,
        hostKeys: Map<String, ByteArray>,
        auth: SyncAuth,
    ): Pair<Side, Side> = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            var hostReceived: ByteArray? = null
            var joinReceived: ByteArray? = null
            val host = async(Dispatchers.IO) {
                runCatching {
                    val socket = withContext(Dispatchers.IO) { server.accept() }
                    SyncProtocol.host(socket, phone, hostCode, { hostKeys[it] }, { phoneData }, { hostReceived = it })
                }
            }
            val join = async(Dispatchers.IO) {
                runCatching { SyncProtocol.join("127.0.0.1", server.localPort, tablet, auth, { tabletData }, { joinReceived = it }) }
            }
            val hostResult = host.await().getOrElse { throw it }
            val joinResult = join.await().getOrElse { throw it }
            Side(hostResult, hostReceived) to Side(joinResult, joinReceived)
        }
    }

    private fun expectFailure(reason: SyncException.Reason, block: () -> Unit) {
        try {
            block()
            fail("Expected $reason")
        } catch (e: SyncException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun pairingWithCode_exchangesData_andGivesBothSidesTheSameKey() {
        val (host, join) = exchange("123456", emptyMap(), SyncAuth.Code("123456"))
        assertEquals("tablet-id", host.exchange.peerId)
        assertEquals("Tablet", host.exchange.peerName)
        assertEquals("phone-id", join.exchange.peerId)
        assertArrayEquals(tabletData, host.received)
        assertArrayEquals(phoneData, join.received)
        val key = host.exchange.newKey
        assertNotNull(key)
        assertEquals(32, key!!.size)
        assertArrayEquals(key, join.exchange.newKey)
    }

    @Test
    fun pairedDevices_syncWithoutCode() {
        val key = ByteArray(32) { it.toByte() }
        val (host, join) = exchange(null, mapOf("tablet-id" to key), SyncAuth.Key(key))
        assertArrayEquals(tabletData, host.received)
        assertArrayEquals(phoneData, join.received)
        assertNull(host.exchange.newKey)
        assertNull(join.exchange.newKey)
    }

    @Test
    fun wrongCode_isRejected() {
        expectFailure(SyncException.Reason.WRONG_CODE) { exchange("123456", emptyMap(), SyncAuth.Code("654321")) }
    }

    @Test
    fun unknownOrWrongKey_isRejected() {
        val key = ByteArray(32) { 1 }
        expectFailure(SyncException.Reason.NOT_PAIRED) { exchange(null, emptyMap(), SyncAuth.Key(key)) }
        expectFailure(SyncException.Reason.NOT_PAIRED) { exchange(null, mapOf("tablet-id" to ByteArray(32) { 2 }), SyncAuth.Key(key)) }
    }

    @Test
    fun codeIsRefused_whenTheHostIsNotShowingOne() {
        expectFailure(SyncException.Reason.NOT_PAIRED) { exchange(null, emptyMap(), SyncAuth.Code("123456")) }
    }

    @Test
    fun codes_areSixDigits() {
        repeat(20) { assertTrue(SyncProtocol.newCode().matches(Regex("\\d{6}"))) }
    }
}
