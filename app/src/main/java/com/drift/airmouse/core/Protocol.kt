package com.drift.airmouse.core

/**
 * Wi-Fi wire format shared with Drift Receiver on the computer.
 * Plain UTF-8 text over UDP; the receiver listens on [PORT].
 */
object Protocol {
    const val PORT = 47800

    const val DISCOVER = "DRIFT?"
    const val DISCOVER_REPLY = "DRIFT!"
    const val HELLO = "HELLO"
    const val OK = "OK"
    const val WAIT = "WAIT"
    const val NO = "NO"
    const val BYE = "BYE"

    const val CLICK = "click"
    const val RIGHT = "right"
    const val DOWN = "down"
    const val UP = "up"
    const val CENTER = "center"
    const val NEXT = "key next"
    const val PREV = "key prev"

    fun move(dx: Int, dy: Int) = "m $dx $dy"

    /** Wheel steps; positive scrolls down. */
    fun scroll(steps: Int) = "s $steps"

    fun type(text: String) = "type " + java.util.Base64.getEncoder().encodeToString(text.take(MAX_TYPED).toByteArray(Charsets.UTF_8))

    /** Keeps a typed message well inside one UDP packet. */
    const val MAX_TYPED = 4_000

    /**
     * The receiver's name for a key, or null if it can't press it.
     * Left and right keep their original names so older receivers still flip slides.
     */
    fun keyCommand(key: RemoteKey): String? = when (key) {
        RemoteKey.RIGHT -> NEXT
        RemoteKey.LEFT -> PREV
        RemoteKey.UP -> "key up"
        RemoteKey.DOWN -> "key down"
        RemoteKey.OK -> "key enter"
        RemoteKey.BACK -> "key esc"
        RemoteKey.BACKSPACE -> "key backspace"
        RemoteKey.PLAY_PAUSE -> "media play"
        RemoteKey.NEXT -> "media next"
        RemoteKey.PREVIOUS -> "media prev"
        RemoteKey.FAST_FORWARD -> "media ff"
        RemoteKey.REWIND -> "media rw"
        RemoteKey.VOLUME_UP -> "media volup"
        RemoteKey.VOLUME_DOWN -> "media voldown"
        RemoteKey.MUTE -> "media mute"
        RemoteKey.SPACE -> "key space"
        RemoteKey.SHIFT -> "key shift"
        RemoteKey.KEY_W -> "key w"
        RemoteKey.KEY_A -> "key a"
        RemoteKey.KEY_S -> "key s"
        RemoteKey.KEY_D -> "key d"
        RemoteKey.KEY_E -> "key e"
        RemoteKey.ESCAPE -> "key esc"
        else -> null
    }
}
