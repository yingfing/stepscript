package com.yingfing.stepscript.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.yingfing.stepscript.core.SelectorType

/**
 * 全局无障碍服务单例：拾步一切"接触界面"的能力都从这里走——
 * 抓节点树、执行点击/手势、承载悬浮拾取层、监听事件供录制器使用。
 */
object A11y {
    @Volatile var service: StepAccessibilityService? = null
        private set

    internal fun attach(s: StepAccessibilityService) { service = s }
    internal fun detach() { service = null }

    val isReady: Boolean get() = service != null

    /** 当前前台窗口的根节点（可能为 null：无权限或目标界面禁止抓取）。 */
    fun root(): AccessibilityNodeInfo? =
        service?.let { it.rootInActiveWindow ?: it.windows.firstOrNull()?.root }
}

class StepAccessibilityService : AccessibilityService() {

    /** 录制器 / 回放引擎订阅事件用；界面层通过它把事件流接走。 */
    var onEvent: ((AccessibilityEvent) -> Unit)? = null

    /** 悬浮拾取器：非空时表示处于"指哪取哪"模式。 */
    var pickerOverlay: PickerOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        A11y.attach(this)
    }

    override fun onDestroy() {
        A11y.detach()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let { onEvent?.invoke(it) }
    }

    override fun onInterrupt() {}

    // ---------- 动作执行 ----------

    /** 对指定节点执行点击；优先用节点自带 ACTION_CLICK（最可靠），失败则降级为手势点中心。 */
    fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect().also { node.getBoundsInScreen(it) }
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    fun longPress(node: AccessibilityNodeInfo): Boolean {
        val r = Rect().also { node.getBoundsInScreen(it) }
        return gesture(r.exactCenterX(), r.exactCenterY(), startTimeMs = 0, durationMs = 900)
    }

    fun tap(x: Float, y: Float): Boolean = gesture(x, y, 0, 60)

    fun gesture(x: Float, y: Float, startTimeMs: Long, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, startTimeMs, durationMs)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** 整屏滑动。屏幕宽度/高度从当前窗口取。 */
    fun swipe(dir: String): Boolean {
        val r = Rect().also { A11y.root()?.getBoundsInScreen(it) }
        val cx = r.exactCenterX()
        val cy = r.exactCenterY()
        val dx = r.width() * 0.6f
        val dy = r.height() * 0.6f
        val path = Path()
        when (dir) {
            "上" -> { path.moveTo(cx, cy + dy / 2); path.lineTo(cx, cy - dy / 2) }
            "下" -> { path.moveTo(cx, cy - dy / 2); path.lineTo(cx, cy + dy / 2) }
            "左" -> { path.moveTo(cx + dx / 2, cy); path.lineTo(cx - dx / 2, cy) }
            else -> { path.moveTo(cx - dx / 2, cy); path.lineTo(cx + dx / 2, cy) }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)

    // ---------- 悬浮窗拾取 ----------

    /** 进入/退出"指哪取哪"拾取模式。 */
    fun startPick(onPicked: (AccessibilityNodeInfo) -> Unit) {
        pickerOverlay?.remove()
        pickerOverlay = PickerOverlay(this, onPicked).also { it.show() }
    }

    fun stopPick() {
        pickerOverlay?.remove()
        pickerOverlay = null
    }

    val isPicking: Boolean get() = pickerOverlay != null
}

/**
 * 控件查找：按 SelectorType 在当前窗口找第一个匹配节点。
 * id 用 viewIdResourceName 精确匹配；文本/描述为包含匹配。
 */
object NodeFinder {
    fun find(type: SelectorType, value: String): AccessibilityNodeInfo? {
        val root = A11y.root() ?: return null
        return when (type) {
            SelectorType.ID -> root.findAccessibilityNodeInfosByViewId(value).firstOrNull()
                ?: root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/$value").firstOrNull()
            SelectorType.TEXT -> root.findAccessibilityNodeInfosByText(value).firstOrNull()
            SelectorType.DESC -> findByDesc(root, value)
        }
    }

    private fun findByDesc(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString()?.contains(desc) == true) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findByDesc(it, desc)?.let { hit -> return hit } }
        }
        return null
    }
}
