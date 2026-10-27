package com.drift.receiver

import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.desktop.AppReopenedListener
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

private object Palette {
    val background = Color(0x0B1020)
    val text = Color(0xEFF1F6)
    val muted = Color(0xA9B0C0)
    val mint = Color(0x8BF1D8)
}

/** One small window: who's connected, and an Allow button when a new watch says hello. */
class ReceiverWindow(
    private val onAllow: (String) -> Unit,
    private val onDeny: (String) -> Unit,
    private val onForget: () -> Unit,
) {
    private val frame = JFrame("Drift Receiver")
    private val status = label("Waiting for your watch…", 16f, Font.BOLD, Palette.text)
    private val detail = label(WAITING_DETAIL, 12f, Font.PLAIN, Palette.muted)
    private val allow = JButton("Allow")
    private val deny = JButton("Don't allow")
    private val askRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
    private var pendingId: String? = null
    private val isMac = System.getProperty("os.name").startsWith("Mac")

    init {
        allow.addActionListener { pendingId?.let(onAllow) }
        deny.addActionListener { pendingId?.let(onDeny) }
        askRow.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(allow)
            add(Box.createHorizontalStrut(8))
            add(deny)
            isVisible = false
        }
        val forget = JButton("Forget watches").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            addActionListener { onForget() }
        }

        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Palette.background
            border = BorderFactory.createEmptyBorder(22, 26, 22, 26)
            add(label("Drift", 30f, Font.BOLD or Font.ITALIC, Palette.mint))
            add(label("Receiver", 12f, Font.PLAIN, Palette.muted))
            add(Box.createVerticalStrut(18))
            add(status)
            add(Box.createVerticalStrut(4))
            add(detail)
            add(Box.createVerticalStrut(12))
            add(askRow)
            add(Box.createVerticalGlue())
            add(Box.createVerticalStrut(16))
            add(label("This computer: ${localAddresses()} · port $PORT", 11f, Font.PLAIN, Palette.muted))
            if (isMac) {
                add(Box.createVerticalStrut(4))
                add(label(MAC_HINT, 11f, Font.PLAIN, Palette.muted))
            }
            add(Box.createVerticalStrut(10))
            add(forget)
        }

        frame.contentPane = root
        // On a Mac, closing the window keeps Drift Receiver running (click its Dock icon to bring it
        // back; quit with Cmd+Q). Elsewhere, closing the window quits.
        if (isMac) {
            frame.defaultCloseOperation = WindowConstants.HIDE_ON_CLOSE
            runCatching {
                Desktop.getDesktop().addAppEventListener(AppReopenedListener { show() })
            }
        } else {
            frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        }
        frame.minimumSize = Dimension(420, 330)
        frame.pack()
        frame.setLocationRelativeTo(null)
    }

    fun show() {
        frame.isVisible = true
        frame.toFront()
    }

    fun waiting() = onUi {
        pendingId = null
        askRow.isVisible = false
        status.text = "Waiting for your watch…"
        detail.text = WAITING_DETAIL
    }

    fun connected(name: String, address: InetAddress) = onUi {
        pendingId = null
        askRow.isVisible = false
        status.text = "Connected to $name"
        detail.text = "${address.hostAddress} · your wrist is the mouse"
    }

    fun askToAllow(id: String, name: String, address: InetAddress) = onUi {
        pendingId = id
        askRow.isVisible = true
        status.text = "$name wants to connect"
        detail.text = "From ${address.hostAddress}. Only allow watches you own."
        frame.toFront()
    }

    private fun onUi(block: () -> Unit) = SwingUtilities.invokeLater(block)

    private companion object {
        const val WAITING_DETAIL = "Open Drift on your watch and tap Pair, then Start."
        const val MAC_HINT = "<html><body style='width:330px'>First time on a Mac: turn on Drift Receiver in " +
            "System Settings → Privacy &amp; Security → Accessibility.</body></html>"

        fun label(text: String, size: Float, style: Int, color: Color) = JLabel(text).apply {
            font = Font(Font.SANS_SERIF, style, 1).deriveFont(size)
            foreground = color
            alignmentX = Component.LEFT_ALIGNMENT
        }

        fun localAddresses(): String = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .joinToString(", ") { it.hostAddress }
        }.getOrNull()?.ifEmpty { null } ?: "no network"
    }
}
