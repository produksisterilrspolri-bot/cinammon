package com.bos.dualnet

import android.net.Network
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/**
 * Proxy HTTP/HTTPS lokal di 127.0.0.1. WebView diarahkan ke sini;
 * tiap koneksi keluar dipasang ke Network pilihan [route] (LAN atau data).
 * shortcut: koneksi keep-alive HTTP biasa dipakai ulang ke host pertama; HTTPS (CONNECT) tidak terpengaruh.
 */
class Proxy(private val route: (String) -> Network?) {

    fun start(): Int {
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (true) {
                val c = ss.accept()
                thread(isDaemon = true) { handle(c) }
            }
        }
        return ss.localPort
    }

    private fun readHeader(i: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var last = 0
        while (true) {
            val x = i.read()
            if (x < 0) break
            out.write(x)
            last = (last shl 8) or x
            if (last == 0x0D0A0D0A) break
        }
        return out.toByteArray()
    }

    private fun pump(i: InputStream, o: OutputStream) {
        try {
            i.copyTo(o)
            o.flush()
        } catch (e: IOException) {
        }
    }

    private fun handle(c: Socket) {
        var up: Socket? = null
        try {
            val cin = c.getInputStream().buffered()
            val cout = c.getOutputStream()
            val head = readHeader(cin)
            val first = String(head, Charsets.ISO_8859_1).substringBefore("\r\n").split(" ")
            if (first.size < 2) return
            val connect = first[0] == "CONNECT"
            val host: String
            val port: Int
            if (connect) {
                host = first[1].substringBeforeLast(":")
                port = first[1].substringAfterLast(":").toInt()
            } else {
                val u = URL(first[1])
                host = u.host
                port = if (u.port > 0) u.port else 80
            }
            val net = route(host)
            up = try {
                if (net != null) net.socketFactory.createSocket(host, port) else Socket(host, port)
            } catch (e: IOException) {
                cout.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                return
            }
            if (connect) cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            else up.getOutputStream().write(head)
            val upSock = up
            thread(isDaemon = true) {
                pump(upSock.getInputStream(), cout)
                runCatching { c.close() }
                runCatching { upSock.close() }
            }
            pump(cin, up.getOutputStream())
        } catch (e: Exception) {
        } finally {
            runCatching { c.close() }
            runCatching { up?.close() }
        }
    }
}
