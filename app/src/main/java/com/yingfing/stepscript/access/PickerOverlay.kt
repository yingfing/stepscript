package com.yingfing.stepscript.access

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout

/**
 * 悬浮拾取层：用无障碍自带的 TYPE_ACCESSIBILITY_OVERLAY（不需要悬浮窗权限），
 * 盖一层透明点击拦截层，手指按下/滑动时实时找"指尖下最深可点击节点"并高亮描红框。
 * 松手后回调选中的节点，并展示信息卡（由 UI 层在 App 内展示，这里只负责拾取与高亮）。
 */
class PickerOverlay(
    private val service: StepAccessibilityService,
    private val onPicked: (AccessibilityNodeInfo) -> Unit,
) {

    private var container: FrameLayout? = null
    private var highlight: View? = null

    fun show() {
        val root = FrameLayout(service)
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
        root.setBackgroundColor(0x22000000)

        val box = View(service)
        box.layoutParams = FrameLayout.LayoutParams(0, 0)
        box.background = android.graphics.drawable.GradientDrawable().apply {
            setStroke(6, 0xFFFF4444.toInt())
            setColor(0x33FF4444)
        }
        root.addView(box)
        highlight = box

        root.setOnTouchListener { _, e ->
            val node = service.rootInActiveWindow?.let { r ->
                // 找指尖处最深的可视节点；找不到再退回根
                r.let { hit -> hit }
            }
            val target = pickAt(service, e.rawX, e.rawY)
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                    target?.let { showHighlight(it) }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    target?.let {
                        showHighlight(it)
                        onPicked(it)
                    }
                    true
                }
                else -> false
            }
        }

        // 以无障碍 overlay 方式挂到窗口上
        service.let {
            val wm = it.getSystemService(android.view.WindowManager::class.java)
            val lp = android.view.WindowManager.LayoutParams().apply {
                type = android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                format = android.graphics.PixelFormat.TRANSLUCENT
                flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                width = android.view.WindowManager.LayoutParams.MATCH_PARENT
                height = android.view.WindowManager.LayoutParams.MATCH_PARENT
                gravity = Gravity.TOP or Gravity.START
            }
            wm.addView(root, lp)
        }
        container = root
    }

    private fun pickAt(service: AccessibilityService, x: Float, y: Float): AccessibilityNodeInfo? {
        // AccessibilityNodeInfo 支持 performAction 的精确命中：
        // 先取根，再逐层找包含该点的最深层子节点
        val root = A11y.root() ?: return null
        var best: AccessibilityNodeInfo? = null
        fun dfs(n: AccessibilityNodeInfo) {
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (!r.contains(x.toInt(), y.toInt())) return
            if (n.isVisibleToUser && (n.isClickable || best == null)) best = n
            for (i in 0 until n.childCount) n.getChild(i)?.let { dfs(it) }
        }
        dfs(root)
        return best
    }

    private fun showHighlight(node: AccessibilityNodeInfo) {
        val r = Rect().also { node.getBoundsInScreen(it) }
        highlight?.let { h ->
            val p = h.layoutParams as FrameLayout.LayoutParams
            p.leftMargin = r.left - 8
            p.topMargin = r.top - 8
            p.width = r.width() + 16
            p.height = r.height() + 16
            h.layoutParams = p
        }
    }

    fun remove() {
        container?.let { c ->
            runCatching {
                service.getSystemService(android.view.WindowManager::class.java).removeView(c)
            }
        }
        container = null
        highlight = null
    }
}
