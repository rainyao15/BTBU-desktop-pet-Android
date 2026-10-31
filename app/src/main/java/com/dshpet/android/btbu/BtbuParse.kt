package com.dshpet.android.btbu

import org.json.JSONArray
import org.json.JSONObject

/**
 * 数据模型 + 解析。
 *
 * 字段名全部来自真实响应（captures/*.json），不是猜的：
 *   一卡通  allPurposeCardVO.balance          = "2580"      ← 分，÷100 → 25.80 元
 *   网费    result.mainBalance                = 15.0        ← 元，浮动
 *   课表    courseList[].kcmc / skxq / skjc / cxjc / skzc / jash / jgmc
 *   节次    coursetimeList[].jcdm / startTime / endTime     ← "8:00" 这种单位数小时
 */
data class Course(
    val name: String,
    val teacher: String,
    val room: String,
    val weekday: Int,          // 1=周一 … 7=周日
    val jcFrom: Int,           // 起始节次
    val jcTo: Int,             // 结束节次
    val start: String,         // "08:00"
    val end: String,           // "08:45"
    val weeks: String,         // 周次位图 "1111111111111111000000…"，第 i 位 = 第 i+1 周
) {
    fun activeAt(week: Int): Boolean =
        week in 1..weeks.length && weeks[week - 1] == '1'
}

data class NetInfo(
    val mainBalance: Double,
    val freeBalance: Double,
    val packageName: String,
    val status: String,        // 如 "服务受限"
    val flowTotal: String,     // "25G"
    val flowMonth: String,     // "3.66G"
)

data class CardInfo(
    val balanceYuan: Double,
    val rawBalance: String,
    val cardNo: String,
)

data class Schedule(
    val xn: String,
    val xq: Int,
    val describe: String,
    val curWeek: Int,
    val totalWeek: Int,
    val courses: List<Course>,
    val lessons: Map<Int, Pair<String, String>>,
)

object BtbuParse {

    /** 一卡通余额单位：实测给的是分（2580 → 25.80 元）。若某天学校改成元，把这里改 1.0 */
    private const val CARD_DIVISOR = 100.0

    fun net(j: JSONObject): NetInfo? {
        val r = j.optJSONObject("result") ?: return null
        return NetInfo(
            mainBalance = r.optDouble("mainBalance", 0.0),
            freeBalance = r.optDouble("freeBalance", 0.0),
            packageName = r.optString("packageName"),
            status = r.optString("userStatus"),
            flowTotal = r.optString("totalFlow"),
            flowMonth = r.optString("monthFlow"),
        )
    }

    fun card(j: JSONObject): CardInfo? {
        val v = j.optJSONObject("allPurposeCardVO") ?: return null
        val raw = v.optString("balance")
        val num = raw.toDoubleOrNull() ?: return null
        return CardInfo(num / CARD_DIVISOR, raw, v.optString("cardNo"))
    }

    fun lessons(j: JSONObject): Map<Int, Pair<String, String>> {
        val out = sortedMapOf<Int, Pair<String, String>>()
        val arr = j.optJSONArray("coursetimeList") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out[o.optInt("jcdm")] = hhmm(o.optString("startTime")) to hhmm(o.optString("endTime"))
        }
        return out
    }

    fun schedule(j: JSONObject, lessons: Map<Int, Pair<String, String>>): Schedule {
        val list = ArrayList<Course>()
        val arr = j.optJSONArray("courseList") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val jcFrom = o.optInt("skjc", 1)
            val jcTo = jcFrom + o.optInt("cxjc", 1) - 1
            list += Course(
                name = o.optString("kcmc"),
                teacher = o.optString("jgmc"),
                room = o.optString("jash").ifEmpty { o.optString("jxlH") },
                weekday = o.optInt("skxq", 1),
                jcFrom = jcFrom,
                jcTo = jcTo,
                start = lessons[jcFrom]?.first ?: "--:--",
                end = lessons[jcTo]?.second ?: "--:--",
                weeks = o.optString("skzc"),
            )
        }
        return Schedule(
            xn = j.optString("xn"),
            xq = j.optInt("xq"),
            describe = j.optString("des"),
            curWeek = j.optInt("curWeek", 1),
            totalWeek = j.optInt("totalWeek", 25),
            courses = list,
            lessons = lessons,
        )
    }

    /** "8:00" → "08:00"；服务端给的是单位数小时 */
    private fun hhmm(s: String): String {
        val p = s.split(":")
        if (p.size < 2) return s
        return "%02d:%02d".format(p[0].trim().toIntOrNull() ?: 0, p[1].trim().toIntOrNull() ?: 0)
    }
}
