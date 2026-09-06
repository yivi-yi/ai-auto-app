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

    /** 精简 UI 树：只返回文字与可交互按键 + 中心坐标 */
    fun uiTree(): JSONArray {
        val root = rootInActiveWindow ?: return JSONArray()
        val arr = JSONArray()
        walk(root, arr)
        return arr
    }

    private fun walk(node: AccessibilityNodeInfo, arr: JSONArray) {
        val t = node.text?.toString()
        if ((!t.isNullOrEmpty()) || node.isClickable) {
            val o = JSONObject()
            o.put("text", t ?: "")
            o.put("click", node.isClickable)
            val r = Rect(); node.getBoundsInScreen(r)
            o.put("x", (r.left + r.right) / 2)
            o.put("y", (r.top + r.bottom) / 2)
            arr.put(o)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it, arr) }
        }
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

    private fun findClickable(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val t = node.text?.toString()
        if (t != null && (t == text || t.contains(text)) && node.isClickable) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findClickable(c, text)
            if (r != null) return r
        }
        return null
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
