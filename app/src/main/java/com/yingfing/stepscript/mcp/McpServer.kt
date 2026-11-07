package com.yingfing.stepscript.mcp

import fi.iki.elonen.NanoHTTPD
import com.yingfing.stepscript.StepScriptApp
import com.yingfing.stepscript.access.A11y
import com.yingfing.stepscript.access.NodeDumper
import com.yingfing.stepscript.access.NodeFinder
import com.yingfing.stepscript.ai.ScriptDoctor
import com.yingfing.stepscript.core.Parser
import com.yingfing.stepscript.core.Renderer
import com.yingfing.stepscript.core.SelectorType
import com.yingfing.stepscript.core.ScriptParseException
import com.yingfing.stepscript.data.FailureEntity
import com.yingfing.stepscript.data.ScriptEntity
import com.yingfing.stepscript.playback.PlaybackEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 内置 MCP Server（Streamable HTTP / JSON-RPC 2.0），监听 127.0.0.1。
 *
 * 电脑侧接入：`adb reverse tcp:8765 tcp:8765`，然后 MCP 客户端连接 http://127.0.0.1:8765/mcp
 * 暴露的工具覆盖本 App 用户能做的一切：管理/编写/运行脚本、读取布局、直接操作控件、
 * 查看失败现场、调 AI 脚本医生出补丁（只出补丁不落库，落库必须写脚本工具显式改）。
 */
class McpServer(private val port: Int = DEFAULT_PORT) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.POST) return jsonRpc(null, -32600, "仅支持 POST")

        val body = session.inputStream.readBytes().toString(Charsets.UTF_8)
        val req = try { JSONObject(body) } catch (e: Exception) {
            return jsonRpc(null, -32700, "JSON 解析失败")
        }
        val id = req.opt("id")
        val method = req.optString("method")
        val params = req.optJSONObject("params") ?: JSONObject()

        val result: JSONObject? = when (method) {
            "initialize" -> JSONObject().apply {
                put("protocolVersion", "2024-11-05")
                put("capabilities", JSONObject().put("tools", JSONObject()))
                put("serverInfo", JSONObject().put("name", "stepscript").put("version", "0.1.0"))
            }
            "notifications/initialized", "initialized" -> return newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", tools)
            "tools/call" -> try {
                callTool(params.optString("name"), params.optJSONObject("arguments") ?: JSONObject())
            } catch (e: Exception) {
                toolError(e.message ?: e.toString())
            }
            else -> return jsonRpc(id, -32601, "未知方法 $method")
        }
        return if (result == null) jsonRpc(id, -32600, "空结果")
        else jsonRpc(id, null, null, result)
    }

    // ---------- 工具定义 ----------

    private val tools: JSONArray get() = JSONArray().apply {
        fun tool(name: String, desc: String, props: JSONObject = JSONObject(), required: JSONArray = JSONArray()) =
            put(JSONObject().apply {
                put("name", name); put("description", desc)
                put("inputSchema", JSONObject().apply {
                    put("type", "object"); put("properties", props); put("required", required)
                })
            })

        // —— 脚本管理 ——
        tool("list_scripts", "列出所有 StepScript 脚本（id/名称/启用状态/源码）")
        tool("get_script", "读取指定脚本（含语法校验结果）", JSONObject()
            .put("scriptId", JSONObject().put("type", "integer").put("description", "脚本 id")), JSONArray().put("scriptId"))
        tool("write_script", "创建或更新脚本。source 必须是合法 StepScript v1 源码，写入前强制语法校验，不合法会返回中文报错",
            JSONObject()
                .put("scriptId", JSONObject().put("type", "integer").put("description", "更新已有脚本时填；新建则省略"))
                .put("name", JSONObject().put("type", "string").put("description", "脚本名（新建必填）"))
                .put("source", JSONObject().put("type", "string").put("description", "StepScript 源码")),
            JSONArray().put("source"))
        tool("run_script", "回放执行指定脚本（同步执行，四重校验，失败自动留证）",
            JSONObject().put("scriptId", JSONObject().put("type", "integer")), JSONArray().put("scriptId"))

        // —— 界面读取 / 操作 ——
        tool("get_layout", "读取当前前台应用的界面布局（压缩文本 + XML 双格式）")
        tool("tap_element", "点击当前界面上的控件", JSONObject()
            .put("selectorType", JSONObject().put("type", "string").put("enum", JSONArray().put("id").put("text").put("desc")))
            .put("value", JSONObject().put("type", "string")),
            JSONArray().put("selectorType").put("value"))
        tool("input_text", "向输入框写入文本", JSONObject()
            .put("selectorType", JSONObject().put("type", "string").put("enum", JSONArray().put("id").put("text")))
            .put("locator", JSONObject().put("type", "string"))
            .put("text", JSONObject().put("type", "string")),
            JSONArray().put("selectorType").put("locator").put("text"))
        tool("press", "按系统键", JSONObject().put("key", JSONObject().put("type", "string").put("enum", JSONArray().put("back").put("home"))),
            JSONArray().put("key"))
        tool("swipe", "整屏滑动", JSONObject().put("direction", JSONObject().put("type", "string").put("enum", JSONArray().put("上").put("下").put("左").put("右"))),
            JSONArray().put("direction"))

        // —— 失败处理 & AI ——
        tool("list_failures", "列出未处理的脚本失败记录")
        tool("get_failure", "读取失败现场（出错步骤/原因/布局快照/截图路径）",
            JSONObject().put("failureId", JSONObject().put("type", "integer")), JSONArray().put("failureId"))
        tool("ai_fix_failure", "让 AI 脚本医生基于失败现场生成脚本补丁（返回 diff 与完整新源码，不自动落库；落库请用 write_script）",
            JSONObject().put("failureId", JSONObject().put("type", "integer")), JSONArray().put("failureId"))
    }

    // ---------- 工具执行 ----------

    private fun callTool(name: String, args: JSONObject): JSONObject {
        val app = StepScriptApp.instance
        val text: String = when (name) {
            "list_scripts" -> runBlocking {
                val list = StepScriptApp.db.scripts.observeAll().first()
                if (list.isEmpty()) "（还没有脚本）"
                else list.joinToString("\n") { "[${it.id}] ${it.name} ${if (it.enabled) "✓" else "✗"}\n${it.source}\n" }
            }

            "get_script" -> runBlocking {
                val s = app.db.scripts.getById(args.getLong("scriptId"))
                    ?: return@runBlocking "找不到脚本 ${args.opt("scriptId")}"
                val err = try { Parser.parse(s.source); null } catch (e: ScriptParseException) { e.message }
                "[${s.id}] ${s.name}\n${s.source}\n语法检查：${err ?: "通过"}"
            }

            "write_script" -> runBlocking {
                val source = args.getString("source")
                // 强制语法校验：AI 写不合法脚本直接打回
                try { Parser.parse(source) } catch (e: ScriptParseException) {
                    return@runBlocking "❌ 语法不合法，未保存：${e.message}\n可用语句：启动/点击/长按/输入/清除/等待/断言/滑动/返回/回桌面/兜底"
                }
                val id = args.optLong("scriptId", -1L)
                if (id > 0) {
                    val s = app.db.scripts.getById(id) ?: return@runBlocking "找不到脚本 $id"
                    app.db.scripts.updateSource(id, source)
                    app.db.versions.insert(com.yingfing.stepscript.data.ScriptVersionEntity(
                        scriptId = id, source = source, comment = "MCP 修改"))
                    "✅ 已更新脚本 [$id] ${s.name}"
                } else {
                    val newId = app.db.scripts.insert(ScriptEntity(
                        name = args.optString("name", "AI 脚本"), source = source))
                    "✅ 已创建脚本 [$newId]"
                }
            }

            "run_script" -> runBlocking {
                val s = app.db.scripts.getById(args.getLong("scriptId"))
                    ?: return@runBlocking "找不到脚本 ${args.opt("scriptId")}"
                val script = try { Parser.parse(s.source) } catch (e: ScriptParseException) {
                    return@runBlocking "脚本语法有误：${e.message}"
                }
                val engine = PlaybackEngine(
                    screenshot = { null },
                    notify = { _, _ -> },
                    onRecordFailure = { ctx ->
                        app.db.failures.insert(FailureEntity(
                            scriptId = s.id, scriptName = s.name, stepIndex = ctx.stepIndex,
                            stepText = Renderer.renderStep(ctx.step), reason = ctx.reason,
                            screenshotPath = ctx.screenshotPath,
                            layoutText = ctx.layoutText, layoutXml = ctx.layoutXml))
                    },
                )
                val ok = engine.run(script) { }
                if (ok) "✅ 「${s.name}」执行完成"
                else "❌ 执行失败，已留证（用 list_failures / get_failure 查看现场）"
            }

            "get_layout" -> {
                val root = A11y.root()
                "【压缩文本】\n${NodeDumper.toText(root)}\n\n【XML】\n${NodeDumper.toXml(root)}"
            }

            "tap_element" -> {
                val type = parseSelector(args.getString("selectorType"))
                val node = NodeFinder.find(type, args.getString("value"))
                    ?: return toolText("找不到控件：${args.getString("value")}")
                if (A11y.service?.click(node) == true) "✅ 已点击 ${args.getString("value")}"
                else "点击失败"
            }

            "input_text" -> {
                val node = NodeFinder.find(parseSelector(args.getString("selectorType")), args.getString("locator"))
                    ?: return toolText("找不到输入框：${args.getString("locator")}")
                node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                    android.os.Bundle().apply {
                        putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            args.getString("text"))
                    })
                "✅ 已输入"
            }

            "press" -> {
                if (args.getString("key") == "back") A11y.service?.goBack() else A11y.service?.goHome()
                "✅ 已按 ${args.getString("key")}"
            }

            "swipe" -> {
                A11y.service?.swipe(args.getString("direction"))
                "✅ 已滑动"
            }

            "list_failures" -> runBlocking {
                val list = snapshotFailures()
                if (list.isEmpty()) "（没有待处理的失败记录）"
                else list.joinToString("\n") { "[${it.id}] ${it.scriptName} 第${it.stepIndex + 1}步：${it.reason}" }
            }

            "get_failure" -> runBlocking {
                val f = app.db.failures.getById(args.getLong("failureId"))
                    ?: return@runBlocking "找不到失败记录"
                "脚本：${f.scriptName}\n出错语句（第${f.stepIndex + 1}步）：${f.stepText}\n原因：${f.reason}\n" +
                    "截图：${f.screenshotPath ?: "无"}\n【布局快照】\n${f.layoutText.take(3000)}"
            }

            "ai_fix_failure" -> runBlocking {
                val f = app.db.failures.getById(args.getLong("failureId"))
                    ?: return@runBlocking "找不到失败记录"
                val script = app.db.scripts.getById(f.scriptId)
                    ?: return@runBlocking "原脚本已被删除"
                val prefs = StepScriptApp.prefs
                val doctor = ScriptDoctor(
                    baseUrl = prefs.getString("baseUrl", "") ?: "",
                    apiKey = prefs.getString("apiKey", "") ?: "",
                    model = prefs.getString("model", "") ?: "",
                )
                if (doctor.baseUrl.isBlank()) return@runBlocking "未配置 AI 接口，请先在 App 设置里填写"
                val patch = doctor.diagnose(script.source, f.stepText, f.reason, f.layoutText)
                buildString {
                    appendLine("【AI 修复说明】${patch.explanation}")
                    appendLine("【diff】（- 删除 + 新增）")
                    patch.diff.forEach { appendLine("${it.prefix} ${it.text}") }
                    appendLine("【新脚本全文】")
                    appendLine(patch.newSource)
                    appendLine("确认无误后，请用 write_script(scriptId=${f.scriptId}, source=新全文) 落库。")
                }
            }

            else -> return toolError("未知工具 $name")
        }
        return toolText(text)
    }

    // ---------- 小工具 ----------

    private suspend fun snapshotScripts(): List<ScriptEntity> =
        StepScriptApp.db.scripts.observeAll().first()

    private suspend fun snapshotFailures(): List<FailureEntity> =
        StepScriptApp.db.failures.observePending().first()

    private fun parseSelector(s: String) = when (s) {
        "id" -> SelectorType.ID; "desc" -> SelectorType.DESC; else -> SelectorType.TEXT
    }

    private fun toolText(text: String): JSONObject = JSONObject().apply {
        put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        put("isError", false)
    }

    private fun toolError(msg: String): JSONObject = JSONObject().apply {
        put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "❌ $msg")))
        put("isError", true)
    }

    private fun jsonRpc(id: Any?, errCode: Int?, errMsg: String?, result: JSONObject? = null): Response {
        val resp = JSONObject()
        if (errCode != null) {
            resp.put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
                .put("error", JSONObject().put("code", errCode).put("message", errMsg ?: ""))
        } else {
            resp.put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result ?: JSONObject())
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
    }

    companion object {
        const val DEFAULT_PORT = 8765
    }
}
