package com.drift.airmouse.core

/** The round button pads. */
enum class Pad(val title: String) {
    REMOTE("TV remote"),
    MEDIA("Media"),
    GAME("Game pad"),
}

/** The five places on a round pad: four around the edge and one in the middle. */
enum class Slot(val label: String) {
    UP("Up"),
    RIGHT("Right"),
    DOWN("Down"),
    LEFT("Left"),
    CENTER("Center"),
}

/** What a button does on a quick tap and on a hold. A [long] of NONE means holding repeats (or just taps). */
data class Binding(val short: RemoteKey, val long: RemoteKey)

/** Every pad button's current binding, with defaults for anything not remapped. */
class Bindings(private val custom: Map<String, Binding> = emptyMap()) {
    fun of(pad: Pad, slot: Slot): Binding = custom[Remap.key(pad, slot)] ?: Remap.default(pad, slot)

    fun isDefault(pad: Pad, slot: Slot): Boolean = of(pad, slot) == Remap.default(pad, slot)

    fun with(pad: Pad, slot: Slot, binding: Binding): Bindings =
        Bindings(custom + (Remap.key(pad, slot) to binding))

    fun encoded(): Map<String, String> = custom.mapValues { Remap.encode(it.value) }
}

object Remap {
    fun default(pad: Pad, slot: Slot): Binding = when (pad) {
        // Arrows repeat while held, like a real remote, so long lists scroll quickly.
        Pad.REMOTE -> when (slot) {
            Slot.UP -> Binding(RemoteKey.UP, RemoteKey.NONE)
            Slot.RIGHT -> Binding(RemoteKey.RIGHT, RemoteKey.NONE)
            Slot.DOWN -> Binding(RemoteKey.DOWN, RemoteKey.NONE)
            Slot.LEFT -> Binding(RemoteKey.LEFT, RemoteKey.NONE)
            Slot.CENTER -> Binding(RemoteKey.OK, RemoteKey.MENU)
        }
        Pad.MEDIA -> when (slot) {
            Slot.UP -> Binding(RemoteKey.VOLUME_UP, RemoteKey.NONE)
            Slot.RIGHT -> Binding(RemoteKey.NEXT, RemoteKey.FAST_FORWARD)
            Slot.DOWN -> Binding(RemoteKey.VOLUME_DOWN, RemoteKey.NONE)
            Slot.LEFT -> Binding(RemoteKey.PREVIOUS, RemoteKey.REWIND)
            Slot.CENTER -> Binding(RemoteKey.PLAY_PAUSE, RemoteKey.MUTE)
        }
        Pad.GAME -> when (slot) {
            Slot.UP -> Binding(RemoteKey.KEY_W, RemoteKey.UP)
            Slot.LEFT -> Binding(RemoteKey.KEY_A, RemoteKey.LEFT)
            Slot.DOWN -> Binding(RemoteKey.KEY_S, RemoteKey.DOWN)
            Slot.RIGHT -> Binding(RemoteKey.KEY_D, RemoteKey.RIGHT)
            Slot.CENTER -> Binding(RemoteKey.SPACE, RemoteKey.OK)
        }
    }

    /** Keys that keep firing while you hold them, when the button has no long-press action. */
    fun repeats(key: RemoteKey): Boolean = key in REPEATING

    fun key(pad: Pad, slot: Slot) = "${pad.name}.${slot.name}"

    fun encode(binding: Binding) = "${binding.short.name}|${binding.long.name}"

    fun decode(text: String): Binding? {
        val parts = text.split('|')
        val short = RemoteKey.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: return null
        val long = RemoteKey.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: RemoteKey.NONE
        return Binding(short, long)
    }

    private val REPEATING = setOf(
        RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT,
        RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN,
        RemoteKey.KEY_W, RemoteKey.KEY_A, RemoteKey.KEY_S, RemoteKey.KEY_D,
        RemoteKey.SPACE,
    )
}
