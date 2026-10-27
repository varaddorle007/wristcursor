package com.drift.airmouse.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

enum class MouseButton { LEFT, RIGHT }

/** Every button Drift can press on a computer or TV. [label] is what Settings shows. */
enum class RemoteKey(val label: String) {
    UP("D-Pad Up"),
    DOWN("D-Pad Down"),
    LEFT("D-Pad Left"),
    RIGHT("D-Pad Right"),
    OK("OK"),
    BACK("Back"),
    HOME("Home"),
    MENU("Menu"),
    SEARCH("Search"),
    PLAY_PAUSE("Play / pause"),
    NEXT("Next track"),
    PREVIOUS("Previous track"),
    FAST_FORWARD("Fast-forward"),
    REWIND("Rewind"),
    VOLUME_UP("Volume up"),
    VOLUME_DOWN("Volume down"),
    MUTE("Mute"),
    BACKSPACE("Delete"),
    SPACE("Space (Nitro / Jump)"),
    SHIFT("Shift (Drift / Sprint)"),
    KEY_W("W (Forward)"),
    KEY_A("A (Left)"),
    KEY_S("S (Brake / Back)"),
    KEY_D("D (Right)"),
    KEY_E("E (Action)"),
    ESCAPE("Esc (Pause / Menu)"),
    NONE("Nothing");

    val shortLabel: String
        get() = when (this) {
            OK -> "OK"
            KEY_W -> "W"
            KEY_A -> "A"
            KEY_S -> "S"
            KEY_D -> "D"
            KEY_E -> "E"
            SPACE -> "SPACE"
            SHIFT -> "SHIFT"
            ESCAPE -> "ESC"
            else -> label
        }
}

/** A way to reach the computer. Every call arrives on the service's motion thread. */
interface PointerLink {
    fun move(dx: Int, dy: Int)
    fun click(button: MouseButton)

    /** Hold a button down (dragging) until [release]. */
    fun press(button: MouseButton)
    fun release(button: MouseButton)

    /** Positive scrolls down the page. */
    fun scroll(steps: Int)

    /** Press and let go of a key. Returns false if this link can't send that key. */
    fun send(key: RemoteKey): Boolean

    /** Types [text] where the computer's cursor is. Returns false if this link can't type. */
    fun type(text: String): Boolean

    /** Puts the cursor in the middle of the screen. Returns false if this link can't know where that is. */
    fun center(): Boolean

    /**
     * Steer a vehicle in car / racing games.
     * [steer]: -1.0f (full left) to +1.0f (full right).
     * [throttle]: true while accelerating (gas pedal down).
     * [brake]: true while braking / reversing.
     * [nitro]: true while boosting / handbraking.
     */
    fun wheel(steer: Float, throttle: Boolean, brake: Boolean, nitro: Boolean) {}

    fun close()
}

/** Wi-Fi: plain text packets to Drift Receiver. */
class UdpLink(private val socket: DatagramSocket, private val target: InetAddress) : PointerLink {
    override fun move(dx: Int, dy: Int) = send(Protocol.move(dx, dy))

    override fun click(button: MouseButton) =
        send(if (button == MouseButton.LEFT) Protocol.CLICK else Protocol.RIGHT)

    override fun press(button: MouseButton) = send(Protocol.DOWN)

    override fun release(button: MouseButton) = send(Protocol.UP)

    override fun scroll(steps: Int) = send(Protocol.scroll(steps))

    override fun wheel(steer: Float, throttle: Boolean, brake: Boolean, nitro: Boolean) {
        val gas = if (throttle) 1 else 0
        val brk = if (brake) 1 else 0
        val ntr = if (nitro) 1 else 0
        val clampedSteer = ((steer * 1000f).toInt()) / 1000f
        send("wheel $clampedSteer $gas $brk $ntr")
    }

    override fun send(key: RemoteKey): Boolean {
        val command = Protocol.keyCommand(key) ?: return false
        send(command)
        return true
    }

    override fun center(): Boolean {
        send(Protocol.CENTER)
        return true
    }

    // Sent as Base64 so any language or emoji survives; the receiver pastes it.
    override fun type(text: String): Boolean {
        send(Protocol.type(text))
        return true
    }

    override fun close() {
        send(Protocol.BYE)
        socket.close()
    }

    private fun send(message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, target, Protocol.PORT)) }
    }
}
