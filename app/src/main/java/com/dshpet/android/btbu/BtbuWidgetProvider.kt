package com.dshpet.android.btbu

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import com.dshpet.android.MainActivity
import com.dshpet.android.R
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 桌面挂件：真正嵌在桌面网格里的那张卡。
 *
 * 为什么不用自己定时刷：
 *  - 时钟交给 `TextClock`（系统驱动，秒级跳动，零耗电）
 *  - 上课倒计时交给 `Chronometer` + `setChronometerCountDown`（系统驱动，同样零耗电）
 *  - 数据（余额/课名）只在同步之后刷一次，靠 [refresh] 广播触发
 *
 * 这样即使 App 进程被杀，时间和倒计时照样在走 —— 这是 AppWidget 相对悬浮窗唯一的硬优势。
 */
class BtbuWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_UPDATE = "com.dshpet.android.btbu.WIDGET_UPDATE"

        /** 同步完成后调一下，把新数据推给挂件 */
        fun refresh(ctx: Context) {
            try {
                ctx.sendBroadcast(Intent(ctx, BtbuWidgetProvider::class.java).setAction(ACTION_UPDATE))
            } catch (_: Throwable) {
            }
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            super.onReceive(context, intent)
        } catch (_: Throwable) {
        }
        if (intent.action == ACTION_UPDATE) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, BtbuWidgetProvider::class.java))
            ids.forEach { render(context, mgr, it) }
        }
    }

    private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_btbu)
        val st = runCatching { BtbuSync.state(ctx) }.getOrNull()

        rv.setTextViewText(
            R.id.wWeek,
            if (st != null && st.hasData) "第 ${st.week} 周" else "未同步",
        )

        if (st == null || !st.hasData) {
            rv.setTextViewText(
                R.id.wBalance,
                if (BtbuSync.configured(ctx)) "点一下同步" else "点一下填账号",
            )
            rv.setTextViewText(R.id.wNext, "北工商桌宠")
            rv.setViewVisibility(R.id.wCountdown, android.view.View.GONE)
        } else {
            rv.setTextViewText(R.id.wBalance, st.balanceLine())
            rv.setTextViewText(R.id.wNext, st.nextLine())
            bindCountdown(rv, st)
        }

        // 点挂件 → 打开设置页
        rv.setOnClickPendingIntent(
            R.id.wClock,
            PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        mgr.updateAppWidget(id, rv)
    }

    /**
     * 倒计时：把"下一节课还有多久"变成一个系统在跑的 Chronometer。
     * base 用 elapsedRealtime + 剩余毫秒，Chronometer 自己往下数，不用我们刷。
     */
    private fun bindCountdown(rv: RemoteViews, st: BtbuStore.HudState) {
        val course = st.current ?: st.next ?: run {
            rv.setViewVisibility(R.id.wCountdown, android.view.View.GONE)
            return
        }
        val start = runCatching { LocalTime.parse(course.start, DateTimeFormatter.ofPattern("HH:mm")) }
            .getOrNull() ?: run {
            rv.setViewVisibility(R.id.wCountdown, android.view.View.GONE)
            return
        }
        val end = runCatching { LocalTime.parse(course.end, DateTimeFormatter.ofPattern("HH:mm")) }
            .getOrNull()

        val now = LocalDateTime.now()
        val target = if (st.current != null && end != null) {
            // 正在上课 → 数到下课
            now.toLocalDate().atTime(end)
        } else {
            // 还没上 → 数到上课（跨天就按当天算，最坏误差一天，可接受）
            now.toLocalDate().atTime(start).plusDays((st.nextInDays).toLong())
        }
        val remainMs = java.time.Duration.between(now, target).toMillis()
        if (remainMs <= 0) {
            rv.setViewVisibility(R.id.wCountdown, android.view.View.GONE)
            return
        }
        rv.setViewVisibility(R.id.wCountdown, android.view.View.VISIBLE)
        rv.setChronometer(R.id.wCountdown, SystemClock.elapsedRealtime() + remainMs, null, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            rv.setChronometerCountDown(R.id.wCountdown, true)
        }
    }
}
