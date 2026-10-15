package me.rerere.rikkahub.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Angel 无障碍服务：让 AI 通过无障碍通道读取屏幕并执行点击、输入、滑动等操作。
 * 与系统「无障碍」开关绑定，需用户主动开启；所有动作都来自用户授权的本地指令。
 */
class AngelAccessibilityService : AccessibilityService() {

    companion object {
        private const val MAX_NODES = 120

        val instance = MutableStateFlow<AngelAccessibilityService?>(null)

        /** 当前屏幕可交互节点摘要（供 AI 决策） */
        val screenSummary = MutableStateFlow("")

        /** 最近一次执行结果/状态消息 */
        val lastStatus = MutableStateFlow("")

        fun isConnected(): Boolean = instance.value != null

        fun refreshScreen() {
            instance.value?.let { service ->
                service.updateSummary()
                service.postStatus("屏幕已刷新")
            }
        }

        fun performTap(text: String): Boolean =
            instance.value?.tapNodeByText(text) ?: false

        fun performInput(text: String): Boolean =
            instance.value?.inputText(text) ?: false

        fun performScroll(direction: String): Boolean =
            instance.value?.scrollScreen(direction) ?: false

        fun performBack(): Boolean =
            instance.value?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false

        fun performHome(): Boolean =
            instance.value?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false

        fun postStatus(message: String) {
            lastStatus.value = message
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastSnapshot = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance.value = this
        updateSummary()
        postStatus("Angel 已就绪")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // 窗口变化或内容变化时刷新屏幕摘要（节流：100ms 内只刷一次）
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, 120)
    }

    override fun onInterrupt() {
        // 系统中断
    }

    override fun onDestroy() {
        instance.value = null
        postStatus("Angel 已断开")
        super.onDestroy()
    }

    private val refreshRunnable = Runnable { updateSummary() }

    private fun updateSummary() {
        val root = rootInActiveWindow ?: return
        val lines = mutableListOf<String>()
        collectNodes(root, 0, lines)
        val text = lines.joinToString("\n")
        lastSnapshot = text
        screenSummary.value = text
    }

    private fun collectNodes(node: AccessibilityNodeInfo?, depth: Int, out: MutableList<String>) {
        if (node == null || out.size >= MAX_NODES) return
        if (depth > 10) return

        val isClickable = node.isClickable
        val isEditable = node.className?.toString()?.contains("EditText") == true
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val label = text.ifEmpty { desc }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val visible = rect.width() > 0 && rect.height() > 0 && rect.right > 0

        if (visible && (isClickable || isEditable || (label.isNotEmpty() && label.length <= 60))) {
            val kind = when {
                isEditable -> "输入框"
                isClickable -> "可点击"
                else -> "文本"
            }
            val bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
            out += "$kind|$bounds|$label"
        }

        for (i in 0 until node.childCount) {
            collectNodes(node.getChild(i), depth + 1, out)
        }
    }

    private fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val normalized = text.trim()
        if (normalized.isEmpty()) return null
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectMatching(root, normalized, candidates)
        // 优先选面积最小（最上层/最精确）的可点击节点
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        for (node in candidates) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val area = bounds.width().toLong() * bounds.height().toLong()
            if (area < bestArea) {
                bestArea = area
                best = node
            }
        }
        return best
    }

    private fun collectMatching(node: AccessibilityNodeInfo, text: String, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val nodeText = node.text?.toString()?.trim().orEmpty()
        val nodeDesc = node.contentDescription?.toString()?.trim().orEmpty()
        if (nodeText == text || nodeDesc == text || nodeText.contains(text) || nodeDesc.contains(text)) {
            if (node.isClickable || node.isVisibleToUser) out += node
        }
        for (i in 0 until node.childCount) {
            collectMatching(node.getChild(i), text, out)
        }
    }

    private fun tapNodeByText(text: String): Boolean {
        val node = findNodeByText(text) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val edit = findEditable(root) ?: return false
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("angel", text))
        val bundle = android.os.Bundle()
        bundle.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text,
        )
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findEditable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun scrollScreen(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val viewport = resources.displayMetrics.run { android.graphics.Rect(0, 0, widthPixels, heightPixels) }
        val startY = viewport.height() / 2
        val deltaY = (viewport.height() * 2 / 5).toInt()
        val path = Path().apply {
            when (direction) {
                "down" -> {
                    moveTo(viewport.centerX().toFloat(), startY.toFloat())
                    lineTo(viewport.centerX().toFloat(), (startY - deltaY).toFloat())
                }
                "up" -> {
                    moveTo(viewport.centerX().toFloat(), startY.toFloat())
                    lineTo(viewport.centerX().toFloat(), (startY + deltaY).toFloat())
                }
                else -> return root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 200))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun postStatus(message: String) {
        lastStatus.value = message
    }
}
