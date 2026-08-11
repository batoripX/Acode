package com.foxdebug.acode.rk.exec.terminal

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal class ProcessServer(port: Int, private val cmd: Array<String>) :
    WebSocketServer(InetSocketAddress("127.0.0.1", port)) {

    private val readyLatch = CountDownLatch(1)
    private val startError = AtomicReference<Exception?>()

    private class ConnState(
        val process: Process,
        val stdin: OutputStream
    )

    @Throws(Exception::class)
    fun startAndAwait() {
        start()
        readyLatch.await()
        startError.get()?.let { throw it }
    }

    override fun onStart() {
        readyLatch.countDown()
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        if (conn == null) {
            // Bind/startup failure — unblock startAndAwait() so it can throw.
            startError.set(ex)
            readyLatch.countDown()
        }
        // Per-connection errors: do nothing. onClose fires immediately after
        // for the same connection, which is the single place cleanup happens.
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        try {
            val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val stdout: InputStream = process.inputStream
            val stdin: OutputStream = process.outputStream

            conn.setAttachment(ConnState(process, stdin))

            thread(start = true) {
                try {
                    val buf = ByteArray(8192)
                    var len: Int
                    while (stdout.read(buf).also { len = it } != -1) {
                        conn.send(ByteBuffer.wrap(buf, 0, len))
                    }
                } catch (ignored: Exception) {
                }
                conn.close(1000, "process exited")
            }
        } catch (e: Exception) {
            conn.close(1011, "Failed to start process: ${e.message}")
        }
    }

    override fun onMessage(conn: WebSocket, msg: ByteBuffer) {
        try {
            val state = conn.getAttachment<ConnState>()
            state?.stdin?.apply {
                write(msg.array(), msg.position(), msg.remaining())
                flush()
            }
        } catch (ignored: Exception) {
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val state = conn.getAttachment<ConnState>()
            state?.stdin?.apply {
                write(message.toByteArray(StandardCharsets.UTF_8))
                flush()
            }
        } catch (ignored: Exception) {
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        try {
            val state = conn.getAttachment<ConnState>()
            state?.process?.destroy()
        } catch (ignored: Exception) {
        }

        // stop() calls w.join() on every worker thread. If called directly from
        // onClose (which runs on a WebSocketWorker thread), it deadlocks waiting
        // for itself to finish. A separate thread sidesteps that entirely.
        thread(start = true) {
            try {
                stop()
            } catch (ignored: Exception) {
            }
        }
    }
}
