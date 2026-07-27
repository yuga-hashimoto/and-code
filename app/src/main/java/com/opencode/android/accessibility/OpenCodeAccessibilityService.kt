package com.opencode.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class OpenCodeAccessibilityService : AccessibilityService() {
    @Volatile
    private var lastRoot: AccessibilityNodeInfo? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        lastRoot = rootInActiveWindow
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    fun captureViewTree(): String {
        val root = lastRoot ?: rootInActiveWindow ?: return JSONObject().put("error", "No window content").toString()
        val tree = nodeToJson(root)
        recycleTree(root)
        return tree.toString(2)
    }

    fun performTap(
        x: Float,
        y: Float,
    ): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun performSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 300,
    ): Boolean {
        val path =
            Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun performRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun typeText(text: String): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun nodeToJson(node: AccessibilityNodeInfo): JSONObject =
        JSONObject().apply {
            put("class", node.className?.toString() ?: "")
            put("text", node.text?.toString() ?: "")
            put("resource_id", node.viewIdResourceName ?: "")
            put("content_desc", node.contentDescription?.toString() ?: "")
            put("clickable", node.isClickable)
            put("focusable", node.isFocusable)
            put("scrollable", node.isScrollable)
            put("editable", node.isEditable)
            put("enabled", node.isEnabled)
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            put("bounds", JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom))
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    children.put(nodeToJson(child))
                }
            }
            if (children.length() > 0) put("children", children)
        }

    private fun recycleTree(node: AccessibilityNodeInfo) {
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let(::recycleTree)
        }
    }

    companion object {
        @Volatile
        var instance: OpenCodeAccessibilityService? = null
    }
}
