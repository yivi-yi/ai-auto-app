package com.example.aiauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.net.Uri
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class AutoAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AutoAccessibilityService? = null
        var enabled = false
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        enabled = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance == this) instance = null
        enabled = false
        super.onDestroy()
    }

    fun tap(x: Float, y: Float) {
        main.post {
            val path = Path().apply { moveTo(x, y) }
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build(), null, null)
        }
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        main.post {
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)
        }
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** 返回/主页/最近任务/下拉通知/锁屏 —— 别硬编码坐标，交给系统 */
    fun globalAction(name: String): Boolean = when (name) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "lock" -> if (android.os.Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
        "power" -> if (android.os.Build.VERSION.SDK_INT >= 21) performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) else false
        else -> false
    }

    /** 屏幕真实尺寸：滑动/坐标都不能写死（手机和平板差太多） */
    fun screenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    /** 当前前台包名 */
    fun currentPackage(): String {
        val root = rootInActiveWindow ?: return ""
        return root.packageName?.toString() ?: ""
    }

    /** 精简 UI 树：文字 / 图标描述 / 输入框 / 可滚动 + 中心坐标（给 AI 找目标用） */
    fun uiTree(): JSONArray {
        val root = rootInActiveWindow ?: return JSONArray()
        val arr = JSONArray()
        walk(root, arr, 0)
        return arr
    }

    private fun walk(node: AccessibilityNodeInfo, arr: JSONArray, depth: Int) {
        if (depth > 40 || arr.length() > 300) return
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        if (!t.isNullOrEmpty() || !d.isNullOrEmpty() || node.isClickable || node.isEditable) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                val o = JSONObject()
                if (!t.isNullOrEmpty()) o.put("text", t)
                if (!d.isNullOrEmpty()) o.put("desc", d)
                o.put("click", node.isClickable || ancestorClickable(node, 5))
                if (node.isEditable) o.put("input", true)
                if (node.isScrollable) o.put("scroll", true)
                o.put("x", (r.left + r.right) / 2)
                o.put("y", (r.top + r.bottom) / 2)
                arr.put(o)
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it, arr, depth + 1) }
        }
    }

    /** 文字节点自己不可点、但外层列表项可点 —— 这种情况也算"可以点这里" */
    private fun ancestorClickable(node: AccessibilityNodeInfo, up: Int): Boolean {
        var cur = node.parent
        var n = 0
        while (cur != null && n < up) {
            if (cur.isClickable) return true
            cur = cur.parent
            n++
        }
        return false
    }

    /** 向当前聚焦/可编辑控件输入文字 */
    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findEditable(c)
            if (r != null) return r
        }
        return null
    }

    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val n = findClickable(root, text) ?: return false
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** 文字 / 描述都能找；节点本身不可点就往上找最近的可点祖先（图标按钮基本都这样） */
    private fun findClickable(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val t = node.text?.toString()
        val d = node.contentDescription?.toString()
        if ((t != null && (t == text || t.contains(text))) ||
            (d != null && (d == text || d.contains(text)))
        ) {
            var cur: AccessibilityNodeInfo? = node
            var hop = 0
            while (cur != null && hop < 6) {
                if (cur.isClickable) return cur
                cur = cur.parent
                hop++
            }
            return node
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findClickable(c, text)
            if (r != null) return r
        }
        return null
    }

    /** 找节点并直接点（不要求它自己可点） */
    fun clickNode(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val n = findClickable(root, text) ?: return false
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** 找当前输入框（微信这类要先把输入框点开，不然拿不到焦点） */
    fun findInput(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
    }

    /** 输入文字并回车（找「发送」/「搜索」按钮，找不到就发 IME 回车） */
    fun inputTextAndSend(text: String, sendLabel: String = ""): Boolean {
        if (!inputText(text)) return false
        main.postDelayed({
            val labels = mutableListOf("发送", "Send", "send")
            if (sendLabel.isNotBlank()) labels.add(0, sendLabel)
            for (l in labels) {
                if (clickNode(l)) return@postDelayed
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                findInput()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }, 500)
        return true
    }

    /** 打开指定包名的 APP */
    fun openApp(pkg: String): Boolean {
        return try {
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    /** 用浏览器/对应应用打开 uri（点歌、开网址、搜索页等） */
    fun openUri(uri: String): Boolean {
        return try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }
}
