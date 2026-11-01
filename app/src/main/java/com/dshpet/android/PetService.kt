package com.dshpet.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.graphics.drawable.GradientDrawable
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar
import kotlin.math.abs
import kotlin.random.Random
import com.dshpet.android.btbu.BtbuAlerts
import com.dshpet.android.btbu.BtbuHud

/**
 * 桌宠前台服务：管住所有悬浮窗，并按帧驱动物理/动画。
 *
 * v1.6 起：
 *  - 可同时放 1~3 只（Prefs.petCount）
 *  - 头顶台词气泡（assets/lines.json，纯本地、不联网）
 *  - 贴边躲藏（松手吸附到屏幕边缘，闲置 12 秒缩进去只露半个身子）
 *
 * 所有步骤都包在 try/catch 里并写入 [Diag]：
 * 手机上闪退看不到 logcat，所以宁可降级成「界面上显示错误」也不要让进程死掉。
 */
class PetService : Service() {

    companion object {
        private const val TAG = "DshPetSvc"
        private const val CHANNEL_ID = "dsh_pet_overlay"
        private const val NOTIF_ID = 0x5E70

        const val ACTION_START = "com.dshpet.android.action.START"
        const val ACTION_STOP = "com.dshpet.android.action.STOP"
        const val ACTION_RELOAD = "com.dshpet.android.action.RELOAD"
        const val ACTION_LINES = "com.dshpet.android.action.LINES"
        const val ACTION_SYNC = "com.dshpet.android.action.SYNC"

        fun start(ctx: Context) {
            val i = Intent(ctx, PetService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, PetService::class.java).setAction(ACTION_STOP))
        }

        fun reload(ctx: Context) {
            ctx.startService(Intent(ctx, PetService::class.java).setAction(ACTION_RELOAD))
        }

        /** 台词被改了：宠物立刻换用新台词，并当场说一句（宠物没在跑就什么都不做） */
        fun linesChanged(ctx: Context) {
            if (!Prefs.running(ctx)) return
            try {
                ctx.startService(Intent(ctx, PetService::class.java).setAction(ACTION_LINES))
            } catch (t: Throwable) {
                Diag.record(ctx, "台词刷新失败", t)
            }
        }
    }

    private class Held(val pet: Pet, val view: View, val params: WindowManager.LayoutParams)

    private var wm: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val held = ArrayList<Held>()
    private var cfg: PetConfig? = null
    private var menuView: View? = null
    private var lines: Lines? = null

    // ---- 台词气泡 ----
    private var bubbleRoot: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubblePet: Pet? = null
    private var bubbleUntilMs = 0L
    private var bubbleX = Int.MIN_VALUE
    private var bubbleY = Int.MIN_VALUE

    // ---- 碎碎念节奏 ----
    private var lastBubbleMs = 0L
    private var lastInteractMs = 0L
    private var lastSpeechCheck = 0L
    private var lastPeriod = ""
    private var lastBtbuCheck = 0L

    // ---- 常驻数据气泡（贴在宠物头顶，显示余额 + 下一节课）----
    private var dbRoot: com.dshpet.android.btbu.ThoughtBubble? = null
    private var dbParams: WindowManager.LayoutParams? = null
    private var dbPet: Pet? = null
    private var dbTitle: TextView? = null
    private var dbBody: TextView? = null
    private var dbHead: TextView? = null
    private var dbPill: View? = null
    private var dbTimer: android.widget.Chronometer? = null
    private var dbSig = ""
    private var tapCount = 0
    private var tapWindowMs = 0L

    // ---- 北工商：桌面小卡（时间 + 余额 + 下一节课） ----
    private var hud: BtbuHud? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 兜底：即使 Application 没被自定义（或在某些机型上被跳过），也保证音效预载
        try {
            Sfx.init(this)
        } catch (t: Throwable) {
            Diag.record(this, "onCreate: 音效初始化失败", t)
        }
        try {
            lines = Lines.get(this)
        } catch (t: Throwable) {
            Diag.record(this, "onCreate: 台词库加载失败", t)
        }
        try {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        } catch (t: Throwable) {
            Diag.record(this, "onCreate: 取 WindowManager 失败", t)
        }
        try {
            ensureChannel()
        } catch (t: Throwable) {
            Diag.record(this, "onCreate: 建通知渠道失败", t)
        }
        try {
            goForeground()
        } catch (t: Throwable) {
            Diag.record(this, "onCreate: startForeground 失败（前台服务起不来）", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "(null)"
        Diag.record(this, "onStartCommand action=$action sdk=${Build.VERSION.SDK_INT}")
        return try {
            when (intent?.action) {
                ACTION_STOP -> {
                    Prefs.setRunning(this, false)
                    stopSelf()
                    return START_NOT_STICKY
                }
                ACTION_RELOAD -> {
                    // 台词库跟尺寸/只数一样，改完要重读一次才生效
                    try {
                        Lines.reload()
                        lines = Lines.get(this)
                    } catch (t: Throwable) {
                        Diag.record(this, "重载台词库失败", t)
                    }
                    teardown()
                    spawnAll(Prefs.sizeDp(this))
                }
                ACTION_SYNC -> {
                    try {
                        com.dshpet.android.btbu.BtbuSync.refreshAsync(this) { ok ->
                            updateDataBubble(true)
                            updateNotification()
                            if (ok) sayFrom("greet", force = false)
                        }
                    } catch (t: Throwable) {
                        Diag.record(this, "通知栏刷新失败", t)
                    }
                }
                ACTION_LINES -> {
                    // 台词刚被改过：换用新台词，并当场说一句让用户看到效果
                    try {
                        Lines.reload()
                        lines = Lines.get(this)
                        sayFrom("summon", force = true)
                    } catch (t: Throwable) {
                        Diag.record(this, "刷新台词失败", t)
                    }
                }
                else -> {
                    if (held.isEmpty()) spawnAll(Prefs.sizeDp(this))
                }
            }
            startTicker()
            // 保活开着用 STICKY：被系统回收后自动把宠物拉回来；关掉则保持 NOT_STICKY，避免崩溃循环
            if (Prefs.keepAlive(this)) START_STICKY else START_NOT_STICKY
        } catch (t: Throwable) {
            Diag.record(this, "onStartCommand 失败", t)
            Prefs.setRunning(this, false)
            stopSelf()
            START_NOT_STICKY
        }
    }

    // ------------------------------------------------------------ 前台通知

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val sync = PendingIntent.getService(
            this, 2,
            Intent(this, PetService::class.java).setAction(ACTION_SYNC),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_launcher)

        // 通知里直接给数据：锁屏 / 下拉栏就能看，不用点开小卡
        val st = try {
            com.dshpet.android.btbu.BtbuSync.state(this)
        } catch (t: Throwable) {
            null
        }
        val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date())
        val hasData = st != null && st.hasData
        val title = if (hasData) {
            clock + " · 第 " + st.week + " 周 · " + st.balanceLine()
        } else {
            getString(R.string.notif_title)
        }
        val text = if (hasData) {
            st.nextLine()
        } else if (com.dshpet.android.btbu.BtbuSync.configured(this)) {
            "点这里同步"
        } else {
            "点这里填学号，开始同步"
        }
        val big = if (hasData) {
            val net = st.net
            val flow = if (net != null) "流量 总余 " + net.flowTotal + " · 本月余 " + net.flowMonth else ""
            st.nextLine() + "\n" + st.balanceLine() + "\n" + flow + "\n" + st.term
        } else {
            text
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(icon, "刷新", sync).build())
            .addAction(Notification.Action.Builder(icon, getString(R.string.notif_stop), stop).build())
            .build()
    }

    /** 数据变了 / 到点了：把通知栏重画一遍（同一个 id，不会刷出多条） */
    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (t: Throwable) {
            Diag.record(this, "更新通知失败", t)
        }
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ------------------------------------------------------------ 悬浮窗

    private fun spawnAll(sizeDp: Int) {
        val w = wm
        if (w == null) {
            Diag.record(this, "spawnAll: WindowManager 为空")
            return
        }
        val can = Settings.canDrawOverlays(this)
        Diag.record(this, "spawnAll: 悬浮窗权限=$can sizeDp=$sizeDp")
        if (!can) {
            Prefs.setRunning(this, false)
            return
        }

        val imgMode = Prefs.petImage(this)
        val conf = try {
            PetConfig.loadBest(this, imgMode)
        } catch (t: Throwable) {
            Diag.record(this, "spawnAll: 配置加载失败（pet-config.json）", t)
            return
        }

        val dm = resources.displayMetrics
        val count = Prefs.petCount(this).coerceIn(Prefs.MIN_PETS, Prefs.MAX_PETS)
        Diag.record(
            this,
            "spawnAll: 屏幕 ${dm.widthPixels}x${dm.heightPixels} density=${dm.density} 配置宠物数=${conf.pets.size} 要放=$count",
        )
        cfg = conf
        lastInteractMs = SystemClock.uptimeMillis()

        val baseSpec = conf.pets.first()
        val suffixes = listOf("", "二号", "三号")

        for (i in 0 until count) {
            val spec = if (i == 0) baseSpec else baseSpec.copy(
                id = baseSpec.id + "-" + (i + 1),
                name = baseSpec.name + (suffixes.getOrNull(i) ?: (i + 1).toString()),
            )
            try {
                var paramsRef: WindowManager.LayoutParams? = null
                var viewRef: View? = null

                val pet = Pet(this, conf, spec, aspect = if (imgMode) 1.0 else 9.0 / 16.0) { x, y, ww, hh ->
                    val p = paramsRef
                    val v = viewRef
                    if (p != null && v != null) {
                        p.x = x
                        p.y = y
                        p.width = ww
                        p.height = hh
                        try {
                            w.updateViewLayout(v, p)
                        } catch (_: Throwable) {
                        }
                    }
                }
                pet.applyScale(sizeDp)
                pet.allowEdgeHide = Prefs.edgeHideOn(this)
                pet.onLongPress = { showActionMenu(pet) }
                pet.onTap = { onPetTapped(pet) }
                pet.onThrown = { onPetThrown(pet) }
                pet.onSettled = { onPetSettled(pet) }
                pet.onHiddenChange = { hidden -> onPetHiddenChange(pet, hidden) }

                val lp = WindowManager.LayoutParams(
                    pet.sizePx,
                    pet.heightPx,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                )
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = pet.x.toInt()
                lp.y = pet.y.toInt()

                paramsRef = lp
                viewRef = pet.view

                Diag.record(this, "addView: ${lp.width}x${lp.height} @ (${lp.x},${lp.y})")
                w.addView(pet.view, lp)
                pet.refreshWindow()
                pet.start()
                // 第二、三只错开放：一上一下，避免叠在一起，也别被推出屏幕
                if (i > 0) {
                    val up = if (i % 2 == 1) -1.0 else 1.0
                    pet.shiftByVisible(
                        up * 0.12 * pet.sizePx,
                        up * 0.55 * pet.heightPx,
                    )
                }
                held.add(Held(pet, pet.view, lp))
                Diag.record(this, "宠物已创建: ${spec.name}")
            } catch (t: Throwable) {
                Diag.record(this, "创建宠物失败（${spec.name}）", t)
            }
        }
        Diag.record(this, "spawnAll 完成，成功 ${held.size} 只")

        // 数据小卡：宠物搭好之后挂上去（没开开关就什么都不做）
        try {
            hud?.release()
            hud = null
            val w = wm
            if (w != null && Prefs.hudOn(this)) {
                hud = BtbuHud(this, w).also { it.show() }
            }
        } catch (t: Throwable) {
            Diag.record(this, "创建数据小卡失败", t)
        }

        // 常驻数据气泡（余额 + 下一节课）
        try {
            dismissDataBubble()
            ensureDataBubble()
            updateDataBubble(true)
        } catch (t: Throwable) {
            Diag.record(this, "数据气泡创建失败", t)
        }

        // 打个招呼
        handler.postDelayed({ sayFrom("greet", force = true) }, 1600L)
    }

    private fun teardown() {
        handler.removeCallbacks(tickRunnable)
        dismissMenu()
        dismissBubble()
        dismissDataBubble()
        try {
            hud?.release()
        } catch (t: Throwable) {
            Diag.record(this, "释放数据小卡失败", t)
        }
        hud = null
        val w = wm
        for (h in held) {
            try {
                h.pet.release()
            } catch (_: Throwable) {
            }
            try {
                w?.removeView(h.view)
            } catch (_: Throwable) {
            }
        }
        held.clear()
    }

    // ------------------------------------------------------------ 常驻数据气泡

    private fun dpPx(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * 常驻气泡：dsh 余额挂件同款「手绘思考气泡」。
     *
     * 白底 + 深藏青手绘描边椭圆（ThoughtBubble 画的），下面串 3 个圆点当尾巴。
     * 三行文字照参考图的比例排版：
     *   标题（北工商 · 第 N 周，小号粗体）
     *   大数字（网 15.00  卡 25.80）
     *   副行（下一节课，浅蓝灰）
     * 底部一条绿色倒计时：正在上课数到下课，否则数到下一节（Chronometer，系统驱动，零耗电）。
     * 点它 → 打开整周课表。和台词气泡是两个独立窗口，互不打断。
     */
    /**
     * 常驻气泡：dsh 鲸鱼挂件同款「手绘思考气泡」（几何见 res/drawable/btbu_bubble.xml）。
     *
     * 版式照上游：标题（北工商 · 第 N 周）/ 大数字（网 15.00  卡 25.80）/ 副行（下一节课）
     * + 底部绿色倒计时药丸（正在上课数到下课，否则数到下一节；Chronometer 系统驱动，零耗电）。
     * 字号随气泡宽度按上游 u 比例联动，ThoughtBubble 会按内容自动缩字，气泡本体永不变形。
     * 点它 → 打开整周课表。和台词气泡是两个独立窗口，互不打断。
     */
    private fun ensureDataBubble() {
        val w = wm ?: return
        if (dbRoot != null) return
        val pet = held.firstOrNull()?.pet ?: return
        val d = resources.displayMetrics.density

        // 字号/颜色都由 ThoughtBubble.bind() 统一管，这里只负责搭结构
        val head = TextView(this)
        val main = TextView(this).apply { maxLines = 2 }
        val sub = TextView(this).apply { maxLines = 2 }
        val chip = TextView(this).apply { text = "课" }
        val timer = Chronometer(this).apply { format = "距上课  %s" }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, (6 * d).toInt(), 0, 0)
            addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER_VERTICAL; rightMargin = (4 * d).toInt() },
            )
            addView(
                timer,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER_VERTICAL },
            )
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(head)
            addView(main)
            addView(sub)
            addView(pill)
        }
        dbHead = head
        dbTitle = main
        dbBody = sub
        dbPill = pill
        dbTimer = timer

        val box = com.dshpet.android.btbu.ThoughtBubble(this).apply {
            addView(
                col,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            bind(head, main, sub)
            bindCountdown(chip, timer)
            setOnClickListener {
                try {
                    startActivity(
                        Intent(this@PetService, com.dshpet.android.btbu.ScheduleActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (t: Throwable) {
                    Diag.record(this@PetService, "打开课表失败", t)
                }
            }
        }

        val bw = fittedDataBubbleWidth(pet)
        box.measure(
            View.MeasureSpec.makeMeasureSpec(bw, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val bh = box.measuredHeight.coerceAtLeast(dpPx(40))
        val lp = WindowManager.LayoutParams(
            bw, bh,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        box.mirrorBubble = pet.mirrored
        placeBubble(lp, bw, bh, pet, box.inkCenterX, com.dshpet.android.btbu.ThoughtBubble.TAIL_TIP)
        try {
            w.addView(box, lp)
            dbRoot = box
            dbParams = lp
            dbPet = pet
        } catch (t: Throwable) {
            Diag.record(this, "数据气泡创建失败", t)
            dbRoot = null
            dbParams = null
        }
    }

    /**
     * 气泡宽度跟着宠物大小走（上游是宽 1026 : 高 700 的固定比例，所以只定宽），再夹在屏幕内。
     * 1.75 这个系数是照真机截图校的：0.95 时气泡只有 189px，"网 15.00  卡 25.80" 会被压成 9px 的字。
     */
    private fun bubbleWidthPx(pet: Pet): Int {
        val screenW = resources.displayMetrics.widthPixels
        val want = (pet.sizePx * 1.75f).toInt()
        return minOf(want, (screenW * 0.62f).toInt()).coerceAtLeast(dpPx(220))
    }

    /** 状态栏/顶部安全区：气泡不允许越过这条线 */
    private fun topInsetPx(): Int = dpPx(28)

    /** 气泡「尾巴尖高度 / 气泡宽度」：700/1026 × 673/700 ≈ 0.656 */
    private val bubbleTipPerWidth =
        (700f / 1026f) * com.dshpet.android.btbu.ThoughtBubble.TAIL_TIP

    /**
     * 按「头顶还剩多少地方」算气泡宽度 —— 气泡和宠物的相对位置要固定，
     * 所以宁可让气泡变小，也不把它翻到宠物下面去。
     *
     * 三段行为（她默认在右下，头顶空间充足 → 满尺寸）：
     *  · 头顶够 → 满尺寸；
     *  · 头顶不够 → 等比缩小，最小到 62%（再小字就看不清了）；
     *  · 贴死顶部 → 用最小尺寸 + 尾巴对不上也只在安全区夹住，永不翻到脚底。
     */
    private fun fittedDataBubbleWidth(pet: Pet): Int {
        val want = bubbleWidthPx(pet)
        val minW = (want * 0.62f).toInt()
        val room = pet.y.toInt() - dpPx(6) - topInsetPx()
        if (room <= 0) return minW
        val maxW = (room / bubbleTipPerWidth).toInt()
        return maxOf(minW, minOf(want, maxW))
    }

    private fun updateDataBubble(force: Boolean = false) {
        val st = try {
            com.dshpet.android.btbu.BtbuSync.state(this)
        } catch (t: Throwable) {
            null
        }
        val hasData = st != null && st.hasData

        // 倒计时是秒级变化的，不能进 sig 去重 —— 每次进来都重新上弦
        if (hasData) {
            dbHead?.text = st.headLine()
            val remain = st.countdownRemainMs()
            if (remain != null && remain > 0) {
                dbPill?.visibility = View.VISIBLE
                dbTimer?.let { t ->
                    t.format = if (st.current != null) "距下课  %s" else "距上课  %s"
                    t.base = android.os.SystemClock.elapsedRealtime() + remain
                    t.isCountDown = true
                    t.start()
                }
            } else {
                dbPill?.visibility = View.GONE
                dbTimer?.stop()
            }
        } else {
            dbHead?.text = "北工商桌宠"
            dbPill?.visibility = View.GONE
            dbTimer?.stop()
        }

        val sig = if (hasData) {
            st.week.toString() + "|" + st.balanceLine() + "|" + st.nextLine()
        } else {
            "empty"
        }
        if (!force && sig == dbSig) return
        dbSig = sig
        // 大数字拆成两行（"网 15.00" / "卡 25.80"）：一行 15 个字塞进椭圆必然被压到 9px
        dbTitle?.text = if (hasData) st.balanceLine().replace("  ", "\n") else "未登录"
        dbBody?.text = if (hasData) st.nextLine() else "点气泡填学号"

        val box = dbRoot ?: return
        val pet = dbPet ?: return
        val lp = dbParams ?: return
        val bw = fittedDataBubbleWidth(pet)
        box.measure(
            View.MeasureSpec.makeMeasureSpec(bw, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val bh = box.measuredHeight.coerceAtLeast(dpPx(40))
        lp.width = bw
        lp.height = bh
        box.mirrorBubble = pet.mirrored
        placeBubble(lp, bw, bh, pet, box.inkCenterX, com.dshpet.android.btbu.ThoughtBubble.TAIL_TIP)
        try {
            wm?.updateViewLayout(box, lp)
        } catch (_: Throwable) {
        }
    }

    private fun followDataBubble() {
        val box = dbRoot ?: return
        val lp = dbParams ?: return
        val pet = dbPet ?: return
        val oldX = lp.x
        val oldY = lp.y
        val oldW = lp.width
        val oldH = lp.height
        // 头顶剩多少地方决定气泡多大：变了就重排一次。
        // 量化到 8dp 台阶，否则拖动时每帧都要 measure（ThoughtBubble 里还有 3 轮缩字迭代）。
        val fitted = fittedDataBubbleWidth(pet)
        if (abs(fitted - lp.width) >= dpPx(8)) {
            box.measure(
                View.MeasureSpec.makeMeasureSpec(fitted, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            lp.width = fitted
            lp.height = box.measuredHeight.coerceAtLeast(dpPx(40))
        }
        box.mirrorBubble = pet.mirrored
        placeBubble(lp, box.measuredWidth, box.measuredHeight, pet, box.inkCenterX, com.dshpet.android.btbu.ThoughtBubble.TAIL_TIP)
        if (lp.x == oldX && lp.y == oldY && lp.width == oldW && lp.height == oldH) return
        try {
            wm?.updateViewLayout(box, lp)
        } catch (_: Throwable) {
        }
    }

    private fun dismissDataBubble() {
        val box = dbRoot ?: return
        try {
            wm?.removeView(box)
        } catch (_: Throwable) {
        }
        dbRoot = null
        dbParams = null
        dbPet = null
        dbHead = null
        dbTitle = null
        dbBody = null
        dbPill = null
        dbTimer = null
        dbSig = ""
    }

    // ------------------------------------------------------------ 台词气泡

    /** 小三角，让气泡看起来是「从她头顶冒出来的」 */
    private class BubbleTail(ctx: Context) : View(ctx) {
        private val path = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF7FFFFFF.toInt()
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            path.reset()
            path.moveTo(0f, 0f)
            path.lineTo(width.toFloat(), 0f)
            path.lineTo(width / 2f, height.toFloat())
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    private fun say(pet: Pet, text: String) {
        val w = wm ?: return
        dismissBubble()
        // 台词气泡和数据气泡锚点相同，会完全叠住：说台词时先把数据气泡藏起来
        try {
            dbRoot?.visibility = View.GONE
        } catch (_: Throwable) {
        }
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(0xFF16223B.toInt())
            textSize = 14f
            includeFontPadding = false
            setPadding((14 * d).toInt(), (9 * d).toInt(), (14 * d).toInt(), (9 * d).toInt())
            maxWidth = (232 * d).toInt()
            background = GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(0xF7FFFFFF.toInt())
                setStroke((1.5f * d).toInt(), 0x664E6BFF.toInt())
            }
        }
        box.addView(
            tv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        box.addView(
            BubbleTail(this),
            LinearLayout.LayoutParams((14 * d).toInt(), (8 * d).toInt()),
        )
        // 先量一次，才能算出「贴在她头顶」的位置；窗口直接用量出来的固定尺寸，
        // 交给系统按 WRAP_CONTENT 再量一次时，部分 ROM 会量成一条细缝（文字就看不见了）。
        val maxW = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        box.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val bw = box.measuredWidth.coerceAtLeast((48 * d).toInt())
        val bh = box.measuredHeight.coerceAtLeast((28 * d).toInt())

        val lp = WindowManager.LayoutParams(
            bw,
            bh,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        placeBubble(lp, bw, bh, pet)
        Diag.record(this, "气泡 ${bw}x${bh} @(${lp.x},${lp.y}) 「$text」")

        try {
            w.addView(box, lp)
            val now = SystemClock.uptimeMillis()
            bubbleRoot = box
            bubbleParams = lp
            bubblePet = pet
            bubbleUntilMs = now + 3600L
            lastBubbleMs = now
        } catch (t: Throwable) {
            Diag.record(this, "气泡创建失败", t)
            bubbleRoot = null
            bubbleParams = null
            bubblePet = null
        }
    }

    /**
     * 把气泡窗口摆到宠物「看得见的那半个身子」的正上方。
     *
     * 两个错位源（以前就是它们让气泡看着不对齐）：
     *  ① 宠物盒中心 ≠ 立绘可见中心：素材四周留白不对称，墨迹中心实测 0.536，
     *     镜像后变 0.464 —— 所以必须问 Pet.visibleCenterX（内部已处理镜像），
     *     不能自己算 sizePx/2。
     *  ② 气泡窗口中心 ≠ 椭圆可见中心：视图 1026 宽里椭圆只占 x 72..836（含描边），
     *     可见中心在 44.25%，右边 20% 是空边。按 50% 摆就永远偏左。
     *     inkCx 由气泡自己给（镜像后为 1-0.4425）。
     *
     * tipRatio：气泡底边（尾巴尖）在窗口高度里的比例 —— 让「尾巴尖」而不是整个窗口顶着头顶。
     */
    private fun placeBubble(
        lp: WindowManager.LayoutParams,
        bw: Int,
        bh: Int,
        pet: Pet,
        inkCx: Float = 0.5f,
        tipRatio: Float = 1f,
    ) {
        if (bw <= 0 || bh <= 0) return
        val dm = resources.displayMetrics
        val d = dm.density
        val pad = (8 * d).toInt()
        var bx = (pet.visibleCenterX - inkCx * bw).toInt()
        val maxX = dm.widthPixels - bw - pad
        bx = bx.coerceIn(pad, maxX.coerceAtLeast(pad))
        // 相对位置固定：尾巴尖永远在她头顶上方 6dp，**永不翻到脚底**。
        // 头顶顶到安全区时就把气泡夹在安全区里（尾巴会压一点点她头顶）——
        // 这也比「气泡跑到下面、看着跟她没关系」好。
        val gap = (6 * d).toInt()
        var by = pet.y.toInt() - (bh * tipRatio).toInt() - gap
        if (by < topInsetPx()) by = topInsetPx()
        lp.x = bx
        lp.y = by
    }

    private fun followBubble() {
        val v = bubbleRoot ?: return
        val lp = bubbleParams ?: return
        val pet = bubblePet ?: return
        if (SystemClock.uptimeMillis() > bubbleUntilMs) {
            dismissBubble()
            return
        }
        val oldX = lp.x
        val oldY = lp.y
        placeBubble(lp, v.measuredWidth, v.measuredHeight, pet)
        if (lp.x == oldX && lp.y == oldY) return
        try {
            wm?.updateViewLayout(v, lp)
        } catch (_: Throwable) {
        }
    }

    private fun dismissBubble() {
        try {
            dbRoot?.visibility = View.VISIBLE
        } catch (_: Throwable) {
        }
        val v = bubbleRoot ?: return
        bubbleRoot = null
        bubbleParams = null
        bubblePet = null
        try {
            wm?.removeView(v)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ 碎碎念触发

    /** 说一句 / 说某类情境的一句。force 只跳过冷却，不跳过总开关（summon 除外）。 */
    private fun sayFrom(category: String, force: Boolean = false) {
        try {
            if (category != "summon" && !Prefs.bubbleOn(this)) return
            val l = lines ?: return
            val pet = bubblePet ?: held.firstOrNull()?.pet ?: return
            val now = SystemClock.uptimeMillis()
            if (!force && now - lastBubbleMs < 20_000L) return
            val text = l.pick(category) ?: return
            say(pet, text)
        } catch (t: Throwable) {
            Diag.record(this, "sayFrom($category) 失败", t)
        }
    }

    private fun onPetTapped(pet: Pet) {
        val now = SystemClock.uptimeMillis()
        lastInteractMs = now
        if (now - tapWindowMs > 6000L) {
            tapWindowMs = now
            tapCount = 0
        }
        tapCount++
        // 连点 5 下：说一句「被戳烦了」
        if (tapCount >= 5) {
            tapCount = 0
            sayFrom("tapped")
        }
    }

    private fun onPetThrown(pet: Pet) {
        lastInteractMs = SystemClock.uptimeMillis()
        if (Random.nextDouble() < 0.45) sayFrom("drag")
    }

    private fun onPetSettled(pet: Pet) {
        lastInteractMs = SystemClock.uptimeMillis()
    }

    private fun onPetHiddenChange(pet: Pet, hidden: Boolean) {
        if (hidden) {
            handler.postDelayed({
                // 缩进去之后再开口，避免气泡跟着一起被藏掉半截
                if (bubbleRoot == null) sayFrom("edge")
            }, 700L)
        }
    }

    private fun periodOf(hour: Int): String? = when {
        hour in 5..10 -> "morning"
        hour in 11..13 -> "noon"
        hour in 19..22 -> "night"
        hour == 23 || hour in 0..4 -> "late"
        else -> null
    }

    /** 每秒跑一次：时段问候 + 闲置吐槽 */
    /** 让宠物对北工商数据做反应：一次只挑一条，且每条都有冷却，避免刷屏 */
    private fun tickBtbu() {
        val pet = held.firstOrNull()?.pet ?: return
        val alert = BtbuAlerts.pick(this) ?: return
        try {
            say(pet, alert.text)
            BtbuAlerts.mark(this, alert.key)
        } catch (t: Throwable) {
            Diag.record(this, "数据提醒失败", t)
        }
    }

    private fun tickSpeech(now: Long) {
        if (held.isEmpty()) return

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val period = periodOf(hour)
        if (period != null && period != lastPeriod) {
            lastPeriod = period
            handler.postDelayed({ sayFrom(period) }, 4000L + Random.nextLong(6000L))
        }

        val idle = now - lastInteractMs
        if (idle > 90_000L && now - lastBubbleMs > 120_000L) {
            // 一小时以上没人理：换成更小声的「busy」嘟囔
            sayFrom(if (idle > 600_000L) "busy" else "idle")
        }
    }

    // ------------------------------------------------------------ 长按动作菜单

    private fun dismissMenu() {
        val v = menuView ?: return
        menuView = null
        try {
            wm?.removeView(v)
        } catch (_: Throwable) {
        }
    }

    /**
     * 长按宠物弹出的动作菜单 —— 对应电脑版的「右键 → 动作 → 分类 → 点播动画」。
     * 手机没有右键，所以落在长按上；分类默认折叠，点标题展开。
     */
    private fun showActionMenu(pet: Pet) {
        val w = wm ?: return
        val conf = cfg ?: return
        dismissMenu()

        val dm = resources.displayMetrics
        val d = dm.density

        val root = FrameLayout(this)
        root.setBackgroundColor(0xB3000000.toInt())
        root.setOnClickListener { dismissMenu() }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 18f * d
                setColor(0xFF161A21.toInt())
                setStroke((1 * d).toInt(), 0xFF2C3442.toInt())
            }
            setOnClickListener { /* 吃掉点击，避免穿透到 scrim */ }
        }
        root.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (dm.heightPixels * 0.66f).toInt(),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { setMargins((12 * d).toInt(), 0, (12 * d).toInt(), (12 * d).toInt()) },
        )

        panel.addView(
            TextView(this).apply {
                text = getString(R.string.menu_title) + " · " + pet.spec.name
                setTextColor(0xFFEAF1F8.toInt())
                textSize = 16f
                setPadding((4 * d).toInt(), 0, (4 * d).toInt(), (8 * d).toInt())
            },
        )

        val scroll = ScrollView(this)
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)

        fun addRow(text: String, onClick: () -> Unit) {
            val tv = TextView(this).apply {
                this.text = text
                setTextColor(0xFFCFDCEA.toInt())
                textSize = 14f
                setPadding((8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt())
                isClickable = true
                setOnClickListener { onClick() }
            }
            list.addView(
                tv,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }

        fun addSection(title: String, slugs: List<String>) {
            if (slugs.isEmpty()) return
            val head = TextView(this).apply {
                this.text = "▸ $title (${slugs.size})"
                setTextColor(0xFF8FB6E8.toInt())
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding((6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
                isClickable = true
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            list.addView(head)
            list.addView(body)
            for (slug in slugs) {
                val tv = TextView(this).apply {
                    text = conf.nameOf(slug)
                    setTextColor(0xFFCFDCEA.toInt())
                    textSize = 14f
                    setPadding((24 * d).toInt(), (9 * d).toInt(), (8 * d).toInt(), (9 * d).toInt())
                    isClickable = true
                    setOnClickListener {
                        dismissMenu()
                        pet.playAction(slug)
                    }
                }
                body.addView(
                    tv,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
                )
            }
            head.setOnClickListener {
                val opening = body.visibility != View.VISIBLE
                body.visibility = if (opening) View.VISIBLE else View.GONE
                head.text = (if (opening) "▾ " else "▸ ") + "$title (${slugs.size})"
            }
        }

        // 「让她说句话」：无论碎碎念开关如何都能用，顺手播一段带文字的动作
        addRow(getString(R.string.menu_speak)) {
            dismissMenu()
            lastInteractMs = SystemClock.uptimeMillis()
            sayFrom("summon", force = true)
            conf.categories.firstOrNull { it.id.contains("文字") }
                ?.actions?.randomOrNull()
                ?.let { slug -> pet.playAction(slug) }
        }
        addRow("↺ 回到初始位置") {
            dismissMenu()
            pet.resetPosition()
        }
        addRow("⇄ 翻转朝向") {
            dismissMenu()
            pet.flip()
        }
        addRow("✕ 收起来") {
            dismissMenu()
            Prefs.setRunning(this, false)
            stopSelf()
        }

        addSection("待机", conf.idle)
        addSection("转向", conf.turn)
        addSection("拖拽", conf.drag)
        addSection("点击回应", conf.clicks)
        addSection("移动", conf.moves.map { it.slug })
        for (cat in conf.categories) addSection("随机动作 · ${cat.id}", cat.actions)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        try {
            w.addView(root, lp)
            menuView = root
        } catch (t: Throwable) {
            Diag.record(this, "动作菜单创建失败", t)
        }
    }

    // ------------------------------------------------------------ 帧循环

    private val tickRunnable = object : Runnable {
        override fun run() {
            var active = false
            for (h in held) {
                try {
                    if (h.pet.tick()) active = true
                } catch (t: Throwable) {
                    Diag.record(this@PetService, "tick 异常", t)
                }
            }
            try {
                followBubble()
            } catch (t: Throwable) {
                Diag.record(this@PetService, "followBubble 异常", t)
            }
            try {
                followDataBubble()
            } catch (t: Throwable) {
                Diag.record(this@PetService, "followDataBubble 异常", t)
            }
            val n = SystemClock.uptimeMillis()
            if (n - lastSpeechCheck > 1000L) {
                lastSpeechCheck = n
                try {
                    tickSpeech(n)
                } catch (t: Throwable) {
                    Diag.record(this@PetService, "tickSpeech 异常", t)
                }
            }
            // 每 60 秒看一次"有没有值得对真实数据反应的事"（余额、限速、快上课）
            if (n - lastBtbuCheck > 60_000L) {
                lastBtbuCheck = n
                try {
                    tickBtbu()
                    updateDataBubble()
                    updateNotification()
                } catch (t: Throwable) {
                    Diag.record(this@PetService, "tickBtbu 异常", t)
                }
            }
            handler.postDelayed(this, if (active) 16L else 66L)
        }
    }

    private fun startTicker() {
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    override fun onDestroy() {
        try {
            teardown()
        } catch (t: Throwable) {
            Log.w(TAG, "onDestroy: ${t.message}")
        }
        super.onDestroy()
    }
}
