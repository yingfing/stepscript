package com.yingfing.stepscript.core

/**
 * StepScript v1 脚本数据模型。
 *
 * 设计原则：语言只是外壳，一切语句最终都落到这里的结构化步骤（Step）上，
 * 便于回放引擎校验、UI 图形化编辑、以及 AI 生成补丁后的严格校验。
 */

/** 控件定位方式。回放时按 id → 文本 → 描述 的固定优先级在界面中查找。 */
enum class SelectorType { ID, TEXT, DESC }

/** 兜底（异常分支）的触发条件。 */
sealed class FallbackCondition {
    /** 界面上出现指定文本时触发。 */
    data class Text(val value: String) : FallbackCondition()
    /** 出现任意脚本未预期的弹窗时触发。 */
    object AnyPopup : FallbackCondition()
}

/** 兜底的处置方式。 */
enum class FallbackAction { SKIP, CLOSE_POPUP_AND_RETRY, FAIL }

/** 滑动方向。 */
enum class SwipeDir { UP, DOWN, LEFT, RIGHT }

/** 一条脚本步骤。 */
sealed class Step {
    abstract val line: Int

    /** 启动 包名 "..." —— 打开应用并等待其进入前台。 */
    data class Launch(override val line: Int, val packageName: String) : Step()

    /** 点击 / 长按 —— [longPress] 为 true 表示长按。 */
    data class Click(
        override val line: Int,
        val selectorType: SelectorType,
        val value: String,
        val longPress: Boolean = false,
    ) : Step()

    /** 输入 —— 向输入框写入文本。 */
    data class Input(
        override val line: Int,
        val selectorType: SelectorType,
        val locator: String,
        val text: String,
    ) : Step()

    /** 清除 —— 清空输入框。 */
    data class Clear(override val line: Int, val selectorType: SelectorType, val value: String) : Step()

    /**
     * 等待 —— 等元素出现，或 [anyPopup] 为 true 时等任意弹窗出现。
     * [timeoutSec] 缺省 10 秒。
     */
    data class Wait(
        override val line: Int,
        val selectorType: SelectorType? = null,
        val value: String? = null,
        val anyPopup: Boolean = false,
        val timeoutSec: Int = 10,
    ) : Step()

    /** 断言 —— 元素必须存在，否则立即判定脚本失败。 */
    data class Assert(override val line: Int, val selectorType: SelectorType, val value: String) : Step()

    /** 滑动 上|下|左|右 —— 整屏滑动。 */
    data class Swipe(override val line: Int, val dir: SwipeDir) : Step()

    /** 返回 —— 按系统返回键。 */
    data class Back(override val line: Int) : Step()

    /** 回桌面 —— 按 Home 键。 */
    data class Home(override val line: Int) : Step()

    /**
     * 兜底 —— 全局异常分支声明，写在脚本任意位置、全局生效。
     * 形如：兜底 文本 "已签到" 则 跳过 / 兜底 任意弹窗 则 关闭弹窗再重试
     */
    data class Fallback(
        override val line: Int,
        val condition: FallbackCondition,
        val action: FallbackAction,
    ) : Step()
}

/** 一份完整的脚本：有序步骤 + 全局兜底规则。 */
data class Script(
    val steps: List<Step>,
    val fallbacks: List<Step.Fallback>,
) {
    companion object {
        val empty = Script(emptyList(), emptyList())
    }
}
