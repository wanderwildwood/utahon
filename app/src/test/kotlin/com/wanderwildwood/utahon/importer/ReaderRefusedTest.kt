package com.wanderwildwood.utahon.importer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/** A site that refuses is asked once, in the app's own name, and not again under another. */
class ReaderRefusedTest {

    @Test
    fun aRefusingSiteIsAskedOnceInTheAppsOwnName() {
        val agents = Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        val answering = thread(isDaemon = true) {
            try {
                while (true) {
                    server.accept().use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("User-Agent:", ignoreCase = true)) agents.add(line.substringAfter(':').trim())
                        }
                        s.getOutputStream().apply {
                            write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            flush()
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // closed at the end of the test
            }
        }
        try {
            val r = Reader.readFrom("http://127.0.0.1:${server.localPort}/song", upgradeToHttps = false)
            assertEquals(Reader.Result.Failed(403), r)
            assertEquals(listOf(Reader.USER_AGENT), agents.toList())
        } finally {
            server.close()
            answering.join(2000)
        }
    }
}
