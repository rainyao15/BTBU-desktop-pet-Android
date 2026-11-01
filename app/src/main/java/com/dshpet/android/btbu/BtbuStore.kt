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
            // 顺手把「本学期第 1 周从哪天开始」也拿下来 —— 这是周次最靠谱的来源
            runCatching { BtbuApi.termStart(tok, s.xn, s.xq) }
                .getOrNull()
                ?.let { day ->
                    val old = sp.getLong("termStartDay", 0L)
                    sp.edit().apply {
                        putLong("termStartDay", day)
                        if (old != 0L && old != day) putInt("weekOffset", 0)
                    }.apply()
                    android.util.Log.i("BtbuStore", "学期开始日 epochDay=$day")
                }
        }.onFailure { android.util.Log.w("BtbuStore", "课表失败 ${it.message}") }

        // 就算课表接口挂了，也把学期开始日拿到（周次不依赖课表）
        runCatching {
            val day = BtbuApi.termStart(tok, "", -1) ?: return@runCatching
            val old = sp.getLong("termStartDay", 0L)
            sp.edit().apply {
                putLong("termStartDay", day)
                // 换学期了 → 之前手动校正的偏移作废
                if (old != 0L && old != day) putInt("weekOffset", 0)
            }.apply()
        }.onFailure { android.util.Log.w("BtbuStore", "学期开始日失败 ${it.message}") }

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
        // 服务器给的 curWeek 先做合理性检查：
        //  · 超出 1..totalWeek（学期未开始时有些库会回 0）→ 不信
        //  · 和"按旧锚点推算的周次"差超过 2 周 → 不信（防止偶发脏数据把周次打回第 1 周）
        val total = s.totalWeek.coerceAtLeast(1)
        val expected = weekNow()
        val rawOk = s.curWeek in 1..total
        val base = when {
            !rawOk -> expected.coerceIn(1, total).also {
                android.util.Log.w("BtbuStore", "curWeek=${s.curWeek} 非法，沿用推算值 $it")
            }
            expected <= 1 -> s.curWeek
            kotlin.math.abs(s.curWeek - expected) <= 2 -> s.curWeek
            else -> expected.coerceIn(1, total).also {
                android.util.Log.w("BtbuStore", "curWeek=${s.curWeek} 与推算 $expected 差太多，沿用推算值")
            }
        }
        // 节次表：键必须是 String。s.lessons 是 Map<Int, …>，直接喂 JSONObject(Map)
        // 会在内部 (String) entry.getKey() 上抛 ClassCastException（Integer→String），
        // 让整个 editor 链在 apply() 之前就炸掉 —— 课表永远存不进去（"最近没课 摸鱼"的元凶）。
        val lessonJson = JSONObject().apply {
            for ((idx, span) in s.lessons) {
                put(idx.toString(), JSONArray().put(span.first).put(span.second))
            }
        }
        sp.edit()
            .putString("courses", arr.toString())
            .putLong("weekAnchorDay", monday.toEpochDay())
            .putLong("weekAnchorAt", at)
            .putString("lessons", lessonJson.toString())
            .putString("term", "${s.xn}-${s.xq}|${s.describe}")
            .putInt("curWeek", base)
            .putInt("curWeekRaw", s.curWeek)
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
        val offset = sp.getInt("weekOffset", 0)
        val base = sp.getInt("curWeek", 1).coerceIn(1, total)

        // ① 最靠谱：学期开始日期（开学第 1 周的周一）→ 第 N 周 = (今天 - 开学)/7 + 1
        val termStart = sp.getLong("termStartDay", 0L)
        if (termStart != 0L) {
            val days = java.time.LocalDate.now().toEpochDay() - termStart
            if (days >= 0) return ((days / 7).toInt() + 1 + offset).coerceIn(1, total)
        }

        val anchor = sp.getLong("weekAnchorDay", 0L)
        if (anchor != 0L) {
            val todayMonday = mondayEpochDay(java.time.LocalDate.now())
            val weeks = ((todayMonday - anchor) / 7L).toInt()
            return (base + weeks + offset).coerceIn(1, total)
        }
        // 老缓存（升级上来的）：退回旧递推，等下次同步就会写上锚点
        val at = sp.getLong("scheduleAt", 0L)
        if (at == 0L) return (base + offset).coerceIn(1, total)
        val weeks = ((System.currentTimeMillis() - at) / (7L * 24 * 3600 * 1000)).toInt()
        return (base + weeks + offset).coerceIn(1, total)
    }

    /** 某个日期所在周的周一（epochDay） */
    private fun mondayEpochDay(d: java.time.LocalDate): Long =
        d.toEpochDay() - (d.dayOfWeek.value - 1).toLong()

    /**
     * 教学周校正：把"当前周"按今天重新钉一次。
     * 学校周次口径和你数的差一周时，点一下就好；之后仍然按周一自动往下走。
     */
    fun shiftWeek(delta: Int) {
        val total = sp.getInt("totalWeek", 25).coerceAtLeast(1)
        val cur = weekNow()
        val target = (cur + delta).coerceIn(1, total)
        if (target == cur) return
        val off = sp.getInt("weekOffset", 0) + delta
        sp.edit().putInt("weekOffset", off).apply()
    }

    /** 自检用：周次是怎么算出来的 */
    fun weekInfo(): String {
        val total = sp.getInt("totalWeek", 25)
        val base = sp.getInt("curWeek", 1)
        val raw = sp.getInt("curWeekRaw", base)
        val anchor = sp.getLong("weekAnchorDay", 0L)
        val anchorTxt = if (anchor == 0L) "无锚点(老缓存)"
        else java.time.LocalDate.ofEpochDay(anchor).toString() + "(周一)"
        val termStartDay = sp.getLong("termStartDay", 0L)
        val offset = sp.getInt("weekOffset", 0)
        val src = if (termStartDay != 0L) "学期开始日" else if (anchor != 0L) "同步锚点" else "老缓存"
        val startTxt = if (termStartDay != 0L) java.time.LocalDate.ofEpochDay(termStartDay).toString()
        else if (anchor != 0L) java.time.LocalDate.ofEpochDay(anchor - (base - 1) * 7L).toString()
        else "--"
        return "第 ${weekNow()}/${total} 周（来源：$src）· 开学首周周一=$startTxt · " +
            "服务器 curWeek=$raw · 校正偏移=${if (offset >= 0) "+$offset" else "$offset"} · 锚点=$anchorTxt"
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
        val raw = sp.getString("lessons", null) ?: return DEFAULT_LESSONS
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return DEFAULT_LESSONS
        val saved = o.keys().asSequence().mapNotNull { k ->
            val idx = k.toIntOrNull() ?: return@mapNotNull null
            val a = o.optJSONArray(k) ?: return@mapNotNull null
            val s = a.optString(0)
            val e = a.optString(1)
            if (s.isBlank() || e.isBlank() || s == "--:--") null else idx to (s to e)
        }.toMap()
        // 服务器给的优先，缺的用内置表补
        return DEFAULT_LESSONS + saved
    }

    // ---------------- 课表数据的读取接口（气泡/"下一节课"用） ----------------

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
        /**
         * 内置节次时间表（照抄学校 getCourseTimeConfig.do 的真实响应）。
         * 为什么要有：只要那个接口偶尔失败一次，所有课的时间就会变成 "--:--"，
         * 表现就是"上课时间对不上"。有兜底就永远不会没时间。
         */
        val DEFAULT_LESSONS: Map<Int, Pair<String, String>> = mapOf(
            1 to ("08:00" to "08:45"),
            2 to ("08:50" to "09:35"),
            3 to ("09:50" to "10:35"),
            4 to ("10:40" to "11:25"),
            5 to ("11:30" to "12:15"),
            6 to ("13:40" to "14:25"),
            7 to ("14:30" to "15:15"),
            8 to ("15:30" to "16:15"),
            9 to ("16:20" to "17:05"),
            10 to ("17:10" to "17:55"),
            11 to ("18:45" to "19:30"),
            12 to ("19:35" to "20:20"),
            13 to ("20:25" to "21:10"),
        )

        fun wdName(w: Int) = when (w) {
            1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
            5 -> "周五"; 6 -> "周六"; else -> "周日"
        }
    }
}
