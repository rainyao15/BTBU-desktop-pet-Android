package com.dshpet.android.btbu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 缓存 + 派生：桌宠的 HUD / AppWidget 只读这里，不直接碰网络。
 *
 * 设计要点（照抄 repo2 的降级哲学）：
 *  - 网络失败 → 返回上次成功的数据 + 时间戳，绝不抛到 UI 层把服务搞崩
 *  - 周次是"会走的时间"，所以存 curWeek + 抓取时刻，之后按自然周递推
 *  - 派生（当前这节课 / 下一节课 / 倒计时）每次读时现算，不在缓存里固化
 */
class BtbuStore(ctx: Context) {

    private val sp = ctx.getSharedPreferences("btbu_cache", Context.MODE_PRIVATE)

    data class HudState(
        val syncedAt: Long,
        val week: Int,
        val totalWeek: Int,
        val term: String,
        val net: NetInfo?,
        val card: CardInfo?,
        val current: Course?,
        val next: Course?,
        val nextInDays: Int,       // 0=今天，1=明天…
        val todayCount: Int,
    ) {
        val hasData get() = syncedAt > 0

        /** 今天有没有课（周次位图已经过滤过） */
        val todayHasClass get() = todayCount > 0

        /** 一行显示余额（挂件/通知栏/小卡用）：**不再写"网 15.00 卡 25.80"**，字样给全 */
        fun balanceLine(): String {
            val n = net?.let { "%.2f".format(it.mainBalance) } ?: "--"
            val c = card?.let { "%.2f".format(it.balanceYuan) } ?: "--"
            return "网费 $n · 校园卡 $c"
        }

        /** 校园卡余额（气泡大字） */
        fun cardMain(): String = card?.let { "%.2f 元".format(it.balanceYuan) } ?: "--"

        /** 网费余额（气泡大字） */
        fun netMain(): String = net?.let { "%.2f 元".format(it.mainBalance) } ?: "--"

        /** 网费副行：本月流量 */
        fun netDetail(): String = net?.let {
            "本月 ${it.flowMonth.ifEmpty { "--" }} / ${it.flowTotal.ifEmpty { "--" }}"
        } ?: "还没同步"

        /** 一节/一段课：第 8 节 / 第 8-10 节 */
        fun rangeOf(c: Course): String =
            if (c.jcTo > c.jcFrom) "第 ${c.jcFrom}-${c.jcTo} 节" else "第 ${c.jcFrom} 节"

        /** 气泡「下一节课」页：大字＝几点上课（正在上则显示几点下课） */
        fun classMain(): String = when {
            current != null -> "下课 ${current.end}"
            next != null -> next.start
            else -> "没课"
        }

        /** 气泡「下一节课」页：副行＝哪天/课名/第几节/在哪上 */
        fun classDetail(): String = when {
            current != null -> "${current.name} · ${rangeOf(current)} · ${current.room}"
            next != null -> {
                val d = if (nextInDays == 0) "今天" else BtbuStore.wdName(next.weekday)
                "$d ${next.name} · ${rangeOf(next)} · ${next.room}"
            }
            else -> "最近没课 摸鱼"
        }
        /** 供 HUD 显示下一节课，例如 "周一 15:30 模拟电子技术 @工2-203-2" */
        fun nextLine(): String = when {
            current != null -> "正在上 ${current.name} @${current.room}"
            next != null -> {
                val d = if (nextInDays == 0) "今天" else BtbuStore.wdName(next.weekday)
                "$d ${next.start} ${next.name} @${next.room}"
            }
            else -> "最近没课 摸鱼"
        }

        /** 标题行，例如 "北工商 · 第 8 周" */
        fun headLine(): String = "北工商 · 第 $week 周"

        /**
         * 倒计时还剩多少毫秒：正在上课 → 数到下课；还没上 → 数到下一节开始。
         * 没课 / 已经超时返回 null。桌面挂件和头顶气泡共用这一份逻辑，别各写一套。
         */
        fun countdownRemainMs(now: LocalDateTime = LocalDateTime.now()): Long? {
            val c = current ?: next ?: return null
            val fmt = DateTimeFormatter.ofPattern("HH:mm")
            val start = runCatching { LocalTime.parse(c.start, fmt) }.getOrNull() ?: return null
            val end = runCatching { LocalTime.parse(c.end, fmt) }.getOrNull()
            val target = if (current != null && end != null) {
                now.toLocalDate().atTime(end)                       // 正在上 → 数到下课
            } else {
                now.toLocalDate().atTime(start).plusDays(nextInDays.toLong()) // 跨天按天补
            }
            val ms = Duration.between(now, target).toMillis()
            return if (ms > 0) ms else null
        }
    }

    // ---------------- 网络刷新 ----------------

    /**
     * 全量刷新。成功几个就更新几个；全失败返回 false（调用方据此显示"上次同步"）。
     * 必须后台线程。
     */
    fun refresh(login: BtbuApi.Login? = null, userName: String = "", password: String = "", uuid: String = ""): Boolean {
        val tok = login?.token?.takeIf { it.isNotEmpty() }
            ?: (if (userName.isNotEmpty()) BtbuApi.login(userName, password, uuid).token else return false)
        sp.edit().putString("token", tok).apply()
        if (login != null) {
            sp.edit().putString("realName", login.realName).putString("userName", login.userName).apply()
        }

        var any = false
        runCatching {
            val s = BtbuApi.schedule(tok)
            saveSchedule(s, System.currentTimeMillis()); any = true
        }.onFailure { android.util.Log.w("BtbuStore", "课表失败 ${it.message}") }

        runCatching { BtbuApi.net(tok)?.let { saveNet(it); any = true } }
            .onFailure { android.util.Log.w("BtbuStore", "网费失败 ${it.message}") }

        runCatching { BtbuApi.card(tok)?.let { saveCard(it); any = true } }
            .onFailure { android.util.Log.w("BtbuStore", "一卡通失败 ${it.message}") }

        if (any) sp.edit().putLong("syncedAt", System.currentTimeMillis()).apply()
        return any
    }

    private fun saveSchedule(s: Schedule, at: Long) {
        val arr = JSONArray()
        s.courses.forEach {
            arr.put(JSONObject().apply {
                put("name", it.name); put("teacher", it.teacher); put("room", it.room)
                put("weekday", it.weekday); put("jcFrom", it.jcFrom); put("jcTo", it.jcTo)
                put("start", it.start); put("end", it.end); put("weeks", it.weeks)
            })
        }
        // 周次锚点：把"服务器说的第几周"钉死在**同步当周周一**上。
        // 以后算周次不再用"距上次同步过了几个 7 天"（那会在跨周那两天错一周，
        // 第 1 周开学时最明显），而是"今天周一 - 锚点周一"。
        val today = java.time.LocalDate.now()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        sp.edit()
            .putString("courses", arr.toString())
            .putLong("weekAnchorDay", monday.toEpochDay())
            .putLong("weekAnchorAt", at)
            .putString("lessons", JSONObject(s.lessons.mapValues { listOf(it.value.first, it.value.second) }).toString())
            .putString("term", "${s.xn}-${s.xq}|${s.describe}")
            .putInt("curWeek", s.curWeek.coerceAtLeast(1))
            .putInt("totalWeek", s.totalWeek.coerceAtLeast(1))
            .putLong("scheduleAt", at)
            .apply()
    }

    private fun saveNet(n: NetInfo) = sp.edit()
        .putString("net", JSONObject().apply {
            put("main", n.mainBalance); put("free", n.freeBalance); put("pack", n.packageName)
            put("status", n.status); put("flowTotal", n.flowTotal); put("flowMonth", n.flowMonth)
        }.toString()).apply()

    private fun saveCard(c: CardInfo) = sp.edit()
        .putString("card", JSONObject().apply {
            put("yuan", c.balanceYuan); put("raw", c.rawBalance); put("no", c.cardNo)
        }.toString()).apply()

    // ---------------- 读取 + 派生 ----------------

    fun state(): HudState {
        val courses = readCourses()
        val lessons = readLessons()
        val curWeek = weekNow()
        val now = LocalDateTime.now()
        val wd = now.dayOfWeek.value          // 1=周一 … 7=周日
        val hh = now.format(DateTimeFormatter.ofPattern("HH:mm"))

        val active = courses.filter { it.activeAt(curWeek) }
        val todays = active.filter { it.weekday == wd }.sortedBy { it.jcFrom }
        val current = todays.firstOrNull { hh >= it.start && hh < it.end }
        val later = todays.filter { it.start > hh }

        var next: Course? = later.firstOrNull()
        var days = 0
        if (next == null) {
            for (d in 1..7) {
                val w = ((wd - 1 + d) % 7) + 1
                val c = active.filter { it.weekday == w }.minByOrNull { it.jcFrom }
                if (c != null) { next = c; days = d; break }
            }
        }

        val netJ = sp.getString("net", null)?.let { JSONObject(it) }
        val cardJ = sp.getString("card", null)?.let { JSONObject(it) }
        val term = sp.getString("term", "") ?: ""

        return HudState(
            syncedAt = sp.getLong("syncedAt", 0L),
            week = curWeek,
            totalWeek = sp.getInt("totalWeek", 25),
            term = term.substringAfter('|', term),
            net = netJ?.let {
                NetInfo(it.optDouble("main"), it.optDouble("free"), it.optString("pack"),
                    it.optString("status"), it.optString("flowTotal"), it.optString("flowMonth"))
            },
            card = cardJ?.let {
                CardInfo(it.optDouble("yuan"), it.optString("raw"), it.optString("no"))
            },
            current = current,
            next = next,
            nextInDays = days,
            todayCount = todays.size,
        )
    }

    /**
     * 当前教学周。
     *
     * 老算法：curWeek + (距上次同步的 7 天整数个) —— 同步发生在周三、周一再看就会少算一周，
     * 学期第 1 周尤其明显（"第 1 周时间对不上"就是这个）。
     *
     * 新算法：同步时把 curWeek 钉在**那一周的周一**（weekAnchorDay），之后
     *   当前周 = curWeek + (今天所在周的周一 - 锚点周一) / 7
     * 只跟"星期几"有关，跟"几点同步的、隔了几天"无关，所以跨周、跨月、离线几个月都不会漂。
     */
    private fun weekNow(): Int {
        val total = sp.getInt("totalWeek", 25).coerceAtLeast(1)
        val base = sp.getInt("curWeek", 1).coerceIn(1, total)
        val anchor = sp.getLong("weekAnchorDay", 0L)
        if (anchor != 0L) {
            val todayMonday = mondayEpochDay(java.time.LocalDate.now())
            val weeks = ((todayMonday - anchor) / 7L).toInt()
            return (base + weeks).coerceIn(1, total)
        }
        // 老缓存（升级上来的）：退回旧递推，等下次同步就会写上锚点
        val at = sp.getLong("scheduleAt", 0L)
        if (at == 0L) return base
        val weeks = ((System.currentTimeMillis() - at) / (7L * 24 * 3600 * 1000)).toInt()
        return (base + weeks).coerceIn(1, total)
    }

    /** 某个日期所在周的周一（epochDay） */
    private fun mondayEpochDay(d: java.time.LocalDate): Long =
        d.toEpochDay() - (d.dayOfWeek.value - 1).toLong()

    /** 自检用：周次是怎么算出来的 */
    fun weekInfo(): String {
        val total = sp.getInt("totalWeek", 25)
        val base = sp.getInt("curWeek", 1)
        val anchor = sp.getLong("weekAnchorDay", 0L)
        val anchorTxt = if (anchor == 0L) "无锚点(老缓存)"
        else java.time.LocalDate.ofEpochDay(anchor).toString() + "(周一)"
        val termStart = if (anchor == 0L) "--"
        else java.time.LocalDate.ofEpochDay(anchor - (base - 1) * 7L).toString()
        return "第 ${weekNow()}/${total} 周 · 服务器 curWeek=$base · 锚点=$anchorTxt · 推算学期首周周一=$termStart"
    }

    private fun readCourses(): List<Course> {
        val raw = sp.getString("courses", null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Course(
                name = o.optString("name"), teacher = o.optString("teacher"),
                room = o.optString("room"), weekday = o.optInt("weekday"),
                jcFrom = o.optInt("jcFrom"), jcTo = o.optInt("jcTo"),
                start = o.optString("start"), end = o.optString("end"),
                weeks = o.optString("weeks"),
            )
        }
    }

    private fun readLessons(): Map<Int, Pair<String, String>> {
        val raw = sp.getString("lessons", null) ?: return emptyMap()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associate { k ->
            val a = o.optJSONArray(k)
            k.toInt() to ((a?.optString(0) ?: "--:--") to (a?.optString(1) ?: "--:--"))
        }
    }

    // ---------------- 给"整周课表"页用的读取接口 ----------------

    /** 某一周要上的课（已按 skzc 位图过滤） */
    fun coursesOf(week: Int): List<Course> = readCourses().filter { it.activeAt(week) }

    fun allCourses(): List<Course> = readCourses()

    fun lessonsOf(): Map<Int, Pair<String, String>> = readLessons()

    fun totalWeekOf(): Int = sp.getInt("totalWeek", 25)

    fun weekOf(): Int = weekNow()

    fun termOf(): String {
        val t = sp.getString("term", "") ?: ""
        return t.substringAfter('|', t)
    }

    fun syncedAtOf(): Long = sp.getLong("syncedAt", 0L)

    fun realName(): String = sp.getString("realName", "") ?: ""
    fun hasToken(): Boolean = !sp.getString("token", null).isNullOrEmpty()

    companion object {
        fun wdName(w: Int) = when (w) {
            1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
            5 -> "周五"; 6 -> "周六"; else -> "周日"
        }
    }
}
