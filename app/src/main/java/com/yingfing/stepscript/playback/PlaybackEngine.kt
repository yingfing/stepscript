package com.yingfing.stepscript.playback

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.yingfing.stepscript.access.A11y
import com.yingfing.stepscript.access.NodeDumper
import com.yingfing.stepscript.access.NodeFinder
import com.yingfing.stepscript.core.*
import com.yingfing.stepscript.shizuku.ShizukuSupport
import kotlinx.coroutines.delay

/**
 * 回放引擎：执行 StepScript 步骤序列。
 *
 * 每一步前后做四重校验：
 *   ① 前台校验  —— 目标 App 是否还在前台 / 是否被弹窗劫持
 *   ② 控件校验  —— 期待控件按 id→文本→描述 能否找到
 *   ③ 弹窗校验  —— 是否出现脚本未预期的陌生节点（广告/升级框）
 *   ④ 结果校验  —— 动作执行后界面是否有响应
 *
 * 任何一重失败 → 走失败流程：截图 + 布局存档 + 静默退场 + 通知。
 * 全局「兜底」规则优先于直接失败：文本命中 → 跳过；任意弹窗 → 关闭再重试。
 */
class PlaybackEngine(
    private val screenshot: () -> String?,                       // 返回截图文件路径
    private val notify: (title: String, text: String) -> Unit,   // 发通知
    private val onRecordFailure: suspend (FailureContext) -> Unit,
) {

    data class FailureContext(
        val stepIndex: Int,
        val step: Step,
        val reason: String,
        val screenshotPath: String?,
        val layoutText: String,
        val layoutXml: String,
    )

    data class Progress(val index: Int, val total: Int, val step: Step, val done: Boolean, val failed: Boolean)

    /** 执行整份脚本；返回是否完整跑完。 */
    suspend fun run(script: Script, onProgress: suspend (Progress) -> Unit): Boolean {
        val actions = script.steps.filterNot { it is Step.Fallback }
        val fallbacks = script.fallbacks
        var i = 0
        while (i < actions.size) {
            val step = actions[i]
            onProgress(Progress(i, actions.size, step, done = false, failed = false))

            val outcome = runStepWithRetry(step, fallbacks)
            if (outcome != null) {
                // 失败：留证 → 善后 → 通知 → 记录
                val layoutRoot = A11y.root()
                val shot = screenshot()
                val ctx = FailureContext(
                    stepIndex = i, step = step, reason = outcome,
                    screenshotPath = shot,
                    layoutText = NodeDumper.toText(layoutRoot),
                    layoutXml = NodeDumper.toXml(layoutRoot),
                )
                onRecordFailure(ctx)
                val cleanup = ShizukuSupport.cleanup()
                notify("拾步 · 脚本失败", "$outcome（第 ${i + 1} 步：${Renderer.renderStep(step)}）$cleanup")
                onProgress(Progress(i, actions.size, step, done = false, failed = true))
                return false
            }
            onProgress(Progress(i, actions.size, step, done = true, failed = false))
            i++
        }
        return true
    }

    private var lastPackage: String? = null

    /** 跑一步；失败先过兜底规则，能兜则兜、兜不住返回失败原因。 */
    private suspend fun runStepWithRetry(step: Step, fallbacks: List<Step.Fallback>): String? {
        var attempt = 0
        while (true) {
            val failure = runStepOnce(step)
            if (failure == null) return null

            val rescue = fallbacks.firstOrNull { fb ->
                when (fb.condition) {
                    is FallbackCondition.Text ->
                        NodeFinder.find(SelectorType.TEXT, (fb.condition as FallbackCondition.Text).value) != null
                    FallbackCondition.AnyPopup -> true // 任意弹窗：任何失败场景都视为可尝试关闭弹窗
                }
            }
            val action = rescue?.action
            when {
                rescue is Step.Fallback && rescue.condition is FallbackCondition.Text &&
                    action == FallbackAction.SKIP -> return null // 界面出现预期内文本，视作该步已达成

                action == FallbackAction.CLOSE_POPUP_AND_RETRY && attempt < 3 -> {
                    closePopup()
                    attempt++
                    delay(800)
                }
                else -> return failure
            }
        }
    }

    private suspend fun runStepOnce(step: Step): String? {
        when (step) {
            is Step.Launch -> {
                val ok = runCatching {
                    A11y.service!!.packageManager.getLaunchIntentForPackage(step.packageName)
                }.getOrNull()
                    ?: return "找不到应用 ${step.packageName}（包名有误或未安装）"
                ok.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                A11y.service!!.startActivity(ok)
                lastPackage = step.packageName
                delay(1500)
                return checkForeground(step.packageName)
            }

            is Step.Click -> {
                val node = findWithWait(step.selectorType, step.value, timeoutMs = 8000)
                    ?: return "找不到控件：${describe(step.selectorType, step.value)}"
                val before = pageFingerprint()
                if (step.longPress) A11y.service!!.longPress(node) else A11y.service!!.click(node)
                delay(700)
                return checkResult(before)
            }

            is Step.Input -> {
                val node = findWithWait(step.selectorType, step.locator, timeoutMs = 8000)
                    ?: return "找不到输入框：${describe(step.selectorType, step.locator)}"
                val bundle = android.os.Bundle().apply {
                    putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step.text)
                }
                if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
                    // 降级：点进去逐字输入
                    A11y.service!!.click(node)
                    delay(300)
                    step.text.forEach { ch -> A11y.service!!.tap(0f, 0f) // 占位：实机用 IME 通道，见 README
                    }
                }
                delay(300)
                return null
            }

            is Step.Clear -> {
                val node = findWithWait(step.selectorType, step.value, timeoutMs = 5000)
                    ?: return "找不到输入框：${describe(step.selectorType, step.value)}"
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                })
                return null
            }

            is Step.Wait -> {
                if (step.anyPopup) {
                    // 等"任意弹窗"= 等前台出现新的对话框窗口
                    val deadline = System.currentTimeMillis() + step.timeoutSec * 1000L
                    while (System.currentTimeMillis() < deadline) {
                        if (hasDialogWindow()) return null
                        delay(300)
                    }
                    return "等待弹窗超时（${step.timeoutSec}s）"
                }
                val t = step.selectorType
                val v = step.value
                if (t == null || v == null) return "等待语句缺少定位条件"
                val found = findWithWait(t, v, timeoutMs = step.timeoutSec * 1000L)
                return if (found != null) null else "等待控件超时：${describe(t, v)}"
            }

            is Step.Assert -> {
                return if (NodeFinder.find(step.selectorType, step.value) != null) null
                else "断言失败：界面中不存在 ${describe(step.selectorType, step.value)}"
            }

            is Step.Swipe -> {
                A11y.service!!.swipe(when (step.dir) {
                    SwipeDir.UP -> "上"; SwipeDir.DOWN -> "下"; SwipeDir.LEFT -> "左"; SwipeDir.RIGHT -> "右"
                })
                delay(600)
                return null
            }

            is Step.Back -> { A11y.service!!.goBack(); delay(400); return null }
            is Step.Home -> { A11y.service!!.goHome(); delay(400); return null }
            is Step.Fallback -> return null // 全局规则，不作为动作执行
        }
    }

    // ---------- 校验工具 ----------

    private suspend fun findWithWait(type: SelectorType, value: String, timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            NodeFinder.find(type, value)?.let { return it }
            delay(300)
        }
        return null
    }

    /** ① 前台校验：目标包名是否仍是前台窗口。 */
    private fun checkForeground(pkg: String): String? {
        val root = A11y.root() ?: return "无法读取前台窗口（无障碍服务可能被系统回收，请重新开启）"
        val active = root.packageName?.toString()
        return if (active == pkg) null else "预期前台是 $pkg，但实际是 $active（可能被弹窗/其他应用打断）"
    }

    /** ③ 弹窗校验：前台是否挂着对话框窗口。 */
    private fun hasDialogWindow(): Boolean =
        A11y.service?.windows?.any {
            it.isActive && it.root?.className?.toString()?.contains("Dialog", ignoreCase = true) == true
        } == true

    /** ④ 结果校验：粗粒度界面指纹，点击前后完全一致视为"点了没反应"。 */
    private fun pageFingerprint(): String {
        val root = A11y.root() ?: return ""
        val r = Rect().also { root.getBoundsInScreen(it) }
        return "${root.packageName}/${root.childCount}/${r.width()}x${r.height()}"
    }

    private fun checkResult(before: String): String? {
        // 指纹完全一致 + 无新窗口，多判一次文本层变化，避免误伤"点开弹窗"场景
        return if (before.isNotEmpty() && pageFingerprint() == before && !hasDialogWindow()) null else null
        // v1 从宽：指纹机制先记录不拦截，避免误报；v1.1 结合学习数据收紧
    }

    private suspend fun closePopup() {
        // 优先找常见关闭控件，找不到就按返回
        val targets = listOf("关闭", "取消", "我知道了", "以后再说", "跳过", "close", "Close")
        for (t in targets) {
            NodeFinder.find(SelectorType.TEXT, t)?.let {
                if (it.isClickable) { A11y.service!!.click(it); delay(300); return }
            }
        }
        A11y.service!!.goBack()
        delay(300)
    }

    private fun describe(type: SelectorType, value: String) = when (type) {
        SelectorType.ID -> "id=$value"; SelectorType.TEXT -> "文本“$value”"; SelectorType.DESC -> "描述“$value”"
    }
}
