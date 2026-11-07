package com.yingfing.stepscript.core

/**
 * 把 [Script] / [Step] 反向渲染回 StepScript 源码文本。
 * 用途：AI 补丁 diff 展示、脚本编辑器「图形 → 文本」同步、录制器生成脚本。
 */
object Renderer {

    fun render(script: Script): String =
        script.steps.joinToString("\n") { renderStep(it) }

    fun renderStep(step: Step): String = when (step) {
        is Step.Launch -> """启动 包名 "${step.packageName}""""
        is Step.Click -> "${if (step.longPress) "长按" else "点击"} ${sel(step.selectorType)} ${q(step.value)}"
        is Step.Input -> "输入 ${sel(step.selectorType)} ${q(step.locator)} 文本 ${q(step.text)}"
        is Step.Clear -> "清除 ${sel(step.selectorType)} ${q(step.value)}"
        is Step.Wait -> when {
            step.anyPopup -> "等待 任意弹窗" + optTimeout(step.timeoutSec)
            else -> "等待 ${sel(step.selectorType ?: SelectorType.TEXT)} ${q(step.value ?: "")}" + optTimeout(step.timeoutSec)
        }
        is Step.Assert -> "断言 ${sel(step.selectorType)} ${q(step.value)}"
        is Step.Swipe -> "滑动 " + when (step.dir) {
            SwipeDir.UP -> "上"; SwipeDir.DOWN -> "下"; SwipeDir.LEFT -> "左"; SwipeDir.RIGHT -> "右"
        }
        is Step.Back -> "返回"
        is Step.Home -> "回桌面"
        is Step.Fallback -> {
            val cond = when (val c = step.condition) {
                is FallbackCondition.Text -> "文本 ${q(c.value)}"
                FallbackCondition.AnyPopup -> "任意弹窗"
            }
            val act = when (step.action) {
                FallbackAction.SKIP -> "跳过"
                FallbackAction.CLOSE_POPUP_AND_RETRY -> "关闭弹窗再重试"
                FallbackAction.FAIL -> "失败"
            }
            "兜底 $cond 则 $act"
        }
    }

    private fun sel(t: SelectorType) = when (t) {
        SelectorType.ID -> "id"; SelectorType.TEXT -> "文本"; SelectorType.DESC -> "描述"
    }

    private fun q(s: String) = "\"${s.replace("\"", "\\\"")}\""

    private fun optTimeout(sec: Int) = if (sec == 10) "" else " 超时 $sec"
}
