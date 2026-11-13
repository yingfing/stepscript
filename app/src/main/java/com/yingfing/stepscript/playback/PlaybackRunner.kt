package com.yingfing.stepscript.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.yingfing.stepscript.R
import com.yingfing.stepscript.StepScriptApp
import com.yingfing.stepscript.access.Screenshot
import com.yingfing.stepscript.core.Parser
import com.yingfing.stepscript.core.Renderer
import com.yingfing.stepscript.core.ScriptParseException
import com.yingfing.stepscript.data.FailureEntity
import com.yingfing.stepscript.data.ScriptEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 统一的脚本执行入口：列表页 / MCP / 失败详情重跑验证，都从这里走。
 * 配真实截图通道 + 失败留证 + 系统通知。
 */
object PlaybackRunner {

    data class Result(
        val ok: Boolean,
        val message: String,
        val failureId: Long? = null,
    )

    /** 同一时间只允许一个脚本在跑，避免无障碍手势打架。 */
    private val mutex = Mutex()

    suspend fun run(script: ScriptEntity, runBy: String = "手动"): Result = mutex.withLock {
        val app = StepScriptApp.instance

        val parsed = try {
            Parser.parse(script.source)
        } catch (e: ScriptParseException) {
            return Result(false, "脚本「${script.name}」语法有误：${e.message}")
        }

        var lastFailureId: Long? = null
        val engine = PlaybackEngine(
            screenshot = { Screenshot.captureAndSave() },
            notify = { title, text -> notify(app, "${title} · ${script.name}", text) },
            onRecordFailure = { ctx ->
                lastFailureId = app.db.failures.insert(
                    FailureEntity(
                        scriptId = script.id, scriptName = script.name,
                        stepIndex = ctx.stepIndex,
                        stepText = Renderer.renderStep(ctx.step),
                        reason = "${if (runBy != "手动") "[$runBy] " else ""}${ctx.reason}",
                        screenshotPath = ctx.screenshotPath,
                        layoutText = ctx.layoutText, layoutXml = ctx.layoutXml,
                    )
                )
            },
        )
        val ok = engine.run(parsed) { }
        return if (ok) {
            Result(true, "✅ 「${script.name}」执行完成")
        } else {
            Result(false, "❌ 「${script.name}」执行失败，已截图留证（失败记录 #${lastFailureId}）", lastFailureId)
        }
    }

    private fun notify(context: Context, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("playback", "脚本执行", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val n = NotificationCompat.Builder(context, "playback")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(scriptNotificationId(), n) }
    }

    private var seq = 0
    private fun scriptNotificationId() = (++seq) and 0x0FFFFFFF or (R.string.app_name.hashCode() and 0x70000000)
}
