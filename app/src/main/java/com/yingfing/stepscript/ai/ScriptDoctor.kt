package com.yingfing.stepscript.ai

import com.yingfing.stepscript.core.Diff
import com.yingfing.stepscript.core.Parser
import com.yingfing.stepscript.core.ScriptParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 脚本医生：失败现场 → 修改后的完整脚本 → diff 预览 → 用户确认后打补丁。
 *
 * 设计红线（用户定的）：
 *  - AI 不操作手机，只产出脚本补丁；
 *  - 产出必须通过 core.Parser 严格校验，语法不过关直接打回；
 *  - 补丁必须用户确认才生效。
 */
class ScriptDoctor(
    private val baseUrl: String,   // OpenAI 兼容接口，如 https://api.xx.com/v1
    private val apiKey: String,
    private val model: String,
) {

    data class Patch(val newSource: String, val diff: List<Diff.Line>, val explanation: String)

    suspend fun diagnose(
        oldSource: String,
        failedStep: String,
        failureReason: String,
        layoutText: String,
        screenshotNote: String = "",
    ): Patch = withContext(Dispatchers.IO) {
        val sys = """
            你是「脚本医生」，负责修复一个安卓自动化脚本的失败步骤。
            脚本语言是 StepScript v1，语法固定如下（只允许这些语句，一行一句）：
            启动 包名 "..." | 点击 (id|文本|描述) "..." | 长按 (id|文本|描述) "..."
            输入 (id|文本) "定位串" 文本 "..." | 清除 (id|文本) "..."
            等待 (id|文本) "..." 超时 n 或 等待 任意弹窗 超时 n | 断言 (id|文本|描述) "..."
            滑动 上|下|左|右 | 返回 | 回桌面
            兜底 (文本 "..."|任意弹窗) 则 (跳过|关闭弹窗再重试|失败)
            规则：
            1. 只依据给出的布局快照修改，不要臆造不存在的 id 或文本；
            2. 定位方式优先 id，其次文本，不要用坐标；
            3. 输出三段：第一行给一句中文修复说明；然后一行只含 ===；然后给出修复后的完整脚本（不要代码块标记）。
        """.trimIndent()

        val user = """
            【失败步骤】$failedStep
            【失败原因】$failureReason
            $screenshotNote
            【当前脚本】
            $oldSource
            【失败时界面布局快照】
            $layoutText
        """.trimIndent()

        val reply = chat(sys, user)

        // 解析 AI 回复：说明 === 脚本
        val parts = reply.split("===", limit = 2)
        val explanation = parts.getOrNull(0)?.trim().orEmpty()
        val newSource = parts.getOrNull(1)?.trim()
            ?: throw IllegalStateException("AI 未返回修复脚本，请重试或换模型")
        // 严格校验：语法不过关直接打回
        try {
            Parser.parse(newSource)
        } catch (e: ScriptParseException) {
            throw IllegalStateException("AI 返回的脚本语法不合法（${e.message}），已丢弃", e)
        }
        Patch(newSource, Diff.diff(oldSource, newSource), explanation)
    }

    /** OpenAI 兼容 chat/completions 调用（不引第三方 SDK，HTTP 直连）。 */
    private fun chat(system: String, user: String): String {
        val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
        }
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw IllegalStateException("模型接口返回 $code：${text.take(300)}")
        return JSONObject(text)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }
}
