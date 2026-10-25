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
    enum class Reason { WRONG_CODE, INCOMPATIBLE, NETWORK, INVALID_DATA }
}

/**
 * One exchange of data between two devices over a TCP connection on the local network (ADR-030).
 *
 * The device that shows the code is the host; the other one connects and types the code. Each side sends its
 * whole data set once, compressed and encrypted with AES-GCM under a key derived from the code (PBKDF2), so
 * data from a device that does not know the code is rejected and nothing readable crosses the network. Both
 * sides then merge what they received with [SyncMerger]; the merge is order-independent, so they end up equal.
 *
 * Wire format: magic, version, device name, 16-byte salt and one frame from the client; a status code, the
 * host's device name and one frame back. A frame is a length-prefixed IV + ciphertext.
 */
object SyncProtocol {
    const val VERSION = 1
    private const val MAGIC = "DAYMARK-SYNC"
    private const val STATUS_OK = 0
    private const val STATUS_WRONG_CODE = 1
    private const val STATUS_INCOMPATIBLE = 2
    private const val MAX_FRAME = 64 * 1024 * 1024
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_ITERATIONS = 100_000
    private const val TIMEOUT_MS = 60_000

    private val random = SecureRandom()

    /** A new six-digit code for the host to show. */
    fun newCode(): String = (0 until 6).joinToString("") { random.nextInt(10).toString() }

    /**
     * Host side of one exchange on an accepted [socket]. Returns the other device's name after [applyRemote]
     * has processed its data.
     */
    suspend fun host(
        socket: Socket,
        code: String,
        deviceName: String,
        localPayload: suspend () -> ByteArray,
        applyRemote: suspend (ByteArray) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        socket.use {
            socket.soTimeout = TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())
            val (peerName, salt) = network {
                val magic = input.readUTF()
                val version = input.readInt()
                if (magic != MAGIC || version != VERSION) {
                    output.writeInt(STATUS_INCOMPATIBLE)
                    output.flush()
                    throw SyncException(SyncException.Reason.INCOMPATIBLE, "Other device uses another sync version")
                }
                input.readUTF() to readExactly(input, SALT_BYTES)
            }
            val key = deriveKey(code, salt)
            val frame = network { readFrame(input) }
            val remote = try {
                decrypt(key, frame)
            } catch (e: GeneralSecurityException) {
                network {
                    output.writeInt(STATUS_WRONG_CODE)
                    output.flush()
                }
                throw SyncException(SyncException.Reason.WRONG_CODE, "Wrong code", e)
            }
            val local = encrypt(key, localPayload())
            network {
                output.writeInt(STATUS_OK)
                output.writeUTF(deviceName)
                writeFrame(output, local)
                output.flush()
            }
            applyRemote(remote)
            peerName
        }
    }

    /** Client side: connects to the host, exchanges data and returns the host's device name. */
    suspend fun join(
        address: String,
        port: Int,
        code: String,
        deviceName: String,
        localPayload: suspend () -> ByteArray,
        applyRemote: suspend (ByteArray) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val key = deriveKey(code, salt)
        val local = encrypt(key, localPayload())
        Socket().use { socket ->
            val (peerName, frame) = network {
                socket.connect(InetSocketAddress(address, port), TIMEOUT_MS)
                socket.soTimeout = TIMEOUT_MS
                val input = DataInputStream(socket.getInputStream().buffered())
                val output = DataOutputStream(socket.getOutputStream().buffered())
                output.writeUTF(MAGIC)
                output.writeInt(VERSION)
                output.writeUTF(deviceName)
                output.write(salt)
                writeFrame(output, local)
                output.flush()
                when (input.readInt()) {
                    STATUS_OK -> Unit
                    STATUS_WRONG_CODE -> throw SyncException(SyncException.Reason.WRONG_CODE, "Wrong code")
                    else -> throw SyncException(SyncException.Reason.INCOMPATIBLE, "Other device uses another sync version")
                }
                input.readUTF() to readFrame(input)
            }
            val remote = try {
                decrypt(key, frame)
            } catch (e: GeneralSecurityException) {
                throw SyncException(SyncException.Reason.WRONG_CODE, "Could not read the other device's data", e)
            }
            applyRemote(remote)
            peerName
        }
    }

    private inline fun <T> network(block: () -> T): T = try {
        block()
    } catch (e: SyncException) {
        throw e
    } catch (e: IOException) {
        throw SyncException(SyncException.Reason.NETWORK, e.message ?: "Connection failed", e)
    }

    private fun deriveKey(code: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(code.toCharArray(), salt, KEY_ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
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
