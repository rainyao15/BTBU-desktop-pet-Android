package com.dshpet.android

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.WindowManager

/**
 * 「锁屏上也能看到她」的开关与通道。
 *
 * 背景（为什么需要无障碍）：
 *  普通 App 的悬浮窗是 `TYPE_APPLICATION_OVERLAY`，系统在**锁屏（keyguard）显示时会把它们藏起来**，
 *  这是 AOSP 的硬规则，加 FLAG_SHOW_WHEN_LOCKED 也没用（那个 flag 只对 Activity 生效）。
 *
 *  能盖在锁屏上的窗口类型只有系统级的几种，普通 App 唯一够得着的就是
 *  `TYPE_ACCESSIBILITY_OVERLAY`（只能由**无障碍服务**创建）。所以：
 *
 *   · 用户在系统设置里开启本 App 的无障碍服务 → [PetA11yService] 连上 → [attach]
 *   · 桌宠/气泡的窗口就改用无障碍服务的 WindowManager + TYPE_ACCESSIBILITY_OVERLAY
 *   · 关闭无障碍 → [detach]，自动退回普通悬浮窗（锁屏时就看不到，其它一切照旧）
 *
 * 注意：部分国产 ROM（MIUI/ColorOS/EMUI 等）对"锁屏上画东西"还有额外限制，
 * 甚至要求"锁屏通知/悬浮窗"另外授权；这时只能退化成"锁屏时看不到"。
 */
object LockOverlay {

    private var service: AccessibilityService? = null

    /** 无障碍服务连上/断开时，通知外面（PetService 要重建窗口类型） */
    var onChange: (() -> Unit)? = null

    val active: Boolean get() = service != null

    fun attach(s: AccessibilityService) {
        service = s
        onChange?.invoke()
    }

    fun detach() {
        service = null
        onChange?.invoke()
    }

    /** 能用无障碍浮层的 WindowManager（能在锁屏上画）；没开就是 null */
    fun windowManager(): WindowManager? = try {
        service?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    } catch (t: Throwable) {
        null
    }

    /** 服务对象本身（需要 context 时用） */
    fun serviceContext(): Context? = service
}
