package com.dshpet.android.btbu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
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
        /** 供 HUD 一行显示，例如 "网 15.00 | 卡 25.80" */
        fun balanceLine(): String {
            val n = net?.let { "%.2f".format(it.mainBalance) } ?: "--"
            val c = card?.let { "%.2f".format(it.balanceYuan) } ?: "--"
            return "网 $n  卡 $c"
        }
        /** 供 HUD 显示下一节课，例如 "周一 15:30 模拟电子技术 @工2-203-2" */
        fun nextLine(): String = when {
            current != null -> "正在上 ${current.name} @${current.room}"
            next != null -> {
                val d = if (nextInDays == 0) "今天" else BtbuStore.wdName(next.weekday)
                "$d ${next.start} ${next.name} @${next.room}"
            }
            else -> "最近没课，摸鱼"
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
        sp.edit()
            .putString("courses", arr.toString())
            .putString("lessons", JSONObject(s.lessons.mapValues { listOf(it.value.first, it.value.second) }).toString())
            .putString("term", "${s.xn}-${s.xq}|${s.describe}")
            .putInt("curWeek", s.curWeek).putInt("totalWeek", s.totalWeek)
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

    /** 复刻 normalize.py 的周次递推：curWeek + 经过的自然周 */
    private fun weekNow(): Int {
        val base = sp.getInt("curWeek", 1)
        val at = sp.getLong("scheduleAt", 0L)
        if (at == 0L) return base
        val weeks = ((System.currentTimeMillis() - at) / (7L * 24 * 3600 * 1000)).toInt()
        val total = sp.getInt("totalWeek", 25)
        return (base + weeks).coerceIn(1, total)
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
