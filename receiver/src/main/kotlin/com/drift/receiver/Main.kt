package com.drift.receiver

import java.awt.AWTException
import java.net.BindException
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.concurrent.thread
import kotlin.system.exitProcess

const val PORT = 47800

fun main() {
    System.setProperty("apple.awt.application.name", "Drift Receiver")
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    val pointer = try {
        Pointer()
    } catch (e: AWTException) {
        JOptionPane.showMessageDialog(null, "Drift Receiver can't control the mouse here.\n${e.message}")
        exitProcess(1)
    }

    val trust = TrustStore()
    lateinit var server: Server
    val window = ReceiverWindow(
        onAllow = { server.allow(it) },
        onDeny = { server.deny(it) },
        onForget = {
            trust.clear()
            server.disconnectAll()
        },
    )
    server = try {
        Server(pointer, trust, window)
    } catch (_: BindException) {
        JOptionPane.showMessageDialog(null, "Port $PORT is busy. Is Drift Receiver already open?")
        exitProcess(1)
    }

    SwingUtilities.invokeLater { window.show() }
    thread(name = "drift-server", isDaemon = true, priority = Thread.MAX_PRIORITY) { server.run() }
}
