package com.dshpet.android.btbu

import android.content.Context
import com.dshpet.android.Prefs
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 让宠物对"真实数据"做反应。
 *
 * 设计原则：
 *  - 只在**状态变化**时说话（同一个提醒一天只响一次 / 同一节课只提醒一次），不然就是刷屏
 *  - 优先级从高到低，一次只挑一条，避免宠物连着蹦三句话
 *  - 说话不阻塞：调用方（PetService）在帧循环里 60 秒探一次，拿不到数据就什么都不做
 */
object BtbuAlerts {

    data class Alert(val key: String, val text: String)

    private const val DAY = 24 * 3600 * 1000L

    /** 挑一条该说的话；没有就返回 null */
    fun pick(ctx: Context): Alert? {
        val st = runCatching { BtbuSync.state(ctx) }.getOrNull() ?: return null
        if (!st.hasData) return null

        val now = LocalDateTime.now()

        // 1) 下一节课 10 分钟内 → 催
        st.next?.let { c ->
            if (st.current == null && st.nextInDays == 0) {
                val start = parse(c.start) ?: return@let
                val minutes = java.time.Duration.between(now.toLocalTime(), start).toMinutes()
                if (minutes in 0..10) {
                    val key = "soon_${c.name}_${c.start}"
                    if (cooled(ctx, key, DAY)) {
                        return Alert(key, "${c.name} 还有 ${minutes} 分钟，${c.room} 等你～")
                    }
                }
            }
        }

        // 2) 校园网被限速 / 余额快没了
        val net = st.net
        if (net != null) {
            val limited = net.status.contains("限")
            val low = net.mainBalance < 20.0
            if ((limited || low) && cooled(ctx, "net", DAY)) {
                val text = if (limited) "网被限速了…要不去充点？" else "网费只剩 ${"%.2f".format(net.mainBalance)} 了"
                return Alert("net", text)
            }
        }

        // 3) 校园卡余额低
        st.card?.let { c ->
            if (c.balanceYuan < 30.0 && cooled(ctx, "card", DAY)) {
                return Alert("card", "卡里只剩 ${"%.2f".format(c.balanceYuan)} 了，饭要吃不起了")
            }
        }

        // 4) 正在上课 → 偶尔提醒教室（每节课一次）
        st.current?.let { c ->
            val key = "in_${c.name}_${c.start}"
            if (cooled(ctx, key, DAY)) {
                return Alert(key, "在 ${c.room} 上${c.name}，${c.end} 下课")
            }
        }

        // 5) 晚上还没同步过 → 温和提示一次
        if (now.hour >= 22 && cooled(ctx, "stale", DAY) &&
            System.currentTimeMillis() - st.syncedAt > 12 * 3600 * 1000L
        ) {
            return Alert("stale", "今天还没同步过，去戳一下小卡？")
        }

        return null
    }

    /** 冷却判断：key 距离上次说超过 [windowMs] 才允许再说 */
    private fun cooled(ctx: Context, key: String, windowMs: Long): Boolean {
        val last = Prefs.alertAt(ctx, key)
        return last == 0L || System.currentTimeMillis() - last > windowMs
    }

    fun mark(ctx: Context, key: String) = Prefs.setAlertAt(ctx, key, System.currentTimeMillis())

    private fun parse(hhmm: String): LocalTime? = runCatching {
        LocalTime.parse(hhmm, DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrNull()
}
