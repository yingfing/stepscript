package com.yingfing.stepscript.access

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.yingfing.stepscript.StepScriptApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * 截图：走无障碍服务的 takeScreenshot（API 30+，无需录屏授权）。
 * 结果存到 filesDir/failures/<时间戳>.png，返回绝对路径；失败/不支持返回 null。
 */
object Screenshot {

    suspend fun captureAndSave(): String? = withContext(Dispatchers.IO) {
        val svc = A11y.service ?: return@withContext null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext null

        val result = suspendCancellableCoroutine<AccessibilityService.ScreenshotResult?> { cont ->
            runCatching {
                svc.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    android.content.Context.MAIN_EXECUTOR,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onWillTakeScreenshot() {}
                        override fun onScreenshotTaken(r: AccessibilityService.ScreenshotResult?) {
                            cont.resume(r)
                        }
                    },
                )
            }.onFailure { cont.resume(null) }
        } ?: return@withContext null

        val buffer = result.hardwareBuffer ?: return@withContext null
        val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
            ?.copy(Bitmap.Config.ARGB_8888, false)
        buffer.close()
        if (bitmap == null) return@withContext null

        return@withContext runCatching {
            val dir = File(StepScriptApp.instance.filesDir, "failures").apply { mkdirs() }
            val f = File(dir, "${System.currentTimeMillis()}.png")
            FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            f.absolutePath
        }.getOrNull()
    }
}
