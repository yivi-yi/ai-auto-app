package com.example.aiauto

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
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
                } else if (path == "/ui") {
                    val arr = uiBlocking()
                    send(it, 200, "application/json", arr?.toString() ?: "[]")
                } else if (path == "/clickNode" && method == "POST") {
                    val j = JSONObject(String(body))
                    val ok = clickNodeBlocking(j.optString("text"))
                    send(it, 200, "application/json", if (ok) Json.ok("clicked") else Json.err("node not found"))
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
                        send(it, 200, "image/jpeg", f.readBytes())
                    } else {
                        send(it, 500, "application/json", Json.err("no screenshot"))
                    }
                } else if (path == "/mcp") {
                    if (method == "GET") {
                        send(it, 200, "text/event-stream", "event: endpoint\ndata: /mcp\n\n")
                    } else {
                        handleMcp(it, body)
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
                "inputText" -> svc?.inputText(j.optString("text"))
                "openApp" -> svc?.openApp(j.optString("package"))
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

    private fun uiBlocking(): org.json.JSONArray? {
        val latch = CountDownLatch(1)
        var res: org.json.JSONArray? = null
        main.post {
            res = AutoAccessibilityService.instance?.uiTree()
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return res
    }

    private fun clickNodeBlocking(text: String): Boolean {
        val latch = CountDownLatch(1)
        var res = false
        main.post {
            res = AutoAccessibilityService.instance?.clickByText(text) ?: false
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return res
    }

    // ===== MCP (HTTP) 工具，对齐之前那套，去掉电量/音量 =====
    private fun handleMcp(socket: Socket, body: ByteArray) {
        val msg = JSONObject(String(body))
        val method = msg.optString("method")
        if (method.startsWith("notifications/")) {
            send(socket, 202, "application/json", "{}")
            return
        }
        val resp = JSONObject().put("jsonrpc", "2.0")
        if (msg.has("id")) resp.put("id", msg.opt("id"))
        when (method) {
            "initialize" -> resp.put("result", mcpInit())
            "tools/list" -> resp.put("result", JSONObject().put("tools", mcpTools()))
            "tools/call" -> {
                val params = msg.optJSONObject("params") ?: JSONObject()
                resp.put("result", mcpCall(params.optString("name"), params.optJSONObject("arguments") ?: JSONObject()))
            }
            else -> resp.put("error", JSONObject().put("code", -32601).put("message", "method not found"))
        }
        send(socket, 200, "application/json", resp.toString())
    }

    private fun mcpInit(): JSONObject = JSONObject().apply {
        put("protocolVersion", "2025-03-26")
        put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
        put("serverInfo", JSONObject().put("name", "ai-auto").put("version", "1.0"))
        put("instructions", "控制平板：click/long_press/swipe/type_text/go_back/launch_app/ui_scan/screenshot_vision/wechat_*/play_song/open_url。click 的 target 可给坐标 (100,200) 或界面文字。")
    }

    private fun mcpTools(): JSONArray = JSONArray().apply {
        put(tool("click", "点击目标，target 为坐标如 (100,200) 或界面文字。", JSONObject().put("target", strProp()).put("feedback", strProp())))
        put(tool("long_press_target", "长按目标，duration 毫秒。", JSONObject().put("target", strProp()).put("duration", numProp())))
        put(tool("swipe_screen", "滑动屏幕，start 为方向 up/down/left/right 或坐标，end 为终点坐标。", JSONObject().put("start", strProp()).put("end", strProp()).put("duration", numProp())))
        put(tool("type_text", "向当前输入框输入文字并回车。", JSONObject().put("text", strProp())))
        put(tool("go_back", "返回键。", JSONObject()))
        put(tool("launch_app", "打开应用，app_name 为名称或包名，page 可选 search。", JSONObject().put("app_name", strProp()).put("page", strProp())))
        put(tool("ui_scan", "扫描当前界面 UI 树，返回文字+坐标+是否可点击。", JSONObject()))
        put(tool("screenshot_vision", "截图并返回压缩后的图片。", JSONObject()))
        put(tool("wechat_type", "微信：点输入框→输入→发送→收起键盘。", JSONObject().put("text", strProp())))
        put(tool("wechat_search_contact", "微信：搜索联系人或群聊并打开。", JSONObject().put("contact", strProp())))
        put(tool("wechat_moments", "微信：进入朋友圈。", JSONObject()))
        put(tool("play_song", "搜索并播放网易云歌曲。", JSONObject().put("keyword", strProp())))
        put(tool("open_url", "用浏览器打开网址。", JSONObject().put("url", strProp())))
    }

    private fun tool(name: String, desc: String, props: JSONObject): JSONObject {
        val schema = JSONObject().put("type", "object").put("properties", props)
        val required = JSONArray()
        for (k in props.keys()) { if (k != "feedback" && k != "duration" && k != "end" && k != "page") required.put(k) }
        if (required.length() > 0) schema.put("required", required)
        return JSONObject().put("name", name).put("description", desc).put("inputSchema", schema)
    }

    private fun numProp() = JSONObject().put("type", "number")
    private fun strProp() = JSONObject().put("type", "string")

    private fun mcpCall(name: String, args: JSONObject): JSONObject {
        val content = JSONArray()
        when (name) {
            "click" -> {
                val t = args.optString("target")
                val c = resolveCoord(t)
                if (c == null) content.put(text("找不到: $t"))
                else { runOnMain { AutoAccessibilityService.instance?.tap(c.first.toFloat(), c.second.toFloat()) }; content.put(text("已点击")) }
            }
            "long_press_target" -> {
                val t = args.optString("target"); val c = resolveCoord(t); val d = args.optLong("duration", 1000)
                if (c == null) content.put(text("找不到: $t"))
                else { runOnMain { AutoAccessibilityService.instance?.swipe(c.first.toFloat(), c.second.toFloat(), c.first.toFloat(), c.second.toFloat(), d) }; content.put(text("已长按")) }
            }
            "swipe_screen" -> {
                val s = args.optString("start"); val e = args.optString("end"); val d = args.optLong("duration", 300)
                if (s in setOf("up", "down", "left", "right")) { runOnMain { directionSwipe(s) }; content.put(text("已滑动")) }
                else {
                    val c1 = parseCoordOrNull(s); val c2 = parseCoordOrNull(e)
                    if (c1 == null || c2 == null) content.put(text("坐标错误"))
                    else { runOnMain { AutoAccessibilityService.instance?.swipe(c1.first.toFloat(), c1.second.toFloat(), c2.first.toFloat(), c2.second.toFloat(), d) }; content.put(text("已滑动")) }
                }
            }
            "type_text" -> { runOnMain { AutoAccessibilityService.instance?.inputText(args.optString("text")) }; content.put(text("已执行")) }
            "go_back" -> { runOnMain { AutoAccessibilityService.instance?.back() }; content.put(text("已返回")) }
            "launch_app" -> {
                val app = args.optString("app_name"); val page = args.optString("page")
                val pkg = packageOf(app)
                if (page == "search") { val uri = searchUriOf(pkg); runOnMain { AutoAccessibilityService.instance?.openUri(uri) } }
                else { runOnMain { AutoAccessibilityService.instance?.openApp(pkg) } }
                content.put(text("已打开 $app"))
            }
            "ui_scan" -> content.put(text(uiBlocking()?.toString() ?: "[]"))
            "screenshot_vision" -> {
                val f = captureBlocking() as? java.io.File
                if (f != null) { val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP); content.put(JSONObject().put("type", "image").put("data", b64).put("mimeType", "image/jpeg")) }
                else content.put(text("截图失败"))
            }
            "wechat_type" -> { wechatType(args.optString("text")); content.put(text("已执行")) }
            "wechat_search_contact" -> { wechatSearch(args.optString("contact")); content.put(text("已执行")) }
            "wechat_moments" -> { wechatMoments(); content.put(text("已执行")) }
            "play_song" -> content.put(text(playSong(args.optString("keyword"))))
            "open_url" -> { val u = args.optString("url").let { if (it.startsWith("http")) it else "https://$it" }; runOnMain { AutoAccessibilityService.instance?.openUri(u) }; content.put(text("已打开")) }
            else -> content.put(text("未知工具"))
        }
        return JSONObject().put("content", content).put("isError", false)
    }

    private fun text(s: String) = JSONObject().put("type", "text").put("text", s)
    private fun runOnMain(block: () -> Unit) { main.post(block) }

    private fun parseCoordOrNull(s: String): Pair<Int, Int>? {
        val t = s.trim().replace("(", "").replace(")", "")
        val p = t.split(",")
        return try { Pair(p[0].trim().toInt(), p[1].trim().toInt()) } catch (e: Exception) { null }
    }

    private fun resolveCoord(target: String): Pair<Int, Int>? {
        parseCoordOrNull(target)?.let { return it }
        val nodes = uiBlocking() ?: return null
        for (i in 0 until nodes.length()) {
            val o = nodes.getJSONObject(i)
            val t = o.optString("text")
            if (target.isNotEmpty() && t.contains(target)) return Pair(o.optInt("x"), o.optInt("y"))
        }
        return null
    }

    private fun directionSwipe(dir: String) {
        val svc = AutoAccessibilityService.instance ?: return
        val cx = 920; val cy = 1400; val off = 700
        when (dir) {
            "up" -> svc.swipe(cx.toFloat(), (cy + off).toFloat(), cx.toFloat(), (cy - off).toFloat(), 300)
            "down" -> svc.swipe(cx.toFloat(), (cy - off).toFloat(), cx.toFloat(), (cy + off).toFloat(), 300)
            "left" -> svc.swipe((cx + off).toFloat(), cy.toFloat(), (cx - off).toFloat(), cy.toFloat(), 300)
            "right" -> svc.swipe((cx - off).toFloat(), cy.toFloat(), (cx + off).toFloat(), cy.toFloat(), 300)
        }
    }

    private fun packageOf(app: String): String {
        val known = mapOf("微信" to "com.tencent.mm", "抖音" to "com.ss.android.ugc.aweme", "网易云" to "com.netease.cloudmusic", "应用商店" to "com.bbk.appstore")
        if (known.containsKey(app)) return known[app]!!
        return app
    }

    private fun searchUriOf(pkg: String): String {
        return mapOf(
            "com.bbk.appstore" to "market://search?q=app",
            "com.ss.android.ugc.aweme" to "snssdk1128://search",
            "com.netease.cloudmusic" to "orpheus://search"
        )[pkg] ?: "market://search?q=app"
    }

    private fun playSong(keyword: String): String {
        return try {
            val url = java.net.URL("http://localhost:3000/search?keywords=${java.net.URLEncoder.encode(keyword, "UTF-8")}&type=1&limit=1")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val bodyStr = conn.inputStream.bufferedReader().readText()
            val data = JSONObject(bodyStr)
            val songs = data.optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
            if (songs.length() == 0) return "未找到歌曲"
            val song = songs.getJSONObject(0)
            val id = song.optLong("id")
            val title = song.optString("name", "未知")
            runOnMain { AutoAccessibilityService.instance?.openUri("orpheus://song/$id") }
            "正在播放: $title (ID: $id)"
        } catch (e: Exception) {
            "点歌失败: ${e.message}"
        }
    }

    private fun wechatType(text: String) {
        main.post {
            AutoAccessibilityService.instance?.tap(900f, 2750f)
            main.postDelayed({ AutoAccessibilityService.instance?.inputText(text) }, 600)
            main.postDelayed({ AutoAccessibilityService.instance?.back() }, 1500)
        }
    }

    private fun wechatSearch(contact: String) {
        main.post {
            AutoAccessibilityService.instance?.tap(1640f, 150f)
            main.postDelayed({ AutoAccessibilityService.instance?.inputText(contact) }, 600)
            main.postDelayed({ AutoAccessibilityService.instance?.tap(1000f, 500f) }, 1400)
        }
    }

    private fun wechatMoments() {
        main.post {
            AutoAccessibilityService.instance?.tap(1150f, 2700f)
            main.postDelayed({ AutoAccessibilityService.instance?.tap(1000f, 280f) }, 600)
        }
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
