package com.dshpet.android.btbu

import org.json.JSONArray
import org.json.JSONObject

/**
 * 掌上北工商的三个数据源。全部同步阻塞，调用方放后台线程。
 *
 * 实测可用的最小链路：
 *   1) POST baseCampus/login/login.do   {campusType:1, userName, password, uuId, schoolId:339}
 *      → msgState=1 + token（**数组**，请求头里要用 "_" 拼起来）
 *   2) 之后所有请求带 header  token: xxxxx_yyyyy
 *
 * 无验证码、公网可达、证书是 GlobalSign 签的 *.btbu.edu.cn（有效期到 2027-02），
 * 所以**不需要** TrustManager 例外，也不需要 SSL Pinning 绕过。
 */
object BtbuApi {

    class Login(val token: String, val userName: String, val realName: String, val userId: Long)

    /** 登录失败抛 [BtbuException]，带上服务端原话（"用户名或密码错误" 之类） */
    class BtbuException(val msgState: Int, val msg: String) : Exception(msg)

    fun login(userName: String, password: String, uuid: String): Login {
        val param = JSONObject()
            .put("campusType", 1)
            .put("userName", userName)
            .put("password", password)      // 实测：这台部署接受明文 param（走 HTTPS）
            .put("uuId", uuid)
            .put("schoolId", Tron.SCHOOL_ID)
        val j = Tron.post(Tron.PFX_BASE + "login/login.do", param)
        if (!Tron.ok(j)) throw BtbuException(j.optInt("msgState", -1), j.optString("msg"))

        val ul = j.optJSONObject("userLoginInfo") ?: JSONObject()
        val ub = j.optJSONObject("userBaseInfo") ?: JSONObject()
        return Login(
            token = tokenOf(j),
            userName = ul.optString("userName").ifEmpty { userName },
            realName = ub.optString("realName").ifEmpty { ub.optString("nickName") },
            userId = ul.optLong("userId"),
        )
    }

    /** token 可能是字符串，也可能是 ["8804ec6792","59857900b8c927f0b8db0e"] —— 后者要 "_" 拼接 */
    private fun tokenOf(j: JSONObject): String {
        val v = j.opt("token")
        return when (v) {
            is JSONArray -> (0 until v.length()).map { v.optString(it) }.joinToString("_")
            null -> ""
            else -> v.toString()
        }
    }

    /**
     * 拉课表。
     *
     * 两个坑（都踩过）：
     *  ① 必须带 `curTime`：只传 curWeek 会回 msgState=2「获取不到课表数据」
     *  ② **`curWeek` 不能写死 1**：教务是"按周给课表"的 —— 你问第 1 周的课表，
     *     它给的就是第 1 周的安排（实验课/换教室这类常常第 9 周才出现，就问不到）。
     *     所以这里要传"我们认为的当前周"，而且下面再按服务器回的 curWeek 校准一次。
     */
    fun schedule(token: String, week: Int = 1, anchorMs: Long = System.currentTimeMillis()): Schedule {
        val w = week.coerceAtLeast(1)
        // 掌上北工商的 `schoolTimetable` 是这么发的（见 assets/www/js/parts/schoolTimetable/main.js）：
        //   { curWeek, curTime, week?, stuYear?, stuTerm? }
        // `curTime` 是**正在看的那一周里的某一天**（它内部把 date 加减周数再取时间戳），
        // 教务就是靠它决定"给你哪一周的安排" —— 这就是为什么我们传 now 永远只能拿到本周、
        // 拿不到第 9 周才出现的「模电实验 6-7 节 工3-202（实验室）」。
        val p = JSONObject()
            .put("curWeek", w)
            .put("curTime", anchorMs)
            .put("week", w)
        val j = Tron.post(Tron.PFX_JW + "course/getCourse.do", p, token)
        if (!Tron.ok(j)) throw BtbuException(j.optInt("msgState", -1), j.optString("msg"))

        val les = Tron.post(Tron.PFX_JW + "course/getCourseTimeConfig.do", JSONObject(), token)
        val lessons = if (Tron.ok(les)) BtbuParse.lessons(les) else emptyMap()
        return BtbuParse.schedule(j, lessons)
    }

    /**
     * 两段式：先按"我们算的第 N 周"问；如果服务器回的 curWeek 和我们问的不一样，
     * 就按服务器给的那个周再问一次，并以第二次的结果为准。
     * 这样拿到的永远是**教务认为的当前周**的课表，和掌上北工商里看到的一致。
     */
    fun scheduleCalibrated(token: String, wantWeek: Int, anchorMs: Long = System.currentTimeMillis()): Schedule {
        val s = schedule(token, wantWeek, anchorMs)
        if (s.curWeek != wantWeek) {
            // 注意：服务器回的 curWeek 是"它认为的当前周"，**不代表你这次拿到的是哪一周的安排**
            //（按周给数据的部署里它恒等于当前周）。所以这里只记录，不再拿它覆盖请求。
            android.util.Log.i(
                "BtbuApi",
                "课表：请求第 $wantWeek 周 → 服务器 curWeek=${s.curWeek}，行数=${s.courses.size}",
            )
        }
        return s
    }

    /**
     * 学期开始日期（**周次的权威来源**）。
     *
     * `oaCampus/sc/getXnXq.do` 返回 xnxqList[]，每条有 xn / xq / startTime（毫秒）。
     * 实测 2026-2027 第 1 学期 startTime = 1788710400000 = 北京时间 2026-09-07 00:00（周一）。
     * 拿它算周次比信 curWeek 靠谱：curWeek 会回 0、会被脏数据改写，学期开始日期不会。
     */
    fun termStart(token: String, xn: String, xq: Int): Long? {
        val j = Tron.post(Tron.PFX_OA + "sc/getXnXq.do", JSONObject(), token)
        if (!Tron.ok(j)) return null
        return BtbuParse.termStartDay(j, xn, xq)
    }

    fun net(token: String): NetInfo? {
        val j = Tron.post(Tron.PFX_IFACE + "jf/getNetUser.htm", JSONObject(), token)
        return if (Tron.ok(j)) BtbuParse.net(j) else null
    }

    fun card(token: String): CardInfo? {
        val j = Tron.post(Tron.PFX_INFO + "playCampus/getAllPurposeCard.do", JSONObject(), token)
        return if (Tron.ok(j)) BtbuParse.card(j) else null
    }
}
