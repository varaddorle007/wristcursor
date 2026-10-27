package com.drift.receiver

import java.util.prefs.Preferences

/** Remembers which watches you've allowed, by the random id each watch makes for itself. */
class TrustStore {
    private val node = Preferences.userRoot().node("com/drift/receiver/watches")

    fun isTrusted(id: String): Boolean = node.get(id, null) != null

    fun add(id: String, name: String) {
        node.put(id, name)
        runCatching { node.flush() }
    }

    fun clear() {
        runCatching {
            node.clear()
            node.flush()
        }
    }
}
