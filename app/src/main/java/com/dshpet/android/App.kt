package com.dshpet.android

import android.app.Application

/** 兜住所有未捕获异常，先落盘再交给系统默认处理（这样至少能查到原因） */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                Diag.record(this, "未捕获异常（线程 ${thread.name}）", ex)
            } catch (_: Throwable) {
            }
            def?.uncaughtException(thread, ex)
        }
        Diag.record(this, "应用进程启动，已安装崩溃捕获")
        // 点击音效只需预载一次
        Sfx.init(this)
        Sfx.enabled = Prefs.soundOn(this)
    }
}
