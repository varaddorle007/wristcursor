package com.drift.airmouse.core

import android.net.Network
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.math.abs

/** Something went wrong connecting; [message] is safe to show the user. */
class VncException(message: String, val unreachable: Boolean = false) : IOException(message)

/**
 * Wi-Fi with nothing to install: drives a Mac through its built-in Screen Sharing
 * (the standard VNC / RFB protocol), signing in with the Mac's own username and password.
 * Only pointer, button, wheel and arrow-key messages are sent; the screen image is never requested.
 */
class VncLink private constructor(
    private val socket: Socket,
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
) : PointerLink {

    private var x = width / 2
    private var y = height / 2
    private var buttons = 0
    private var restX = 0f
    private var restY = 0f
    // Retina Macs share a framebuffer at twice the point size; scale so speed feels the same.
    private val scale = (width / 1600f).coerceAtLeast(1f)
    private val pointer = ByteArray(6)
    private val keyMessage = ByteArray(8)

    override fun move(dx: Int, dy: Int) {
        restX += dx * scale
        restY += dy * scale
        val stepX = restX.toInt()
        val stepY = restY.toInt()
        restX -= stepX
        restY -= stepY
        x = (x + stepX).coerceIn(0, width - 1)
        y = (y + stepY).coerceIn(0, height - 1)
        sendPointer()
    }

    override fun click(button: MouseButton) {
        press(button)
        release(button)
    }

    override fun press(button: MouseButton) {
        buttons = buttons or button.mask()
        sendPointer()
    }

    override fun release(button: MouseButton) {
        buttons = buttons and button.mask().inv()
        sendPointer()
    }

    override fun scroll(steps: Int) {
        // VNC wheel: button 4 scrolls up, button 5 scrolls down; each is a press and release.
        val wheel = if (steps > 0) WHEEL_DOWN else WHEEL_UP
        repeat(abs(steps)) {
            buttons = buttons or wheel
            sendPointer()
            buttons = buttons and wheel.inv()
            sendPointer()
        }
    }

    override fun send(key: RemoteKey): Boolean {
        val keysym = when (key) {
            RemoteKey.UP -> KEYSYM_UP
            RemoteKey.DOWN -> KEYSYM_DOWN
            RemoteKey.LEFT -> KEYSYM_LEFT
            RemoteKey.RIGHT -> KEYSYM_RIGHT
            RemoteKey.OK -> KEYSYM_RETURN
            RemoteKey.BACK -> KEYSYM_ESCAPE
            RemoteKey.BACKSPACE -> KEYSYM_BACKSPACE
            else -> return false // Screen Sharing has no media keys
        }
        sendKey(keysym, down = true)
        sendKey(keysym, down = false)
        return true
    }

    override fun center(): Boolean {
        x = width / 2
        y = height / 2
        sendPointer()
        return true
    }

    // One key event per character; the Mac turns keysyms into the right letter, accents included.
    override fun type(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val keysym = keysymFor(codePoint)
            sendKey(keysym, down = true)
            sendKey(keysym, down = false)
            i += Character.charCount(codePoint)
        }
        return true
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun MouseButton.mask() = if (this == MouseButton.LEFT) 1 else 4

    private fun sendPointer() {
        pointer[0] = 5
        pointer[1] = buttons.toByte()
        pointer[2] = (x shr 8).toByte()
        pointer[3] = x.toByte()
        pointer[4] = (y shr 8).toByte()
        pointer[5] = y.toByte()
        write(pointer)
    }

    private fun sendKey(keysym: Int, down: Boolean) {
        keyMessage[0] = 4
        keyMessage[1] = if (down) 1 else 0
        keyMessage[2] = 0
        keyMessage[3] = 0
        keyMessage[4] = (keysym shr 24).toByte()
        keyMessage[5] = (keysym shr 16).toByte()
        keyMessage[6] = (keysym shr 8).toByte()
        keyMessage[7] = keysym.toByte()
        write(keyMessage)
    }

    private fun write(bytes: ByteArray) {
        runCatching {
            out.write(bytes)
            out.flush()
        }
    }

    companion object {
        private const val WHEEL_UP = 8
        private const val WHEEL_DOWN = 16
        private const val KEYSYM_RIGHT = 0xFF53
        private const val KEYSYM_LEFT = 0xFF51
        private const val KEYSYM_UP = 0xFF52
        private const val KEYSYM_DOWN = 0xFF54
        private const val KEYSYM_RETURN = 0xFF0D
        private const val KEYSYM_ESCAPE = 0xFF1B
        private const val KEYSYM_BACKSPACE = 0xFF08

        /** X11 keysym for a character: Latin-1 maps directly, everything else uses the Unicode range. */
        fun keysymFor(codePoint: Int): Int = when {
            codePoint == '\n'.code -> KEYSYM_RETURN
            codePoint == '\t'.code -> 0xFF09
            codePoint in 0x20..0x7E || codePoint in 0xA0..0xFF -> codePoint
            else -> 0x01000000 or codePoint
        }
        private const val SECURITY_NONE = 1
        private const val SECURITY_VNC = 2
        private const val SECURITY_APPLE = 30

        /** Connects and signs in. Blocking: call off the main thread. */
        fun open(network: Network?, host: String, port: Int, user: String, password: String): VncLink {
            val socket = network?.socketFactory?.createSocket() ?: Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 5_000)
                socket.soTimeout = 8_000
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val out = socket.getOutputStream()

                val version = ByteArray(12).also(input::readFully)
                if (!String(version, Charsets.US_ASCII).startsWith("RFB ")) throw VncException("That isn't a Screen Sharing computer")
                out.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
                out.flush()

                val count = input.readUnsignedByte()
                if (count == 0) throw VncException(readReason(input) ?: "The Mac refused the connection")
                val types = ByteArray(count).also(input::readFully).map { it.toInt() and 0xFF }
                when {
                    SECURITY_APPLE in types && user.isNotEmpty() -> appleSignIn(input, out, user, password)
                    SECURITY_VNC in types -> vncSignIn(input, out, password)
                    SECURITY_NONE in types -> {
                        out.write(SECURITY_NONE)
                        out.flush()
                    }
                    else -> throw VncException("This Mac's Screen Sharing sign-in isn't supported")
                }
                if (input.readInt() != 0) {
                    throw VncException(readReason(input)?.let { "Sign-in failed: $it" } ?: "Wrong Mac username or password")
                }

                out.write(1) // ClientInit: share the session, don't kick other viewers
                out.flush()
                val width = input.readUnsignedShort()
                val height = input.readUnsignedShort()
                input.skipBytes(16) // pixel format
                input.skipBytes(input.readInt()) // desktop name

                // Ask for raw pixels and a single 1×1 update; some servers ignore input until a client asks once.
                out.write(byteArrayOf(2, 0, 0, 1, 0, 0, 0, 0))
                out.write(byteArrayOf(3, 0, 0, 0, 0, 0, 0, 1, 0, 1))
                out.flush()

                socket.soTimeout = 0
                // Keep reading whatever the Mac sends so its connection never stalls; we don't need it.
                thread(name = "drift-vnc-drain", isDaemon = true) {
                    val sink = ByteArray(8 * 1024)
                    runCatching { while (input.read(sink) >= 0) Unit }
                }
                return VncLink(socket, out, width.coerceAtLeast(1), height.coerceAtLeast(1))
            } catch (e: VncException) {
                runCatching { socket.close() }
                throw e
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw VncException("Couldn't reach the Mac. Is Screen Sharing on?", unreachable = true)
            }
        }

        private fun readReason(input: DataInputStream): String? = runCatching {
            val length = input.readInt().coerceIn(0, 1024)
            String(ByteArray(length).also(input::readFully), Charsets.UTF_8)
        }.getOrNull()

        /** Classic VNC password: DES over a 16-byte challenge, with each key byte bit-reversed. */
        private fun vncSignIn(input: DataInputStream, out: OutputStream, password: String) {
            out.write(SECURITY_VNC)
            out.flush()
            val challenge = ByteArray(16).also(input::readFully)
            out.write(vncResponse(challenge, password))
            out.flush()
        }

        fun vncResponse(challenge: ByteArray, password: String): ByteArray {
            val key = ByteArray(8)
            val raw = password.toByteArray(Charsets.ISO_8859_1)
            for (i in 0 until minOf(8, raw.size)) key[i] = reverseBits(raw[i])
            val cipher = Cipher.getInstance("DES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
            return cipher.doFinal(challenge)
        }

        private fun reverseBits(b: Byte): Byte {
            var v = b.toInt() and 0xFF
            var r = 0
            repeat(8) {
                r = (r shl 1) or (v and 1)
                v = v shr 1
            }
            return r.toByte()
        }

        /**
         * Apple's sign-in (type 30): Diffie-Hellman agrees a key, then the Mac username and
         * password travel AES-encrypted with the MD5 of the shared secret.
         */
        private fun appleSignIn(input: DataInputStream, out: OutputStream, user: String, password: String) {
            out.write(SECURITY_APPLE)
            out.flush()
            val generator = input.readUnsignedShort()
            val keyLength = input.readUnsignedShort()
            val prime = BigInteger(1, ByteArray(keyLength).also(input::readFully))
            val serverKey = BigInteger(1, ByteArray(keyLength).also(input::readFully))

            val random = SecureRandom()
            val secret = BigInteger(keyLength * 8, random).mod(prime)
            val publicKey = BigInteger.valueOf(generator.toLong()).modPow(secret, prime)
            val shared = serverKey.modPow(secret, prime)
            val aesKey = MessageDigest.getInstance("MD5").digest(fixed(shared, keyLength))

            val credentials = ByteArray(128).also(random::nextBytes)
            fill(credentials, 0, user)
            fill(credentials, 64, password)
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
            out.write(cipher.doFinal(credentials))
            out.write(fixed(publicKey, keyLength))
            out.flush()
        }

        private fun fill(block: ByteArray, offset: Int, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8).take(63)
            bytes.forEachIndexed { i, b -> block[offset + i] = b }
            block[offset + bytes.size] = 0
        }

        /** Big-endian, exactly [length] bytes. */
        fun fixed(value: BigInteger, length: Int): ByteArray {
            val raw = value.toByteArray()
            return when {
                raw.size == length -> raw
                raw.size > length -> raw.copyOfRange(raw.size - length, raw.size)
                else -> ByteArray(length - raw.size) + raw
            }
        }
    }
}
