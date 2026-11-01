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
        val nextWeek: Int,         // 下一节课属于第几教学周（跨周时 ≠ week）
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
        /** 哪天/第几周：今天 / 明天 / 周一…，跨周时带上"第 N 周" */
        fun whenLabel(): String {
            val base = when (nextInDays) {
                0 -> "今天"
                1 -> "明天"
                else -> BtbuStore.wdName(next?.weekday ?: 1)
            }
            return if (next != null && nextWeek != week) "第 $nextWeek 周 $base" else base
        }

        fun nextLine(): String = when {
            current != null -> "正在上 ${current.name} @${current.room}"
            next != null -> "${whenLabel()} ${next.start} ${next.name} @${next.room}"
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
            // 按"我们算的当前周"去问，并按服务器回的 curWeek 校准一次
            val wantWeek = weekNow().coerceAtLeast(1)
            // curTime 用"本周周三 12:00"而不是 now：教务是拿 curTime 判断你问的是哪一周的
            //（见 captures/课表_A..E.json：curTime 落在第 9 周才返回「模拟电子技术实验 6-7 节 工3-202」）
            val s = BtbuApi.scheduleCalibrated(tok, wantWeek, anchorMsForWeek(wantWeek))
            saveSchedule(s, System.currentTimeMillis(), wantWeek); any = true
            // 服务器按 curTime 回的 curWeek 就是它对"这周"的判断 → 和本地算的不一致就以它为准，
            // 把学期开始日整体挪 (差几周 × 7) 天，之后 local == server，不会再飘。
            val serverWeek = s.curWeek
            if (serverWeek in 1..s.totalWeek.coerceAtLeast(1) && serverWeek != wantWeek) {
                val diff = wantWeek - serverWeek
                val termStart = sp.getLong("termStartDay", 0L)
                if (termStart != 0L && kotlin.math.abs(diff) <= 3) {
                    sp.edit().putLong("termStartDay", termStart + diff * 7L).apply()
                    android.util.Log.w(
                        "BtbuStore",
                        "周次对齐：本地第 $wantWeek 周 / 服务器第 $serverWeek 周 → 学期开始日挪 ${diff * 7} 天",
                    )
                }
            }
            android.util.Log.i(
                "BtbuStore",
                "课表：问第 $wantWeek 周 → 拿到第 ${s.curWeek} 周 · ${s.courses.size} 门课",
            )
            // 今天已经没有课了（晚上/周末/今天没课）→ 顺手把**下一周**也拉一份，
            // 这样"明天/下周"那节课的节次和教室才是教务按周给的那一份（实验课常常只从第 9 周开始）。
            if (!hasClassLeftToday(s)) {
                val nw = (s.curWeek + 1).coerceAtMost(s.totalWeek.coerceAtLeast(1))
                val lastNext = sp.getLong("nextWeekAt", 0L)
                if (nw > wantWeek && System.currentTimeMillis() - lastNext > 10 * 60_000L) {
                    runCatching {
                        BtbuApi.scheduleCalibrated(tok, nw, anchorMsForWeek(nw))
                    }.getOrNull()?.let { s2 ->
                        saveSchedule(s2, System.currentTimeMillis(), nw)
                        sp.edit().putLong("nextWeekAt", System.currentTimeMillis()).apply()
                        android.util.Log.i("BtbuStore", "课表：顺带拉了第 $nw 周（${s2.courses.size} 门）")
                    }
                }
            }
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

    /**
     * 第 [week] 周「周三 12:00」的时间戳，喂给接口的 `curTime`。
     * 教务用 curTime 判断"你要哪一周的课表"，掌上北工商切周时也是这么发的。
     * 拿不到学期开始日时，用"当前时间 + (week - 当前周) 周"兜底。
     */
    fun anchorMsForWeek(week: Int): Long {
        val now = java.time.LocalDateTime.now()
        val termStart = sp.getLong("termStartDay", 0L)
        val date = if (termStart != 0L) {
            java.time.LocalDate.ofEpochDay(termStart).plusWeeks((week - 1).toLong()).plusDays(2)
        } else {
            now.toLocalDate().plusWeeks((week - weekNow()).toLong()).plusDays(2)
        }
        return date.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** 今天这个教学周还有没有没上完的课 */
    private fun hasClassLeftToday(s: Schedule): Boolean {
        val now = java.time.LocalDateTime.now()
        val hh = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        val wd = now.dayOfWeek.value
        return s.courses.any { it.weekday == wd && it.activeAt(s.curWeek) && it.end > hh }
    }

    private fun saveSchedule(s: Schedule, at: Long, weekOverride: Int = 0) {
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
        // 按**教学周**分桶存：教务是"按周给课表"的，第 8 周的模电可能是 8-10 节/工2-203-2，
        // 第 9 周就变成 6-7 节/工3-202（实验室）。只存一份的话，跨周显示必然是错的。
        val bucketWeek = if (weekOverride >= 1) weekOverride else s.curWeek.coerceAtLeast(1)
        val bucket = "courses_$bucketWeek"
        val kept = storedWeeks().sortedByDescending { it }.take(2)   // 加上这次最多 3 周
        val editor = sp.edit().putString(bucket, arr.toString())
        for (w in storedWeeks()) if (w !in kept && "courses_$w" != bucket) editor.remove("courses_$w")
        editor.putString("courseWeeks", (kept + bucketWeek).distinct().joinToString(","))
        editor
            .putString("courses", arr.toString())        // 老字段保留一份（老版本/兜底）
            .putLong("weekAnchorDay", monday.toEpochDay())
            .putLong("weekAnchorAt", at)
            .putString("lessons", lessonJson.toString())
            .putString("term", "${s.xn}-${s.xq}|${s.describe}")
            .putInt("curWeek", base)
            // 「服务器 curWeek」只在**本周那次请求**里记：顺手补拉下周时也会走这里，
            // 如果照写，自检行会显示成服务器的"下周周次"，看着像本地周次错了。
            .apply {
                if (weekOverride < 1) {
                    putInt("curWeekRaw", s.curWeek)
                    putInt("scheduleWeek", s.curWeek)
                }
            }
            .putInt("courseCount", s.courses.size)
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
        val curWeek = weekNow()
        val courses = coursesFor(curWeek)
        val now = LocalDateTime.now()
        val wd = now.dayOfWeek.value          // 1=周一 … 7=周日
        val hh = now.format(DateTimeFormatter.ofPattern("HH:mm"))

        val todays = courses.filter { it.weekday == wd }.sortedBy { it.jcFrom }
        val current = todays.firstOrNull { hh >= it.start && hh < it.end }
        val later = todays.filter { it.start > hh }

        var next: Course? = later.firstOrNull()
        var days = 0
        var nextWeek = curWeek
        if (next == null) {
            // 关键：明天/下周的课，得按**那一天所在的教学周**去过滤位图。
            // 以前一律用本周过滤 —— 于是“某门课第 9 周才开始”（实验课常这样）就会被漏掉。
            val today = now.toLocalDate()
            for (d in 1..7) {
                val target = today.plusDays(d.toLong())
                val w = weekOfDate(target)
                val c = coursesFor(w)
                    .filter { it.weekday == target.dayOfWeek.value }
                    .minByOrNull { it.jcFrom }
                if (c != null) {
                    next = c
                    days = d
                    nextWeek = w
                    break
                }
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
            nextWeek = nextWeek,
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
    private fun weekNow(): Int = weekOfDate(java.time.LocalDate.now())

    /**
     * 任意日期属于第几教学周。和 weekNow 同一套规则（学期开始日 → 锚点 → 老缓存），
     * 只是把"今天"换成传入的日期 —— 找"明天/下周"的课必须用它。
     */
    private fun weekOfDate(date: java.time.LocalDate): Int {
        val total = sp.getInt("totalWeek", 25).coerceAtLeast(1)
        val offset = sp.getInt("weekOffset", 0)
        val base = sp.getInt("curWeek", 1).coerceIn(1, total)

        // ① 最靠谱：学期开始日期（开学第 1 周的周一）→ 第 N 周 = (日期 - 开学)/7 + 1
        val termStart = sp.getLong("termStartDay", 0L)
        if (termStart != 0L) {
            val days = date.toEpochDay() - termStart
            if (days >= 0) return ((days / 7).toInt() + 1 + offset).coerceIn(1, total)
        }

        val anchor = sp.getLong("weekAnchorDay", 0L)
        if (anchor != 0L) {
            val targetMonday = mondayEpochDay(date)
            val weeks = ((targetMonday - anchor) / 7L).toInt()
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

    /** 自检用：这次课表是按第几周拉的、几门课、本周几门 */
    fun scheduleInfo(): String {
        val sw = sp.getInt("scheduleWeek", 0)
        val n = sp.getInt("courseCount", -1)
        val week = weekNow()
        val thisWeek = runCatching { coursesOf(week).size }.getOrDefault(-1)
        val at = sp.getLong("scheduleAt", 0L)
        val age = if (at == 0L) "从未" else "${(System.currentTimeMillis() - at) / 60_000L} 分钟前"
        val weeks = storedWeeks().sorted().joinToString(",").ifEmpty { "${sw}" }
        return "课表：手里有第 [$weeks] 周各一份 · 本周($week) $thisWeek 门 · 更新于 $age"
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

    /** 存过哪几周（"8,9" 这种） */
    private fun storedWeeks(): List<Int> =
        (sp.getString("courseWeeks", "") ?: "")
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }

    /** 指定周那一桶的原始 JSON；没有就退到"最接近的那一周"，再没有就用老字段 */
    private fun coursesJsonFor(week: Int): String? {
        sp.getString("courses_$week", null)?.let { return it }
        val near = storedWeeks().minByOrNull { kotlin.math.abs(it - week) }
        if (near != null) sp.getString("courses_$near", null)?.let { return it }
        return sp.getString("courses", null)
    }

    /**
     * 某一教学周应该上的课。
     *
     * 教务是**按周给课表**的（第 8 周只有模电 8-10 节；第 9 周多出「模电实验 6-7 节 工3-202」）。
     * 所以：
     *  · 手里正好有这一周的桶 → 直接用，**不再按位图( skzc )过滤**（这个模型下位图不可信）
     *  · 只有别周的桶（比如还没拉到下一周）→ 退回去用位图过滤，聊胜于无
     */
    private fun coursesFor(week: Int): List<Course> {
        sp.getString("courses_$week", null)?.let { return parseCourses(it) }
        return parseCourses(coursesJsonFor(week)).filter { it.activeAt(week) }
    }

    private fun readCourses(): List<Course> = parseCourses(coursesJsonFor(weekNow()))

    private fun parseCourses(raw: String?): List<Course> {
        if (raw == null) return emptyList()
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
    fun coursesOf(week: Int): List<Course> = coursesFor(week)

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
