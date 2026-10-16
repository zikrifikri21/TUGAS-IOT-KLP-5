package com.berkacaki

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MirrorAutoAcceptService : AccessibilityService() {

    private val acceptButtonTexts = listOf(
        "Mulai",
        "Start now",
        "Mulai sekarang",
        "Mulai Sekarang",
        "Start",
        "Izinkan",
        "Allow",
        "Accept",
        "Agree",
        "Setuju",
        "Lanjutkan"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        // We removed the restrictive package name filter to support custom OEM skins (Xiaomi, Oppo, Vivo, Realme, Samsung, etc.)
        // which may display the screen recording confirmation dialog under custom security or system packages.

        // Try scanning event.source first as it's the node that directly triggered the change
        val source = event.source
        if (source != null) {
            if (findAndClickAcceptButton(source)) {
                source.recycle()
                return
            }
            source.recycle()
        }

        // Try scanning the active window
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            if (findAndClickAcceptButton(activeRoot)) {
                activeRoot.recycle()
                return
            }
            activeRoot.recycle()
        }

        // Fallback: scan all interactive windows (important for system overlay dialogs)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val currentWindows = windows
                if (currentWindows != null) {
                    for (window in currentWindows) {
                        val windowRoot = window.root
                        if (windowRoot != null) {
                            if (findAndClickAcceptButton(windowRoot)) {
                                windowRoot.recycle()
                                return
                            }
                            windowRoot.recycle()
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MirrorAutoAccept", "Error scanning windows: ${e.message}")
            }
        }
    }

    private fun findAndClickAcceptButton(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val text = node.text?.toString()?.trim() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""

        if (text.isNotEmpty() || contentDesc.isNotEmpty()) {
            for (target in acceptButtonTexts) {
                // Ensure we only match relatively short texts to avoid clicking long descriptive paragraphs
                val isShortText = text.length < 25 && contentDesc.length < 25
                if (isShortText) {
                    val isTextMatch = text.equals(target, ignoreCase = true) || 
                            text.startsWith(target, ignoreCase = true)
                    val isDescMatch = contentDesc.equals(target, ignoreCase = true) || 
                            contentDesc.startsWith(target, ignoreCase = true)

                    if (isTextMatch || isDescMatch) {
                        val clickableNode = findClickableSelfOrParent(node)
                        if (clickableNode != null) {
                            val success = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (success) {
                                android.util.Log.d("MirrorAutoAccept", "Auto-clicked button matching '$target' (text: '$text', desc: '$contentDesc')")
                                clickableNode.recycle()
                                return true
                            }
                            clickableNode.recycle()
                        }
                    }
                }
            }
        }

        // Recurse children
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (findAndClickAcceptButton(child)) {
                    child.recycle()
                    return true
                }
                child.recycle()
            }
        }

        return false
    }

    private fun findClickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return AccessibilityNodeInfo.obtain(current)
            }
            current = current.parent
        }
        return null
    }

    override fun onInterrupt() {}
}