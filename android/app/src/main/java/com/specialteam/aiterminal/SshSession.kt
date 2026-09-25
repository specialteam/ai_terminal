package com.specialteam.aiterminal

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream

class SshSession(
    private val log: SessionLog,
    private val onOutput: (String) -> Unit,
    private val onDisconnected: (String) -> Unit,
) {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var out: OutputStream? = null

    /** Blocking; call from a background thread. */
    fun connect(host: String, port: Int, user: String, password: String) {
        val s = JSch().getSession(user, host, port)
        s.setPassword(password)
        s.setConfig("StrictHostKeyChecking", "no")
        s.connect(10_000)
        val ch = s.openChannel("shell") as ChannelShell
        ch.setPtyType("xterm")
        val input = ch.inputStream
        out = ch.outputStream
        ch.connect(10_000)
        session = s
        channel = ch
        Thread({ readLoop(input) }, "ssh-reader").apply { isDaemon = true }.start()
    }

    private fun readLoop(input: InputStream) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val text = String(buf, 0, n, Charsets.UTF_8)
                log.log("SERVER_OUTPUT", text)
                onOutput(text)
            }
            onDisconnected("connection closed")
        } catch (e: Exception) {
            onDisconnected(e.message ?: e.toString())
        }
    }

    /** Blocking write; call from a background thread. */
    fun send(cmd: String) {
        val line = if (cmd.endsWith("\n")) cmd else "$cmd\n"
        log.log("USER_COMMAND", line.trim())
        out?.apply { write(line.toByteArray()); flush() }
    }

    val isConnected: Boolean get() = channel?.isConnected == true

    fun close() {
        channel?.disconnect()
        session?.disconnect()
    }
}
