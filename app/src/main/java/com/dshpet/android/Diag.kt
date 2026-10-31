package com.dshpet.android

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自诊断：把服务端每一步的异常记下来，写进 SharedPreferences（供界面显示）
 * 和应用外部目录 diag.txt（方便用文件管理器取出后发给开发者）。
 *
 * 目的：手机上闪退时拿不到 logcat，至少要让错误可见、可复制，而不是进程直接死掉。
 */
object Diag {
    private const val PREF = "diag"
    private const val KEY_LAST = "last"
    private const val TAG = "DshPetDiag"

    fun record(ctx: Context, stage: String, t: Throwable? = null) {
        val line = buildString {
            append("[").append(SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())).append("] ")
            append(stage)
            if (t != null) {
                append("\n").append(t.javaClass.name).append(": ").append(t.message ?: "(no message)")
                t.stackTrace.take(14).forEach { append("\n    at ").append(it.toString()) }
                var cause = t.cause
                var depth = 0
                while (cause != null && depth < 3) {
                    append("\n  caused by ").append(cause.javaClass.name).append(": ").append(cause.message)
                    cause.stackTrace.take(6).forEach { append("\n    at ").append(it.toString()) }
                    cause = cause.cause
                    depth++
                }
            }
            append("\n")
        }
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_LAST, line).apply()
        } catch (_: Throwable) {
        }
        Log.e(TAG, line)
        try {
            val f = File(ctx.getExternalFilesDir(null), "diag.txt")
            f.appendText(line + "--------\n")
        } catch (_: Throwable) {
        }
    }

    fun last(ctx: Context): String =
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LAST, "") ?: ""
        } catch (_: Throwable) {
            ""
        }

    fun clear(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_LAST).apply()
        } catch (_: Throwable) {
        }
    }
}
