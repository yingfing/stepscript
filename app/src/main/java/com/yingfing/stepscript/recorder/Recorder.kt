package com.yingfing.stepscript.recorder

import android.view.accessibility.AccessibilityEvent
import com.yingfing.stepscript.access.A11y
import com.yingfing.stepscript.access.NodeFinder
import com.yingfing.stepscript.core.SelectorType
import com.yingfing.stepscript.core.Step

/**
 * 录制器：实时监听无障碍事件，把用户的自由操作转成脚本步骤。
 *
 * 录制策略（噪声控制）：
 *  - TYPE_VIEW_CLICKED 事件 → 记一条「点击」；
 *  - TYPE_WINDOW_STATE_CHANGED 且包名变化 → 记一条「启动」（用户切换了应用）；
 *  - 输入框失焦且文本变化 → 记一条「输入」（正在编辑时实时文本会刷屏，只在收尾记录一次）；
 *  - 不记录滚动/滑动（噪声大），需要时用户在编辑器里手动加「滑动」语句。
 */
class Recorder {

    data class Recording(val packageName: String?, val steps: List<Step>)

    private val steps = mutableListOf<Step>()
    private val inputBuffers = mutableMapOf<String, String>() // id -> 最近文本
    var targetPackage: String? = null
        private set

    fun onEvent(e: AccessibilityEvent): Step? {
        val produced: Step? = when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = e.packageName?.toString() ?: return null
                if (pkg != targetPackage && pkg !in IGNORED_PACKAGES) {
                    targetPackage = pkg
                    Step.Launch(line = steps.size + 1, packageName = pkg)
                } else null
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = e.source ?: return null
                pickSelector(node)?.let { (t, v) -> Step.Click(steps.size + 1, t, v) }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val id = e.source?.viewIdResourceName ?: return null
                inputBuffers[id] = e.text?.joinToString("") ?: ""
                null // 收尾时统一转成输入语句
            }
            else -> null
        }
        produced?.let { steps += it }
        return produced
    }

    /** 结束录制：把输入缓冲落成「输入」语句，返回全部步骤。 */
    fun finish(): Recording {
        inputBuffers.forEach { (id, text) ->
            if (text.isNotEmpty()) steps += Step.Input(steps.size + 1, SelectorType.ID, id, text)
        }
        return Recording(targetPackage, steps.toList())
    }

    /** 给节点挑一个最稳的定位：id > text > desc。 */
    private fun pickSelector(node: android.view.accessibility.AccessibilityNodeInfo): Pair<SelectorType, String>? {
        node.viewIdResourceName?.let { return SelectorType.ID to it.substringAfterLast('/') }
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return SelectorType.TEXT to it }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return SelectorType.DESC to it }
        // 找不到特征时向上找带文本的父节点
        var p = node.parent
        var hops = 0
        while (p != null && hops++ < 3) {
            p.text?.toString()?.takeIf { it.isNotBlank() }?.let { return SelectorType.TEXT to it }
            p = p.parent
        }
        return null
    }

    companion object {
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui", "com.yingfing.stepscript",
        )
    }
}
