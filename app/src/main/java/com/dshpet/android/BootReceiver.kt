package com.dshpet.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** 开机 / 应用更新后自动把桌宠放回来（前提是上次是开启状态且仍有悬浮窗权限） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.running(ctx) && Settings.canDrawOverlays(ctx)) {
            try {
                PetService.start(ctx)
            } catch (_: Throwable) {
                // Android 12+ 从后台启动前台服务受限，失败就算了，用户手动点开关即可
            }
        }
    }
}
