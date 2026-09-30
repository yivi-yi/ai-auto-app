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

class LocalServer(
    private val port: Int,
    private val readToken: () -> String = { "" },
    private val writeToken: (String) -> Unit = {}
) {

    private var server: ServerSocket? = null
    private var running = false
    private val main = Handler(Looper.getMainLooper())

    /** 启动失败的原因（端口被占用之类），给 /status 和通知用 */
    @Volatile
    var startError: String? = null
        private set

    fun start() {
        running = true
        thread {
            try {
                server = ServerSocket(port)
                startError = null
                while (running) {
                    try {
                        val socket = server!!.accept()
                        thread { handle(socket) }
                    } catch (e: IOException) {
                        if (!running) break
                    }
                }
            } catch (e: IOException) {
                // 端口被占用或启动失败 —— 记下来，别静默
                startError = e.message ?: "端口 $port 被占用"
                running = false
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
            val rawPath = parts[1]
            val path = rawPath.substringBefore("?")
            val query = rawPath.substringAfter("?", "")
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

            // 局域网来的请求要带 token（本机回环不用）；token 没设就不校验
            val isLocal = socket.inetAddress?.isLoopbackAddress == true
            val token = readToken()
            if (!isLocal && token.isNotEmpty()) {
                val fromHeader = headers.firstOrNull { it.startsWith("X-Token", true) }
                    ?.substringAfter(":")?.trim()
                val fromQuery =
                    if (query.contains("token=")) query.substringAfter("token=").substringBefore("&") else null
                if (fromHeader != token && fromQuery != token) {
                    send(socket, 401, "application/json", Json.err("token 不对"))
                    return
                }
            }

            try {
                if (path == "/set_token" && method == "POST") {
                    if (!isLocal) {
                        send(it, 403, "application/json", Json.err("只能在本机设置"))
                    } else {
                        val t = JSONObject(String(body)).optString("token").trim()
                        writeToken(t)
                        send(it, 200, "application/json", JSONObject().put("ok", true).put("token", t).toString())
                    }
                } else if (path == "/status") {
                    send(it, 200, "application/json", statusJson())
                } else if (path == "/ui") {
                    if (noA11y(it)) return
                    val arr = uiBlocking()
                    // 默认只给能点的：一行「文字 (x,y)」——点的时候本来也只吃坐标
                    if (query.contains("full")) send(it, 200, "application/json", arr?.toString() ?: "[]")
                    else send(it, 200, "text/plain; charset=utf-8", uiText(arr))
                } else if (path == "/clickNode" && method == "POST") {
                    if (noA11y(it)) return
                    val j = JSONObject(String(body))
                    val ok = clickNodeBlocking(j.optString("text"))
                    send(it, 200, "application/json", if (ok) Json.ok("clicked") else Json.err("node not found"))
                } else if (path == "/action" && method == "POST") {
                    if (noA11y(it)) return
                    val j = JSONObject(String(body))
                    val raw = j.optString("type").trim()
                    if (normalizeType(raw) !in actionTypes) {
                        // 认不出就别回 ok —— 不然调用方试一百遍也不知道自己写错了
                        val tail = if (raw.isEmpty()) "" else "「$raw」"
                        send(it, 200, "application/json", Json.err("不认识的 type$tail，可用：${actionTypes.joinToString("/")}"))
                        return
                    }
                    runAction(j)
                    send(it, 200, "application/json", Json.ok("ok"))
                } else if (path == "/capture" && method == "POST") {
                    if (CaptureService.lastShot == null && AutoAccessibilityService.instance == null && CaptureService.instance == null) {
                        send(it, 200, "application/json", Json.err("截图没开：无障碍或录屏授权任意一个"))
                        return
                    }
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

    /** 无障碍没开时点击/滑动全是空转 —— 直接说清楚，别让 AI 以为点成了 */
    private fun noA11y(socket: Socket): Boolean {
        if (AutoAccessibilityService.instance != null) return false
        send(socket, 200, "application/json", Json.err("无障碍没开：先在平板上打开 AI Auto 的无障碍开关"))
        return true
    }

    /** /action 认的 type，也是报错时要列出来的那份 */
    private val actionTypes = setOf(
        "tap", "swipe", "back", "home", "recents", "notifications", "lock", "global",
        "inputText", "inputTextSend", "clickText", "openApp"
    )

    /** 顺手认几个常见别名，省得调用方一个个试 */
    private fun normalizeType(t: String): String = when (t) {
        "click" -> "tap"
        "input", "input_text", "set_text" -> "inputText"
        "input_send", "send" -> "inputTextSend"
        "click_text" -> "clickText"
        "open_app", "launch" -> "openApp"
        else -> t
    }

    private fun runAction(j: JSONObject) {
        val type = normalizeType(j.optString("type"))
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
                "recents" -> svc?.globalAction("recents")
                "notifications" -> svc?.globalAction("notifications")
                "lock" -> svc?.globalAction("lock")
                "global" -> svc?.globalAction(j.optString("key"))
                "inputText" -> svc?.inputText(j.optString("text"))
                "inputTextSend" -> svc?.inputTextAndSend(j.optString("text"))
                "clickText" -> svc?.clickNode(j.optString("text"))
                "openApp" -> svc?.openApp(j.optString("package"))
            }
        }
    }

    /** 在主线程跑一段并等结果（工具执行完直接返回，AI 不用猜） */
    private fun <T> onMainGet(block: () -> T?): T? {
        val latch = CountDownLatch(1)
        var res: T? = null
        main.post {
            res = block()
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return res
    }

    private fun captureBlocking(): Any? {
        // ① 先试无障碍自带截图：**不用**授权录屏（API 30+ 且 config 里 canTakeScreenshot=true）
        //    注意在 HTTP 线程调（回调回主线程，主线程里等会死锁）
        try {
            val shot = AutoAccessibilityService.instance?.screenshotSync(3000)
            if (shot != null) {
                CaptureService.lastShot = shot
                return shot
            }
        } catch (e: Exception) {
        }
        // ② 退回 MediaProjection（开了「开启截图」授权的那条路）
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
        put("instructions", "控制平板：screen_size 看屏幕多大；current_app 看现在前台是哪个应用；ui_scan 读当前界面上可交互的东西（每行「文字 (x,y)」，其余文字自己截图看）；click 可按文字或坐标点；tap_xy 纯坐标点；type_text 输入（enter=true 输入完直接回车发送）；press 用 back/home/recents/notifications；launch_app 开应用（中文名、英文名、包名都行）；swipe_screen 滑动（up/down/left/right 或坐标）；screenshot_vision 截图看画面，界面上那些点不动的文字就看截图；click/tap_xy/long_press_target/swipe_screen/type_text/press/go_back/launch_app/微信三个/play_song/open_url 都能自己顺带参数 shot=true（做完等 2 秒截图返回）或 scan=true（做完等 2 秒返回界面文字），一次调用就拿到「已点击 + 结果」，别点完再单独截一次。")
    }

    private fun mcpTools(): JSONArray = JSONArray().apply {
        put(tool("screen_size", "屏幕宽高（像素），算坐标前先看一眼。", JSONObject()))
        put(tool("current_app", "当前前台应用的包名和名字。", JSONObject()))
        put(tool("click", "点击目标，target 为坐标如 (100,200) 或界面文字（含图标描述，如「搜索」）$THEN_HINT", withThen(JSONObject().put("target", strProp()))))
        put(tool("long_press_target", "长按目标，duration 毫秒$THEN_HINT", withThen(JSONObject().put("target", strProp()).put("duration", numProp()))))
        put(tool("swipe_screen", "滑动屏幕，start 为方向 up/down/left/right 或坐标，end 为终点坐标$THEN_HINT", withThen(JSONObject().put("start", strProp()).put("end", strProp()).put("duration", numProp()))))
        put(tool("type_text", "往当前输入框打字；enter=true 输入完直接回车发送（键盘回车键），不带 enter 就只输入不发送$THEN_HINT", withThen(JSONObject().put("text", strProp()).put("enter", boolProp()))))
        put(tool("press", "系统按键：back / home / recents / notifications / quick_settings / lock / power$THEN_HINT", withThen(JSONObject().put("key", strProp()))))
        put(tool("go_back", "返回键（同 press key=back）$THEN_HINT", withThen(JSONObject())))
        put(tool("launch_app", "打开应用。app_name 给中文名（如「微信」）、英文名或包名都行$THEN_HINT", withThen(JSONObject().put("app_name", strProp()).put("page", strProp()))))
        put(tool("ui_scan", "读当前界面上可交互的东西（能点的、输入框），每行「文字 (x,y)」。其它界面文字不返回 —— 想读内容用 screenshot_vision 截图。", JSONObject()))
        put(tool("tap_xy", "按坐标点击（x/y 像素，参照 screen_size 的宽高）$THEN_HINT", withThen(JSONObject().put("x", numProp()).put("y", numProp()))))
        put(tool("screenshot_vision", "截图并返回压缩后的图片。", JSONObject()))
        put(tool("wechat_type", "微信：点输入框→输入→发送→收起键盘$THEN_HINT", withThen(JSONObject().put("text", strProp()))))
        put(tool("wechat_search_contact", "微信：搜索联系人或群聊并打开$THEN_HINT", withThen(JSONObject().put("contact", strProp()))))
        put(tool("wechat_moments", "微信：进入朋友圈$THEN_HINT", withThen(JSONObject())))
        put(tool("play_song", "搜索并播放网易云歌曲$THEN_HINT", withThen(JSONObject().put("keyword", strProp()))))
        put(tool("open_url", "用浏览器打开网址$THEN_HINT", withThen(JSONObject().put("url", strProp()))))
    }

    private fun tool(name: String, desc: String, props: JSONObject): JSONObject {
        val schema = JSONObject().put("type", "object").put("properties", props)
        val required = JSONArray()
        for (k in props.keys()) { if (k != "duration" && k != "end" && k != "page" && k != "enter" && k != "shot" && k != "scan") required.put(k) }
        if (required.length() > 0) schema.put("required", required)
        return JSONObject().put("name", name).put("description", desc).put("inputSchema", schema)
    }

    /** 会动界面的那几个 —— 只有它们才值得「执行完再补一眼」 */
    private val actTools = setOf(
        "click", "tap_xy", "long_press_target", "swipe_screen", "type_text", "press", "go_back",
        "launch_app", "wechat_type", "wechat_search_contact", "wechat_moments", "play_song", "open_url"
    )

    /** 行动工具统一挂的两个可选尾巴：做完别急着回，等他 2 秒再补截图 / 补 UI 树 */
    private fun withThen(props: JSONObject): JSONObject =
        props.put("shot", boolProp()).put("scan", boolProp())

    /** 拼在行动工具描述末尾的提示语 */
    private val THEN_HINT = "。shot=执行后 2 秒截图，scan=执行后 2 秒扫界面，两个可以一起给"

    private fun numProp() = JSONObject().put("type", "number")
    private fun strProp() = JSONObject().put("type", "string")
    private fun boolProp() = JSONObject().put("type", "boolean")

    private fun mcpCall(name: String, args: JSONObject): JSONObject {
        if (AutoAccessibilityService.instance == null) {
            return JSONObject()
                .put("content", JSONArray().put(text("无障碍没开：先在平板上打开 AI Auto 的无障碍开关，再让我操作")))
                .put("isError", true)
        }
        val content = JSONArray()
        when (name) {
            "screen_size" -> {
                val size = AutoAccessibilityService.instance?.screenSize() ?: Pair(0, 0)
                content.put(text("宽 ${size.first}，高 ${size.second}"))
            }
            "current_app" -> {
                val pkg = AutoAccessibilityService.instance?.currentPackage().orEmpty()
                content.put(text(if (pkg.isBlank()) "读不到（可能被输入法或系统窗口盖着）" else "$pkg（${appLabelOf(pkg)}）"))
            }
            "tap_xy" -> {
                val x = args.optDouble("x", -1.0).toFloat()
                val y = args.optDouble("y", -1.0).toFloat()
                if (x < 0 || y < 0) content.put(text("坐标不对"))
                else {
                    onMainGet { AutoAccessibilityService.instance?.tap(x, y) }
                    content.put(text("已点击 ($x, $y)"))
                }
            }
            "click" -> {
                val t = args.optString("target")
                val c = parseCoordOrNull(t)
                if (c != null) {
                    onMainGet { AutoAccessibilityService.instance?.tap(c.first.toFloat(), c.second.toFloat()) }
                    content.put(text("已点击 (${c.first},${c.second})"))
                } else {
                    val ok = onMainGet { AutoAccessibilityService.instance?.clickNode(t) }
                    if (ok == true) {
                        content.put(text("已点击「$t」"))
                    } else {
                        val cc = resolveCoord(t)
                        if (cc == null) content.put(text("找不到: $t"))
                        else {
                            onMainGet { AutoAccessibilityService.instance?.tap(cc.first.toFloat(), cc.second.toFloat()) }
                            content.put(text("已点击「$t」(${cc.first},${cc.second})"))
                        }
                    }
                }
            }
            "press" -> {
                val key = args.optString("key", "back")
                val ok = onMainGet { AutoAccessibilityService.instance?.globalAction(key) }
                content.put(text(if (ok == true) "已执行 $key" else "执行失败: $key"))
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
            "type_text" -> {
                val t = args.optString("text")
                val enter = args.optBoolean("enter", false)
                val ok = onMainGet { AutoAccessibilityService.instance?.inputText(t) }
                if (ok != true) {
                    content.put(text("没找到输入框"))
                } else if (!enter) {
                    content.put(text("已输入"))
                } else {
                    // 等文字先落进输入框再回车（HTTP 线程 sleep，不卡界面）
                    try { Thread.sleep(500) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
                    val sent = onMainGet { AutoAccessibilityService.instance?.pressEnter() }
                    content.put(text(if (sent == true) "已输入并回车发送" else "已输入，但回车/发送没成功"))
                }
            }
            "go_back" -> { onMainGet { AutoAccessibilityService.instance?.back() }; content.put(text("已返回")) }
            "launch_app" -> {
                val app = args.optString("app_name"); val page = args.optString("page")
                val pkg = packageOf(app)
                if (page == "search") {
                    val uri = searchUriOf(pkg)
                    val ok = onMainGet { AutoAccessibilityService.instance?.openUri(uri) }
                    content.put(text(if (ok == true) "已打开 $app 的搜索页" else "打不开 $app 的搜索页"))
                } else {
                    val ok = onMainGet { AutoAccessibilityService.instance?.openApp(pkg) }
                    content.put(
                        text(
                            if (ok == true) "已打开 ${appLabelOf(pkg)}（$pkg）"
                            else "没找到应用：$app（解析成 $pkg，先 list_apps 看名字）"
                        )
                    )
                }
            }
            "ui_scan" -> content.put(text(uiText(uiBlocking())))
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
        settleThen(name, args, content)
        return JSONObject().put("content", content).put("isError", false)
    }

    /**
     * shot / scan：行动工具做完先等 2 秒（动画、页面跳转得走完，马上截多半是半截界面），
     * 再把截图 / UI 树并进这一次返回 —— 省掉「点一下再单独截一次」的往返。
     */
    private fun settleThen(name: String, args: JSONObject, content: JSONArray) {
        if (name !in actTools) return
        val shot = args.optBoolean("shot", false)
        val scan = args.optBoolean("scan", false)
        if (!shot && !scan) return
        try {
            Thread.sleep(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
        if (scan) content.put(text("[2 秒后界面]\n" + uiText(uiBlocking())))
        if (shot) {
            val f = captureBlocking() as? java.io.File
            if (f != null) {
                val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
                content.put(JSONObject().put("type", "image").put("data", b64).put("mimeType", "image/jpeg"))
            } else {
                content.put(text("(截图失败)"))
            }
        }
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
        // 中心按真实屏幕算（以前写死 920/1400，是手机的尺寸，平板就滑歪了）
        val (w, h) = svc.screenSize()
        val cx = w / 2
        val cy = h / 2
        val off = (h * 0.25f).toInt().coerceAtLeast(120)
        when (dir) {
            "up" -> svc.swipe(cx.toFloat(), (cy + off).toFloat(), cx.toFloat(), (cy - off).toFloat(), 300)
            "down" -> svc.swipe(cx.toFloat(), (cy - off).toFloat(), cx.toFloat(), (cy + off).toFloat(), 300)
            "left" -> svc.swipe((cx + off).toFloat(), cy.toFloat(), (cx - off).toFloat(), cy.toFloat(), 300)
            "right" -> svc.swipe((cx - off).toFloat(), cy.toFloat(), (cx + off).toFloat(), cy.toFloat(), 300)
        }
    }

    /** 应用名 / 包名 → 包名：先在能启动的应用里按名字找（全等 > 含） > 别名兜底 */
    private fun packageOf(app: String): String {
        val a = app.trim()
        if (a.isEmpty()) return a
        val ctx = appContext() ?: return a
        val pm = ctx.packageManager
        // 直接就是包名（装了才认）
        try {
            pm.getApplicationInfo(a, 0)
            return a
        } catch (_: Exception) {
        }
        val t = a.removeSuffix("app").removeSuffix("APP").trim()
        val apps = launcherApps(pm)
        apps.firstOrNull { it.second == t }?.let { return it.first }
        apps.firstOrNull { it.second.equals(t, true) }?.let { return it.first }
        apps.firstOrNull { t.length >= 2 && it.second.contains(t) }?.let { return it.first }
        apps.firstOrNull { t.length >= 2 && it.second.lowercase().contains(t.lowercase()) }?.let { return it.first }
        val known = mapOf(
            "微信" to "com.tencent.mm",
            "抖音" to "com.ss.android.ugc.aweme",
            "网易云" to "com.netease.cloudmusic",
            "b站" to "tv.danmaku.bili",
            "哔哩哔哩" to "tv.danmaku.bili",
            "浏览器" to "com.android.browser"
        )
        return known[a] ?: known[t] ?: a
    }

    /** ui_scan 的返回：只留能点的，一行「文字 (x,y)」；点不动的文字让模型自己看截图 */
    private fun uiText(nodes: org.json.JSONArray?): String {
        if (nodes == null || nodes.length() == 0) return "(没读到可点的东西)"
        val sb = StringBuilder()
        for (i in 0 until nodes.length()) {
            val o = nodes.getJSONObject(i)
            if (!o.optBoolean("click") && !o.optBoolean("input")) continue
            val label = when {
                !o.optString("text").isNullOrBlank() -> o.optString("text")
                !o.optString("desc").isNullOrBlank() -> o.optString("desc")
                o.optBoolean("input") -> "输入框"
                else -> ""
            }
            if (label.isBlank()) continue
            sb.append(label).append(" (").append(o.optInt("x")).append(",").append(o.optInt("y")).append(")\n")
        }
        return if (sb.isEmpty()) "(没读到可点的东西)" else sb.toString().trim()
    }

    private fun launcherApps(pm: android.content.pm.PackageManager): List<Pair<String, String>> {
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .distinctBy { it.activityInfo?.packageName }
                .mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    Pair(pkg, info.loadLabel(pm).toString())
                }
                .sortedBy { it.second }
                .take(300)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun appLabelOf(pkg: String): String = try {
        val pm = appContext()?.packageManager ?: return pkg
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
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

    /** 微信发消息：不再写死坐标 —— 点输入框（按可编辑节点找）→ 输入 → 点「发送」 */
    private fun wechatType(text: String) {
        main.post {
            val svc = AutoAccessibilityService.instance ?: return@post
            val input = svc.findInput()
            if (input != null) {
                // 有些输入框要先点一下才拿得到焦点
                val r = android.graphics.Rect(); input.getBoundsInScreen(r)
                svc.tap(((r.left + r.right) / 2).toFloat(), ((r.top + r.bottom) / 2).toFloat())
                main.postDelayed({ svc.inputTextAndSend(text) }, 500)
            } else {
                svc.inputTextAndSend(text)
            }
        }
    }

    /** 微信找联系人/群：点搜索（图标或「搜索」文字都认）→ 输入 → 点第一条结果 */
    private fun wechatSearch(contact: String) {
        main.post {
            val svc = AutoAccessibilityService.instance ?: return@post
            svc.clickNode("搜索") || svc.clickNode("Search")
            main.postDelayed({ svc.inputText(contact) }, 700)
            main.postDelayed({
                // 结果第一条通常在列表最上面：按文字先找，找不到就点屏幕上方第一条可点的
                if (!svc.clickNode(contact)) {
                    val nodes = svc.uiTree()
                    var best: Pair<Int, Int>? = null
                    for (i in 0 until nodes.length()) {
                        val o = nodes.getJSONObject(i)
                        if (!o.optBoolean("click")) continue
                        val y = o.optInt("y")
                        if (y < 200) continue
                        if (best == null || y < best!!.second) best = Pair(o.optInt("x"), y)
                    }
                    best?.let { svc.tap(it.first.toFloat(), it.second.toFloat()) }
                }
            }, 1500)
        }
    }

    /** 微信朋友圈：底部「发现」→「朋友圈」，都按文字点 */
    private fun wechatMoments() {
        main.post {
            val svc = AutoAccessibilityService.instance ?: return@post
            svc.clickNode("发现")
            main.postDelayed({ svc.clickNode("朋友圈") }, 800)
        }
    }

    private fun appContext(): android.content.Context? =
        App.ctx ?: AutoAccessibilityService.instance?.applicationContext
            ?: CaptureService.instance?.applicationContext

    private fun statusJson(): String {
        val o = JSONObject()
        val a11y = AutoAccessibilityService.instance != null
        o.put("accessibility", if (a11y) "on" else "off")
        o.put("capture", if (CaptureService.active) "on" else "off")
        // 截图有两条路：无障碍自带（不用授权录屏）优先，其次录屏授权
        o.put("screenshot", if (a11y) "accessibility" else if (CaptureService.active) "projection" else "none")
        o.put("port", port)
        o.put("token_required", readToken().isNotEmpty())
        startError?.let { o.put("error", it) }
        val size = AutoAccessibilityService.instance?.screenSize()
        o.put("width", size?.first ?: 0)
        o.put("height", size?.second ?: 0)
        o.put("package", AutoAccessibilityService.instance?.currentPackage() ?: "")
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
