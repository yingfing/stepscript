package com.yingfing.stepscript.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku 封装：目前只用到一件事——失败善后时 force-stop 目标应用
 * （比"按返回/回桌面"干净，避免目标 App 留在后台继续弹东西）。
 * Shizuku 未连接时优雅降级为回桌面。
 */
object ShizukuSupport {

    fun isAvailable(): Boolean =
        runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    /** 尽力 force-stop；返回实际使用的善后方式描述（给失败记录看）。 */
    fun forceStop(packageName: String): String {
        if (isAvailable()) {
            val exit = runCatching {
                val process = Shizuku.newProcessBuilder()
                    .addCommand("am", "force-stop", packageName)
                    .start()
                process.waitFor()
            }.getOrDefault(-1)
            if (exit == 0) return "已通过 Shizuku 强制停止 $packageName"
        }
        // 降级：回桌面 + 把目标应用退到后台
        A11yBridge.goHome()
        return "Shizuku 不可用，已回桌面并结束前台（可在系统设置里授权 Shizuku 获得更干净的退出）"
    }
}

/** 避免包循环依赖的桥：把无障碍的全局动作暴露给 Shizuku 层。 */
object A11yBridge {
    fun goHome() = com.yingfing.stepscript.access.A11y.service?.goHome()
}
