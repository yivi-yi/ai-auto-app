package com.example.aiauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class AutoAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AutoAccessibilityService? = null
        var enabled: Boolean = false
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
            val stroke = GestureDescription.StrokeDescription(path, 0, 80)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        main.post {
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun uiTree(): JSONArray {
        val root = rootInActiveWindow ?: return JSONArray()
        val arr = JSONArray()
        buildNode(root, arr, 0)
        return arr
    }

    private fun buildNode(node: AccessibilityNodeInfo, parent: JSONArray, depth: Int) {
        if (depth > 14) return
        val o = JSONObject()
        o.put("text", node.text?.toString())
        o.put("id", node.viewIdResourceName)
        o.put("class", node.className?.toString())
        val r = Rect()
        node.getBoundsInScreen(r)
        o.put("x", (r.left + r.right) / 2)
        o.put("y", (r.top + r.bottom) / 2)
        o.put("clickable", node.isClickable)
        o.put("scrollable", node.isScrollable)
        val children = JSONArray()
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            buildNode(c, children, depth + 1)
        }
        if (children.length() > 0) o.put("children", children)
        parent.put(o)
    }

    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val n = findClickable(root, text) ?: return false
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findClickable(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val t = node.text?.toString()
        if (t != null && (t == text || t.contains(text)) && node.isClickable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findClickable(c, text)
            if (r != null) return r
        }
        return null
    }
}
