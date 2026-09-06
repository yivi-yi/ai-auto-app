package com.example.aiauto

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LocalServer(private val port: Int) {

    private var server: ServerSocket? = null
    private var running = false
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        running = true
        thread {
            try {
                server = ServerSocket(port)
                while (running) {
                    try {
                        val socket = server!!.accept()
                        thread { handle(socket) }
                    } catch (e: IOException) {
                        if (!running) break
                    }
                }
            } catch (e: IOException) {
                // 端口被占用或启动失败
            }
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (e: IOException) {
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            val input = it.getInputStream()
            val sb = StringBuilder()
            var ended = false
            while (!ended && sb.length < 65536) {
                val b = input.read()
                if (b == -1) break
                sb.append(b.toChar())
                if (sb.endsWith("\r\n\r\n")) ended = true
            }
            val lines = sb.toString().split("\r\n")
            if (lines.isEmpty()) return
            val parts = lines[0].split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]
            val headers = lines.drop(1).filter { it.contains(":") }
            val len = headers.firstOrNull { it.startsWith("Content-Length", true) }
                ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
            val body = if (len > 0) {
                val arr = ByteArray(len)
                var r = 0
                while (r < len) {
                    val c = input.read()
                    if (c == -1) break
                    arr[r++] = c.toByte()
                }
                arr
            } else {
                ByteArray(0)
            }

            try {
                if (path == "/status") {
                    send(it, 200, "application/json", statusJson())
                } else if (path == "/action" && method == "POST") {
                    val j = JSONObject(String(body))
                    runAction(j)
                    send(it, 200, "application/json", Json.ok("ok"))
                } else if (path == "/capture" && method == "POST") {
                    val f = captureBlocking()
                    if (f != null) send(it, 200, "application/json", Json.ok("shot"))
                    else send(it, 500, "application/json", Json.err("no capture"))
                } else if (path == "/screenshot") {
                    val f = CaptureService.lastShot
                    if (f != null && f.exists()) {
                        send(it, 200, "image/png", f.readBytes())
                    } else {
                        send(it, 500, "application/json", Json.err("no screenshot"))
                    }
                } else {
                    send(it, 404, "application/json", Json.err("not found"))
                }
            } catch (e: Exception) {
                send(it, 500, "application/json", Json.err(e.message ?: "error"))
            }
        }
    }

    private fun runAction(j: JSONObject) {
        val type = j.optString("type")
        main.post {
            val svc = AutoAccessibilityService.instance
            when (type) {
                "tap" -> svc?.tap(j.getDouble("x").toFloat(), j.getDouble("y").toFloat())
                "swipe" -> svc?.swipe(
                    j.getDouble("x1").toFloat(), j.getDouble("y1").toFloat(),
                    j.getDouble("x2").toFloat(), j.getDouble("y2").toFloat(),
                    j.optLong("duration", 300)
                )
                "back" -> svc?.back()
                "home" -> svc?.home()
            }
        }
    }

    private fun captureBlocking(): Any? {
        val latch = CountDownLatch(1)
        var result: Any? = null
        main.post {
            result = CaptureService.instance?.saveScreenshot()
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    private fun statusJson(): String {
        val o = JSONObject()
        o.put("accessibility", if (AutoAccessibilityService.enabled) "on" else "off")
        o.put("capture", if (CaptureService.active) "on" else "off")
        o.put("port", port)
        return o.toString()
    }

    private fun send(socket: Socket, status: Int, type: String, body: String) {
        send(socket, status, type, body.toByteArray(Charsets.UTF_8))
    }

    private fun send(socket: Socket, status: Int, type: String, bytes: ByteArray) {
        try {
            val out = socket.getOutputStream()
            val head = "HTTP/1.1 $status OK\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (e: IOException) {
        }
    }

    private object Json {
        fun ok(msg: String) = JSONObject().put("ok", true).put("msg", msg).toString()
        fun err(msg: String) = JSONObject().put("ok", false).put("msg", msg).toString()
    }
}
