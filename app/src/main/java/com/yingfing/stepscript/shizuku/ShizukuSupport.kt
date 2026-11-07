package com.yingfing.stepscript.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku 集成（v1 仅探测可用性；force-stop 走 UserService，v1.1 实现）。
 * 当前善后策略：回桌面 + 返回，把目标 App 退到后台。
 */
object ShizukuSupport {

    fun isAvailable(): Boolean =
        runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    /** 善后：返回实际使用的退场方式描述（写进失败记录）。 */
    fun cleanup(): String {
        val svc = com.yingfing.stepscript.access.A11y.service
        svc?.goBack()
        svc?.goHome()
        return "已静默退场（回桌面）" + if (isAvailable()) "；Shizuku 已连接，force-stop 将在 v1.1 接入" else ""
    }
}
