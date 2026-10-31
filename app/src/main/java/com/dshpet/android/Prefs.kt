package com.dshpet.android

import android.content.Context

/** 极简本地偏好（尺寸 / 是否常驻），不依赖任何第三方库 */
object Prefs {
    private const val FILE = "dsh_pet"
    private const val KEY_SIZE = "size_dp"
    private const val KEY_RUNNING = "running"
    private const val KEY_KEEP_ALIVE = "keep_alive"
    private const val KEY_KEEP_ALIVE_ASKED = "keep_alive_asked"
    private const val KEY_SOUND = "sound_on"
    private const val KEY_PETS = "pet_count"
    private const val KEY_BUBBLE = "bubble_on"
    private const val KEY_EDGE_HIDE = "edge_hide"
    private const val KEY_ACC_USER = "acc_user"
    private const val KEY_ACC_PASS = "acc_pass"
    private const val KEY_UUID = "dev_uuid"
    private const val KEY_HUD = "hud_on"
    private const val KEY_HUD_X = "hud_x"
    private const val KEY_HUD_Y = "hud_y"
    private const val KEY_PET_IMAGE = "pet_image"

    const val DEFAULT_SIZE_DP = 150
    const val MIN_SIZE_DP = 80
    const val MAX_SIZE_DP = 360
    const val MIN_PETS = 1
    const val MAX_PETS = 3

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun sizeDp(ctx: Context): Int = sp(ctx).getInt(KEY_SIZE, DEFAULT_SIZE_DP)

    fun setSizeDp(ctx: Context, dp: Int) {
        sp(ctx).edit().putInt(KEY_SIZE, dp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)).apply()
    }

    fun running(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_RUNNING, false)

    fun setRunning(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_RUNNING, v).apply()
    }

    /** 点击音效开关，默认开启 */
    fun soundOn(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SOUND, true)

    fun setSoundOn(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_SOUND, v).apply()
    }

    /** 桌宠只数：同时放 1~3 只，默认 1 */
    fun petCount(ctx: Context): Int = sp(ctx).getInt(KEY_PETS, 1).coerceIn(MIN_PETS, MAX_PETS)

    fun setPetCount(ctx: Context, n: Int) {
        sp(ctx).edit().putInt(KEY_PETS, n.coerceIn(MIN_PETS, MAX_PETS)).apply()
    }

    /** 碎碎念（头顶台词气泡），默认开启 */
    fun bubbleOn(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_BUBBLE, true)

    fun setBubbleOn(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_BUBBLE, v).apply()
    }

    /** 贴边躲藏（松手吸附到屏幕边缘，闲置后只露半个身子），默认开启 */
    fun edgeHideOn(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_EDGE_HIDE, true)

    fun setEdgeHideOn(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_EDGE_HIDE, v).apply()
    }

    /** 后台保活：从「最近任务」隐藏 + 被系统回收后自动回来（默认开启） */
    fun keepAlive(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_KEEP_ALIVE, true)

    fun setKeepAlive(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_KEEP_ALIVE, v).apply()
    }

    /** 电池优化豁免是否已经自动问过一次（只问一次，不反复弹系统弹窗） */
    fun keepAliveAsked(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_KEEP_ALIVE_ASKED, false)

    fun setKeepAliveAsked(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_KEEP_ALIVE_ASKED, v).apply()
    }

    // ------------------------------------------------------------ 北工商：账号与桌面小卡

    /**
     * 学号 / 密码只落在本 App 的私有 SharedPreferences（别的应用读不到）。
     * 想再上一层保险，可以把这里换成 EncryptedSharedPreferences —— 但那要多引一个
     * androidx.security 依赖，跟本项目"零第三方依赖"的风格冲突，自用先这样。
     */
    fun accUser(ctx: Context): String = sp(ctx).getString(KEY_ACC_USER, "") ?: ""

    fun accPass(ctx: Context): String = sp(ctx).getString(KEY_ACC_PASS, "") ?: ""

    fun setAcc(ctx: Context, user: String, pass: String) {
        sp(ctx).edit().putString(KEY_ACC_USER, user).putString(KEY_ACC_PASS, pass).apply()
    }

    /** 设备号：登录接口要 uuId，稳定一个就够（换设备会被当成新设备） */
    fun uuid(ctx: Context): String {
        val cur = sp(ctx).getString(KEY_UUID, "") ?: ""
        if (cur.isNotEmpty()) return cur
        val gen = java.util.UUID.randomUUID().toString().replace("-", "")
        sp(ctx).edit().putString(KEY_UUID, gen).apply()
        return gen
    }

    /**
     * 悬浮小卡（HUD）开关，默认**关**：
     * 数据现在由「宠物头顶的常驻气泡 + 桌面挂件」承担，再加一块矩形卡片就重复了。
     * 想回到第一轮那种独立卡片，在这里打开即可。
     */
    fun hudOn(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_HUD, false)

    fun setHudOn(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_HUD, v).apply()
    }

    /** 小卡位置（拖动后记住；-1 表示还没拖过，用默认值） */
    fun hudX(ctx: Context, def: Int): Int = sp(ctx).getInt(KEY_HUD_X, def)

    fun hudY(ctx: Context, def: Int): Int = sp(ctx).getInt(KEY_HUD_Y, def)

    fun setHudPos(ctx: Context, x: Int, y: Int) {
        sp(ctx).edit().putInt(KEY_HUD_X, x).putInt(KEY_HUD_Y, y).apply()
    }

    /** 提醒冷却：key → 上次说这句话的时间戳 */
    fun alertAt(ctx: Context, key: String): Long = sp(ctx).getLong("alert_" + key, 0L)

    fun setAlertAt(ctx: Context, key: String, at: Long) {
        sp(ctx).edit().putLong("alert_" + key, at).apply()
    }

    /**
     * 形象模式：true = 用 res/drawable-nodpi 里的鲸鱼娘立绘 + GIF（跟 dsh 余额挂件同一套形象）；
     * false = 用上游 dsh-pet 的 106 段透明动画（需要跑过 tools/fetch-and-transcode）。
     */
    fun petImage(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_PET_IMAGE, true)

    fun setPetImage(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_PET_IMAGE, v).apply()
    }
}
