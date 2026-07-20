package com.jimi.ai

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * This service is Jimi's "hands and eyes" on the screen. Once the user enables it
 * (Settings > Accessibility > Jimi), it can:
 *  - read what's currently on screen (findNodeByText / findNodeById)
 *  - click a button/node (clickNode)
 *  - type text into a focused field (typeIntoNode)
 *
 * WhatsAppAutomator, AppLauncher etc. all call into this instance.
 */
class JimiAccessibilityService : AccessibilityService() {

    companion object {
        // Static reference so other classes (WhatsAppAutomator, YouTubeHelper) can use
        // the running service instance without needing a bound-service connection.
        var instance: JimiAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to react to every event right now; WhatsAppAutomator polls
        // rootInActiveWindow directly after triggering an intent. Left here so the
        // service can be extended later (e.g. auto-replying to incoming notifications).
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    /** Searches the current screen for a node whose text or content-description contains [text]. */
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return searchNode(root, text)
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

    /** Finds the first EditText-like focused/editable node on screen. */
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
        // Clickable action often needs to be performed on a clickable ancestor
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
}
