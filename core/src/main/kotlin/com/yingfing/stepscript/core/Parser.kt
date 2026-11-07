package com.yingfing.stepscript.core

/**
 * StepScript 解析异常：携带行号与中文错误信息，直接展示给用户/AI。
 */
class ScriptParseException(val line: Int, message: String) : Exception("第 $line 行：$message")

/**
 * StepScript v1 解析器。
 *
 * 语法（一页纸规范）：
 *   #            注释，整行有效
 *   启动 包名 "..."                              打开应用并等待到前台
 *   点击 (id|文本|描述) "..."                     找到控件并点击
 *   长按 (id|文本|描述) "..."                     找到控件并长按
 *   输入 (id|文本) "定位串" 文本 "..."            向输入框写入文本
 *   清除 (id|文本) "..."                         清空输入框
 *   等待 (id|文本) "..." [超时 n] / 等待 任意弹窗 [超时 n]
 *   断言 (id|文本) "..."                          不存在则立即失败
 *   滑动 上|下|左|右
 *   返回
 *   回桌面
 *   兜底 (文本 "..."|任意弹窗) 则 (跳过|关闭弹窗再重试|失败)
 *
 * 容错规则：
 *   - 英文引号 "..." 与中文引号 “...” 均可
 *   - 整行注释以 # 开头（允许前导空白）
 *   - 多余空白忽略；缩进仅用于阅读
 *   - 关键字后参数不足 / 出现多余 token 均报中文错误并带行号
 */
object Parser {

    private val QUOTE_PAIRS = mapOf('"' to '"', '“' to '”', '「' to '」', '『' to '』')

    /** 把脚本源码解析为 [Script]；语法不合法抛 [ScriptParseException]。 */
    fun parse(source: String): Script {
        val steps = mutableListOf<Step>()
        val fallbacks = mutableListOf<Step.Fallback>()

        source.lines().forEachIndexed { idx, raw ->
            val line = idx + 1
            val text = raw.trim()
            if (text.isEmpty() || text.startsWith("#")) return@forEachIndexed

            val tokens = tokenize(text, line)
            when (val kw = tokens.first()) {
                "启动" -> steps += parseLaunch(tokens, line)
                "点击" -> steps += parseClick(tokens, line, longPress = false)
                "长按" -> steps += parseClick(tokens, line, longPress = true)
                "输入" -> steps += parseInput(tokens, line)
                "清除" -> steps += parseClear(tokens, line)
                "等待" -> steps += parseWait(tokens, line)
                "断言" -> steps += parseAssert(tokens, line)
                "滑动" -> steps += parseSwipe(tokens, line)
                "返回" -> steps += requireNoArgs(tokens, line, "返回") { Step.Back(line) }
                "回桌面" -> steps += requireNoArgs(tokens, line, "回桌面") { Step.Home(line) }
                "兜底" -> {
                    val fb = parseFallback(tokens, line)
                    steps += fb
                    fallbacks += fb
                }
                else -> throw ScriptParseException(
                    line,
                    "无法识别的语句「${kw}」。可用语句：启动/点击/长按/输入/清除/等待/断言/滑动/返回/回桌面/兜底",
                )
            }
        }
        return Script(steps, fallbacks)
    }

    // ---------- 词法 ----------

    /** 把一行文本切成 token：关键字、定位类型、数字、字符串、符号。 */
    internal fun tokenize(text: String, line: Int): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '"' || c == '“' || c == '「' || c == '『' -> {
                    val close = QUOTE_PAIRS[c] ?: '"'
                    val end = text.indexOf(close, i + 1)
                    if (end == -1) throw ScriptParseException(line, "字符串引号未闭合：$text")
                    tokens += text.substring(i + 1, end)
                    i = end + 1
                }
                else -> {
                    val start = i
                    while (i < text.length && !text[i].isWhitespace()) i++
                    tokens += text.substring(start, i)
                }
            }
        }
        if (tokens.isEmpty()) throw ScriptParseException(line, "空语句")
        return tokens
    }

    // ---------- 各语句解析 ----------

    private fun parseLaunch(t: List<String>, line: Int): Step.Launch {
        expectSize(t, 3, line, "「启动」的格式：启动 包名 \"com.example.app\"")
        if (t[1] != "包名") throw ScriptParseException(line, "「启动」后应为「包名」，但写成了「${t[1]}」")
        return Step.Launch(line, t[2])
    }

    private fun parseClick(t: List<String>, line: Int, longPress: Boolean): Step.Click {
        val name = if (longPress) "长按" else "点击"
        expectSize(t, 3, line, "「$name」的格式：$name (id|文本|描述) \"…\"")
        val (type, rest) = parseSelector(t, line, name)
        if (rest.isNotEmpty()) throw ScriptParseException(line, "「$name」后面有多余内容「${rest[0]}」")
        return Step.Click(line, type.first, type.second, longPress)
    }

    private fun parseInput(t: List<String>, line: Int): Step.Input {
        expectSize(t, 5, line, "「输入」的格式：输入 (id|文本) \"定位串\" 文本 \"要输入的内容\"")
        val (type, rest) = parseSelector(t, line, "输入")
        if (rest.firstOrNull() != "文本")
            throw ScriptParseException(line, "「输入」定位之后应为「文本」，但写成了「${rest.getOrNull(0) ?: "（缺失）"}」")
        return Step.Input(line, type.first, type.second, rest[1])
    }

    private fun parseClear(t: List<String>, line: Int): Step.Clear {
        expectSize(t, 3, line, "「清除」的格式：清除 (id|文本) \"…\"")
        val (type, rest) = parseSelector(t, line, "清除")
        if (rest.isNotEmpty()) throw ScriptParseException(line, "「清除」后面有多余内容「${rest[0]}」")
        return Step.Clear(line, type.first, type.second)
    }

    private fun parseWait(t: List<String>, line: Int): Step.Wait {
        // 等待 任意弹窗 [超时 n]  |  等待 (id|文本) "..." [超时 n]
        if (t.size >= 2 && t[1] == "任意弹窗") {
            val timeout = parseOptionalTimeout(t, 2, line)
            return Step.Wait(line, anyPopup = true, timeoutSec = timeout)
        }
        expectSize(t, 4, line, "「等待」的格式：等待 (id|文本) \"…\" 超时 n，或 等待 任意弹窗 超时 n")
        val (type, rest) = parseSelector(t, line, "等待")
        val timeout = parseOptionalTimeout(rest, 0, line)
        return Step.Wait(line, type.first, type.second, timeoutSec = timeout)
    }

    private fun parseAssert(t: List<String>, line: Int): Step.Assert {
        expectSize(t, 3, line, "「断言」的格式：断言 (id|文本|描述) \"…\"")
        val (type, rest) = parseSelector(t, line, "断言")
        if (rest.isNotEmpty()) throw ScriptParseException(line, "「断言」后面有多余内容「${rest[0]}」")
        return Step.Assert(line, type.first, type.second)
    }

    private fun parseSwipe(t: List<String>, line: Int): Step.Swipe {
        expectSize(t, 2, line, "「滑动」的格式：滑动 上|下|左|右")
        val dir = when (t[1]) {
            "上" -> SwipeDir.UP
            "下" -> SwipeDir.DOWN
            "左" -> SwipeDir.LEFT
            "右" -> SwipeDir.RIGHT
            else -> throw ScriptParseException(line, "「滑动」的方向只能是 上/下/左/右，但写成了「${t[1]}」")
        }
        if (t.size > 2) throw ScriptParseException(line, "「滑动」后面有多余内容「${t[2]}」")
        return Step.Swipe(line, dir)
    }

    private fun parseFallback(t: List<String>, line: Int): Step.Fallback {
        // 兜底 文本 "..." 则 动作   |   兜底 任意弹窗 则 动作
        val isTextCond = t.getOrNull(1) == "文本"
        expectSize(t, if (isTextCond) 5 else 4, line,
            "「兜底」的格式：兜底 文本 \"…\" 则 跳过|关闭弹窗再重试|失败，或 兜底 任意弹窗 则 …")
        val condition = when {
            t[1] == "文本" -> FallbackCondition.Text(t[2])
            t[1] == "任意弹窗" -> FallbackCondition.AnyPopup
            else -> throw ScriptParseException(line, "「兜底」的条件应为 文本 \"…\" 或 任意弹窗，但写成了「${t[1]}」")
        }
        // 「文本 "..."」占 2 个 token，「任意弹窗」只占 1 个，则/动作的索引随之不同
        val thenIdx = if (isTextCond) 3 else 2
        if (t.getOrNull(thenIdx) != "则") throw ScriptParseException(line, "「兜底」条件之后应为「则」，但写成了「${t.getOrNull(thenIdx) ?: "（缺失）"}」")
        val action = when (t.getOrNull(thenIdx + 1)) {
            "跳过" -> FallbackAction.SKIP
            "关闭弹窗再重试" -> FallbackAction.CLOSE_POPUP_AND_RETRY
            "失败" -> FallbackAction.FAIL
            else -> throw ScriptParseException(
                line,
                "「兜底」的处置只能是 跳过 / 关闭弹窗再重试 / 失败，但写成了「${t.getOrNull(thenIdx + 1) ?: "（缺失）"}」",
            )
        }
        return Step.Fallback(line, condition, action)
    }

    // ---------- 公共小工具 ----------

    /** 解析「id|文本|描述 "..."」并返回 (类型, 值) 与剩余 token。 */
    private fun parseSelector(t: List<String>, line: Int, stmt: String): Pair<Pair<SelectorType, String>, List<String>> {
        val type = when (t.getOrNull(1)) {
            "id" -> SelectorType.ID
            "文本" -> SelectorType.TEXT
            "描述" -> SelectorType.DESC
            else -> throw ScriptParseException(
                line,
                "「$stmt」的定位方式只能是 id / 文本 / 描述，但写成了「${t.getOrNull(1) ?: "（缺失）"}」",
            )
        }
        val value = t.getOrNull(2)
            ?: throw ScriptParseException(line, "「$stmt」的定位方式之后需要一个字符串，例如 \"$stmt 文本 \\\"签到\\\"\"")
        return Pair(type to value, t.drop(3))
    }

    private fun parseOptionalTimeout(tokens: List<String>, from: Int, line: Int): Int {
        if (from >= tokens.size) return 10
        if (tokens[from] != "超时")
            throw ScriptParseException(line, "此处应为「超时 <秒数>」或省略，但写成了「${tokens[from]}」")
        val n = tokens.getOrNull(from + 1)?.toIntOrNull()
            ?: throw ScriptParseException(line, "「超时」之后应为一个整数秒数，但写成了「${tokens.getOrNull(from + 1) ?: "（缺失）"}」")
        if (n <= 0) throw ScriptParseException(line, "「超时」的秒数必须大于 0，但写成了 $n")
        if (tokens.size > from + 2) throw ScriptParseException(line, "「超时 n」后面有多余内容「${tokens[from + 2]}」")
        return n
    }

    private fun expectSize(t: List<String>, n: Int, line: Int, usage: String) {
        if (t.size < n) throw ScriptParseException(line, "$usage（当前参数不足）")
    }

    private inline fun requireNoArgs(t: List<String>, line: Int, name: String, block: () -> Step): Step {
        if (t.size != 1) throw ScriptParseException(line, "「$name」不带任何参数")
        return block()
    }
}
