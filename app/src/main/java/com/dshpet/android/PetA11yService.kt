package com.dshpet.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * 只为"锁屏上也能看到她"而存在的无障碍服务：**不读屏幕内容、不监听输入**，
 * 仅仅是拿一个能创建 `TYPE_ACCESSIBILITY_OVERLAY` 窗口的身份。
 *
 * 用户不开它，App 其它功能完全不受影响（只是锁屏时看不到桌宠）。
 */
class PetA11yService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        LockOverlay.attach(this)
        Diag.record(this, "无障碍已连接：桌宠可以在锁屏上显示")
        // 重建窗口由 LockOverlay.onChange（PetService 里挂的）负责，这里不重复调
    }

    override fun onUnbind(intent: Intent?): Boolean {
        LockOverlay.detach()
        Diag.record(this, "无障碍已断开：锁屏不再显示")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        LockOverlay.detach()
        super.onDestroy()
    }

    /** 事件我们一概不看，直接忽略 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
