package com.jimi.ai

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JimiAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JimiAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return searchNode(root, text)
    }

    fun findNodeById(resourceId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
        return nodes?.firstOrNull()
    }

    private fun searchNode(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        val nodeDesc = node.contentDescription?.toString() ?: ""
        if (nodeText.contains(text, ignoreCase = true) || nodeDesc.contains(text, ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchNode(child, text)
            if (found != null) return found
        }
        return null
    }

    fun findEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return searchEditable(root)
    }

    private fun searchEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchEditable(child)
            if (found != null) return found
        }
        return null
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    fun typeIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /** Poori current screen ka visible text nikalta hai (sab nodes traverse karke) —
     * "iska matlab kya hai" / "translate karo" jaise screen-explain commands ke liye. */
    fun getScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        collectText(root, sb)
        return sb.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) sb.append(text).append(" ")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, sb)
        }
    }
}
