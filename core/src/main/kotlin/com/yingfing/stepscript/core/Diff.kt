package com.yingfing.stepscript.core

/**
 * 极简行级 diff（LCS），专门给「AI 脚本医生」的补丁预览用。
 * 输出统一为 [- 旧行] [+ 新行] [  未变行] 三种前缀的行序列。
 */
object Diff {

    data class Line(val prefix: String, val text: String) {
        val isChange: Boolean get() = prefix != " "
    }

    fun diff(oldText: String, newText: String): List<Line> {
        val a = oldText.lines()
        val b = newText.lines()
        val n = a.size
        val m = b.size
        // LCS 动态规划表
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val out = mutableListOf<Line>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += Line(" ", a[i]); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out += Line("-", a[i]); i++ }
                else -> { out += Line("+", b[j]); j++ }
            }
        }
        while (i < n) { out += Line("-", a[i]); i++ }
        while (j < m) { out += Line("+", b[j]); j++ }
        return out
    }
}
