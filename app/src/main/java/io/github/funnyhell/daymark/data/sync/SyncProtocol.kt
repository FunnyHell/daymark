package io.github.funnyhell.daymark.data.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason {
        WRONG_CODE,
        NOT_PAIRED,
        INCOMPATIBLE,
        NETWORK,
        INVALID_DATA,

        /** A paired device was not found on the network (Daymark is not open there, or another network). */
        NOT_FOUND,
    }
}

/** This device as the other side sees it. [id] is random and stable; [name] is for display. */
data class SyncIdentity(val id: String, val name: String)

/** How the connecting device proves it may sync: the code shown on the host, or the key from an earlier pairing. */
sealed interface SyncAuth {
    data class Code(val code: String) : SyncAuth
    class Key(val key: ByteArray) : SyncAuth
}

/** Who was on the other side; [newKey] is set when this exchange paired the two devices. */
class SyncExchange(val peerId: String, val peerName: String, val newKey: ByteArray?)

/**
 * One exchange of data between two devices over a TCP connection on the local network (ADR-030, ADR-033).
 *
 * The first time, the host shows a six-digit code and the other device types it; the exchange is encrypted with
 * a key derived from the code (PBKDF2) and the host hands out a random 256-bit key that both devices keep. Later
 * exchanges between those devices use that key directly, so no code is needed. Each side sends its whole data
 * set once (compressed, AES-GCM); both then merge with [SyncMerger], which is order-independent.
 *
 * Wire format, client first: magic, version, mode, device id, device name, then (code mode) a 16-byte salt, and
 * one data frame. Host: a status code, its id and name, (code mode) a frame with the pairing key, and its data
 * frame. A frame is a length-prefixed IV + ciphertext.
 */
object SyncProtocol {
    const val VERSION = 2
    private const val MAGIC = "DAYMARK-SYNC"
    private const val MODE_CODE = 0
    private const val MODE_KEY = 1
    private const val STATUS_OK = 0
    private const val STATUS_WRONG_CODE = 1
    private const val STATUS_INCOMPATIBLE = 2
    private const val STATUS_NOT_PAIRED = 3
    private const val MAX_FRAME = 64 * 1024 * 1024
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private const val KEY_ITERATIONS = 100_000
    private const val TIMEOUT_MS = 60_000

    private val random = SecureRandom()

    /** A new six-digit code for the host to show. */
    fun newCode(): String = (0 until 6).joinToString("") { random.nextInt(10).toString() }

    /**
     * Host side of one exchange on an accepted [socket]. [code] is the code currently shown, or null when only
     * paired devices are accepted; [keyFor] returns the stored key of a paired device.
     */
    suspend fun host(
        socket: Socket,
        me: SyncIdentity,
        code: String?,
        keyFor: suspend (peerId: String) -> ByteArray?,
        localPayload: suspend () -> ByteArray,
        applyRemote: suspend (ByteArray) -> Unit,
    ): SyncExchange = withContext(Dispatchers.IO) {
        socket.use {
            socket.soTimeout = TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())
            fun refuse(status: Int, reason: SyncException.Reason, message: String): Nothing {
                network {
                    output.writeInt(status)
                    output.flush()
                }
                throw SyncException(reason, message)
            }
            val (mode, peerId, peerName) = network {
                val magic = input.readUTF()
                val version = input.readInt()
                if (magic != MAGIC || version != VERSION) refuse(STATUS_INCOMPATIBLE, SyncException.Reason.INCOMPATIBLE, "Other device uses another sync version")
                Triple(input.readInt(), input.readUTF(), input.readUTF())
            }
            val key: SecretKey = when (mode) {
                MODE_CODE -> {
                    val salt = network { readExactly(input, SALT_BYTES) }
                    if (code == null) refuse(STATUS_NOT_PAIRED, SyncException.Reason.NOT_PAIRED, "Not showing a code")
                    deriveKey(code, salt)
                }
                MODE_KEY -> keyFor(peerId)?.let(::aesKey) ?: refuse(STATUS_NOT_PAIRED, SyncException.Reason.NOT_PAIRED, "Unknown device")
                else -> refuse(STATUS_INCOMPATIBLE, SyncException.Reason.INCOMPATIBLE, "Unknown mode")
            }
            val frame = network { readFrame(input) }
            val remote = try {
                decrypt(key, frame)
            } catch (e: GeneralSecurityException) {
                if (mode == MODE_CODE) refuse(STATUS_WRONG_CODE, SyncException.Reason.WRONG_CODE, "Wrong code")
                refuse(STATUS_NOT_PAIRED, SyncException.Reason.NOT_PAIRED, "Pairing key does not match")
            }
            val newKey = if (mode == MODE_CODE) ByteArray(KEY_BYTES).also(random::nextBytes) else null
            val local = encrypt(key, localPayload())
            network {
                output.writeInt(STATUS_OK)
                output.writeUTF(me.id)
                output.writeUTF(me.name)
                if (newKey != null) writeFrame(output, encrypt(key, newKey))
                writeFrame(output, local)
                output.flush()
            }
            applyRemote(remote)
            SyncExchange(peerId, peerName, newKey)
        }
    }

    /** Client side: connects to the host, exchanges data and returns who the host was. */
    suspend fun join(
        address: String,
        port: Int,
        me: SyncIdentity,
        auth: SyncAuth,
        localPayload: suspend () -> ByteArray,
        applyRemote: suspend (ByteArray) -> Unit,
    ): SyncExchange = withContext(Dispatchers.IO) {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val key = when (auth) {
            is SyncAuth.Code -> deriveKey(auth.code, salt)
            is SyncAuth.Key -> aesKey(auth.key)
        }
        val local = encrypt(key, localPayload())
        Socket().use { socket ->
            val response = network {
                socket.connect(InetSocketAddress(address, port), TIMEOUT_MS)
                socket.soTimeout = TIMEOUT_MS
                val input = DataInputStream(socket.getInputStream().buffered())
                val output = DataOutputStream(socket.getOutputStream().buffered())
                output.writeUTF(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(if (auth is SyncAuth.Code) MODE_CODE else MODE_KEY)
                output.writeUTF(me.id)
                output.writeUTF(me.name)
                if (auth is SyncAuth.Code) output.write(salt)
                writeFrame(output, local)
                output.flush()
                when (input.readInt()) {
                    STATUS_OK -> Unit
                    STATUS_WRONG_CODE -> throw SyncException(SyncException.Reason.WRONG_CODE, "Wrong code")
                    STATUS_NOT_PAIRED -> throw SyncException(SyncException.Reason.NOT_PAIRED, "The other device does not know this one")
                    else -> throw SyncException(SyncException.Reason.INCOMPATIBLE, "Other device uses another sync version")
                }
                val peerId = input.readUTF()
                val peerName = input.readUTF()
                val keyFrame = if (auth is SyncAuth.Code) readFrame(input) else null
                Response(peerId, peerName, keyFrame, readFrame(input))
            }
            val (newKey, remote) = try {
                response.keyFrame?.let { decrypt(key, it) } to decrypt(key, response.dataFrame)
            } catch (e: GeneralSecurityException) {
                throw SyncException(SyncException.Reason.WRONG_CODE, "Could not read the other device's data", e)
            }
            applyRemote(remote)
            SyncExchange(response.peerId, response.peerName, newKey)
        }
    }

    private class Response(val peerId: String, val peerName: String, val keyFrame: ByteArray?, val dataFrame: ByteArray)

    private inline fun <T> network(block: () -> T): T = try {
        block()
    } catch (e: SyncException) {
        throw e
    } catch (e: IOException) {
        throw SyncException(SyncException.Reason.NETWORK, e.message ?: "Connection failed", e)
    }

    private fun aesKey(bytes: ByteArray): SecretKey = SecretKeySpec(bytes, "AES")

    private fun deriveKey(code: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(code.toCharArray(), salt, KEY_ITERATIONS, 256)
        return aesKey(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)
    }

    private fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC.toByteArray())
        return iv + cipher.doFinal(gzip(plain))
    }

    private fun decrypt(key: SecretKey, frame: ByteArray): ByteArray {
        if (frame.size <= IV_BYTES) throw GeneralSecurityException("Frame too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, frame.copyOfRange(0, IV_BYTES)))
        cipher.updateAAD(MAGIC.toByteArray())
        return gunzip(cipher.doFinal(frame, IV_BYTES, frame.size - IV_BYTES))
    }

    private fun writeFrame(output: DataOutputStream, bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        val size = input.readInt()
        if (size !in 1..MAX_FRAME) throw IOException("Invalid frame size $size")
        return readExactly(input, size)
    }

    private fun readExactly(input: DataInputStream, size: Int): ByteArray =
        ByteArray(size).also { bytes ->
            try {
                input.readFully(bytes)
            } catch (e: EOFException) {
                throw IOException("Connection closed", e)
            }
        }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}
