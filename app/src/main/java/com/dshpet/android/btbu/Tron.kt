package com.dshpet.android.btbu

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 掌上北工商（兰途 MobileCampus）请求信封 + 签名。
 *
 * 所有常量都是从 APK 的 assets/www/js/campus-basis-mcApp-2.0.5.js 里挖出来的，
 * 协议实测见 docs/03-接口逆向笔记.md。
 *
 * 零第三方依赖：只用 HttpURLConnection + org.json，和 dsh-pet-android 的风格一致。
 */
object Tron {

    const val HOST = "https://appyd.btbu.edu.cn:50001/"
    const val APP_KEY = "GiITvn"        // MC_LANTU_APPKEY
    const val SCHOOL_ID = 339           // MC_CAMPUS_ID

    const val PFX_BASE = "baseCampus/"                  // 登录 / 用户
    const val PFX_JW = "jwCampus/"                      // 教务（课表）
    const val PFX_INFO = "infoCampus/"                  // 网费 / 一卡通
    const val PFX_IFACE = "interface/rest/http/"        // 学校自研接口（jf/getNetUser.htm）
    const val PFX_OA = "oaCampus/"                      // 学期/学年（sc/getXnXq.do）

    /**
     * 信封：
     *   {"appKey":"GiITvn","param":"<JSON字符串>","time":<ms>,"secure":0,"sign":"<md5>"}
     * sign = md5( 按 key 升序 "k=v" 用 & 拼接 )
     *
     * 坑：param 参与签名的是**已经 stringify 过的字符串**，不是对象。
     *     按对象拼成 "[object Object]" 会被服务端回 鉴权失败。
     */
    fun envelope(paramJson: String, secure: Boolean = false, aes: Boolean = false): String {
        val m = sortedMapOf<String, String>(
            "appKey" to APP_KEY,
            "param" to paramJson,
            "time" to System.currentTimeMillis().toString(),
            "secure" to (if (secure) "1" else "0"),
        )
        if (aes) m["acceptSecure"] = "aes"
        if (secure) m["schoolId"] = SCHOOL_ID.toString()
        m["sign"] = md5(m.entries.joinToString("&") { "${it.key}=${it.value}" })
        val out = JSONObject()
        for ((k, v) in m) out.put(k, v)
        return out.toString()
    }

    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * POST 一次。返回解析后的 JSONObject（msgState == 1 表示成功，其它交给调用方判断）。
     * 必须在后台线程调用（本函数是阻塞的）。
     */
    fun post(path: String, param: JSONObject = JSONObject(), token: String? = null): JSONObject {
        val body = envelope(param.toString(), secure = false).toByteArray(Charsets.UTF_8)
        val conn = (URL(HOST + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            setRequestProperty("Accept-Language", "zh-CN")
            if (!token.isNullOrEmpty()) setRequestProperty("token", token)
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            val text = decodeSmart(raw)
            return runCatching { JSONObject(text) }
                .getOrElse { JSONObject().put("msgState", -1).put("msg", "HTTP $code 无法解析") }
        } finally {
            conn.disconnect()
        }
    }

    /** 服务端有时用 GBK 输出中文（JSON 结构本身是 ASCII），先 UTF-8 再退 GBK */
    private fun decodeSmart(b: ByteArray): String {
        if (b.isEmpty()) return ""
        return try {
            val s = String(b, Charsets.UTF_8)
            if (s.contains('\uFFFD')) String(b, charset("GBK")) else s
        } catch (t: Throwable) {
            String(b, Charsets.UTF_8)
        }
    }

    fun ok(j: JSONObject) = j.optInt("msgState", -1) == 1
}
