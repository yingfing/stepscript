package com.yingfing.stepscript.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParserTest {

    // ---------- 正常解析 ----------

    @Test
    fun `完整脚本解析成功`() {
        val src = """
            # 自动签到示例
            启动 包名 "com.example.app"
            等待 文本 "我的" 超时 10
            点击 文本 "签到"
            等待 文本 "签到成功" 超时 5
            断言 文本 "金币+1"
            兜底 文本 "已签到" 则 跳过
            兜底 任意弹窗 则 关闭弹窗再重试
            滑动 下
            点击 id "btn_next"
            长按 描述 "更多选项"
            输入 id "et_search" 文本 "你好世界"
            清除 文本 "搜索框"
            返回
            回桌面
        """.trimIndent()

        val s = Parser.parse(src)
        assertEquals(14, s.steps.size)
        assertEquals(2, s.fallbacks.size)
        assertTrue(s.steps[0] is Step.Launch)
        assertEquals("com.example.app", (s.steps[0] as Step.Launch).packageName)
        assertTrue(s.steps[9] is Step.Click && (s.steps[9] as Step.Click).longPress)
        assertEquals("你好世界", (s.steps[10] as Step.Input).text)
        assertEquals(SwipeDir.DOWN, (s.steps[7] as Step.Swipe).dir)
    }

    @Test
    fun `中文引号与英文引号都能解析`() {
        val a = Parser.parse("点击 文本 \"签到\"").steps.single() as Step.Click
        val b = Parser.parse("点击 文本 “签到”").steps.single() as Step.Click
        val c = Parser.parse("点击 文本 「签到」").steps.single() as Step.Click
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun `等待的超时可省略且默认10秒`() {
        val w = Parser.parse("等待 任意弹窗").steps.single() as Step.Wait
        assertTrue(w.anyPopup)
        assertEquals(10, w.timeoutSec)
        val w2 = Parser.parse("等待 任意弹窗 超时 3").steps.single() as Step.Wait
        assertEquals(3, w2.timeoutSec)
        val w3 = Parser.parse("等待 id \"btn\" 超时 2").steps.single() as Step.Wait
        assertEquals(SelectorType.ID, w3.selectorType)
        assertEquals("btn", w3.value)
    }

    @Test
    fun `行号被正确记录`() {
        val s = Parser.parse("# 注释\n\n返回")
        assertEquals(3, s.steps.single().line)
    }

    // ---------- 错误处理 ----------

    @Test
    fun `未知语句报中文错误并带行号`() {
        val e = assertFailsWith<ScriptParseException> { Parser.parse("启动 包名 \"a\"\n乱写一点东西") }
        assertEquals(2, e.line)
        assertTrue(e.message!!.contains("无法识别的语句"))
    }

    @Test
    fun `启动缺少包名关键字报错`() {
        val e = assertFailsWith<ScriptParseException> { Parser.parse("启动 \"com.a.b\"") }
        assertTrue(e.message!!.contains("包名"))
        assertEquals(1, e.line)
    }

    @Test
    fun `定位方式不合法报错`() {
        val e = assertFailsWith<ScriptParseException> { Parser.parse("点击 坐标 100,200") }
        assertTrue(e.message!!.contains("id / 文本 / 描述"))
    }

    @Test
    fun `引号未闭合报错`() {
        val e = assertFailsWith<ScriptParseException> { Parser.parse("点击 文本 \"签到") }
        assertTrue(e.message!!.contains("未闭合"))
    }

    @Test
    fun `输入缺少文本关键字报错`() {
        val e = assertFailsWith<ScriptParseException> { Parser.parse("输入 id \"et\" \"内容\"") }
        assertTrue(e.message!!.contains("文本"))
    }

    @Test
    fun `兜底的处置不合法报错`() {
        val e = assertFailsWith<ScriptParseException> { Parser.parse("兜底 任意弹窗 则 自动处理") }
        assertTrue(e.message!!.contains("跳过"))
    }

    @Test
    fun `滑动方向不合法报错`() {
        assertFailsWith<ScriptParseException> { Parser.parse("滑动 斜着") }
    }

    @Test
    fun `返回不接参数`() {
        assertFailsWith<ScriptParseException> { Parser.parse("返回 超时 3") }
    }

    // ---------- 渲染往返 & Diff ----------

    @Test
    fun `解析后再渲染再解析结果一致`() {
        val src = """
            启动 包名 "com.example.app"
            等待 文本 "我的" 超时 8
            点击 文本 "签到"
            长按 描述 "更多"
            输入 id "et" 文本 "abc"
            清除 文本 "搜索"
            等待 任意弹窗 超时 3
            断言 文本 "成功"
            滑动 上
            返回
            回桌面
            兜底 文本 "已签到" 则 跳过
            兜底 任意弹窗 则 关闭弹窗再重试
        """.trimIndent()
        val parsed = Parser.parse(src)
        val rendered = Renderer.render(parsed)
        val reparsed = Parser.parse(rendered)
        assertEquals(parsed, reparsed)
    }

    @Test
    fun `diff 能标出增删行`() {
        val old = "返回\n点击 文本 \"A\"\n回桌面"
        val new = "返回\n点击 id \"btn_a\"\n回桌面\n滑动 下"
        val d = Diff.diff(old, new)
        assertTrue(d.any { it.prefix == "-" && it.text == "点击 文本 \"A\"" })
        assertTrue(d.any { it.prefix == "+" && it.text == "点击 id \"btn_a\"" })
        assertTrue(d.any { it.prefix == "+" && it.text == "滑动 下" })
        assertEquals(2, d.count { it.prefix == " " })
    }
}
