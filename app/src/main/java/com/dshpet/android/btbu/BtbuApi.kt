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

    fun schedule(token: String): Schedule {
        // 课表**必须带 curTime**，只传 curWeek 会回 msgState=2「获取不到课表数据」
        val p = JSONObject().put("curWeek", 1).put("curTime", System.currentTimeMillis())
        val j = Tron.post(Tron.PFX_JW + "course/getCourse.do", p, token)
        if (!Tron.ok(j)) throw BtbuException(j.optInt("msgState", -1), j.optString("msg"))

        val les = Tron.post(Tron.PFX_JW + "course/getCourseTimeConfig.do", JSONObject(), token)
        val lessons = if (Tron.ok(les)) BtbuParse.lessons(les) else emptyMap()
        return BtbuParse.schedule(j, lessons)
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
