package com.yingfing.stepscript.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yingfing.stepscript.core.Parser
import com.yingfing.stepscript.core.ScriptParseException

/** StepScript 语法高亮：关键字染色 + 行内解析错误标红。 */
class StepScriptHighlight : VisualTransformation {
    private val keywords = listOf(
        "启动", "包名", "点击", "长按", "输入", "文本", "清除", "等待", "任意弹窗",
        "断言", "滑动", "上", "下", "左", "右", "返回", "回桌面", "兜底", "则",
        "跳过", "关闭弹窗再重试", "失败", "超时", "id", "描述",
    )

    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val out = buildAnnotatedString {
            append(s)
            for (kw in keywords) {
                var from = 0
                while (true) {
                    val i = s.indexOf(kw, from)
                    if (i == -1) break
                    addStyle(SpanStyle(color = Color(0xFF1B6EF3)), i, i + kw.length)
                    from = i + kw.length
                }
            }
            s.lineSequence().forEachIndexed { idx, line ->
                val lineStart = s.lines().take(idx).sumOf { it.length + 1 }
                if (line.trimStart().startsWith("#")) {
                    addStyle(SpanStyle(color = Color(0xFF888888)), lineStart, lineStart + line.length)
                }
            }
        }
        return TransformedText(out, OffsetMapping.Identity)
    }
}

/** 语法错误提示条：解析当前文本，返回 null 表示通过。 */
fun tryParse(source: String): ScriptParseException? =
    try { Parser.parse(source); null } catch (e: ScriptParseException) { e }
