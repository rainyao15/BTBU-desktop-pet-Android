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
import android.view.Choreographer
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
        /** 数据已经拉好了（别的地方同步完）：只刷气泡/通知，不再请求一次 */
        const val ACTION_DATA = "com.dshpet.android.action.DATA"
        /** 一句台词在气泡里停留多久 */
        private const val SPEECH_MS = 3600L
        /** 气泡出现/消失动画时长 */
        private const val BUBBLE_ANIM_MS = 500L
        /** 多久没点气泡、也没点桌宠 → 自己收起来 */
        private const val BUBBLE_AUTO_HIDE_MS = 5_000L
        /** 气泡翻页后多久自动回到默认页（毫秒） */
        private const val PAGE_RESET_MS = 30_000L
        /** 空闲时的慢拍（毫秒）：没有东西在动就不要每帧醒 */
        private const val IDLE_TICK_MS = 66L
        /** 单帧最多按多少毫秒算（切后台/卡顿回来时别让物理瞬移） */
        private const val MAX_FRAME_MS = 50.0

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

        /** 数据变了（别处同步完成）：立刻刷气泡和通知栏，不再拉一次网 */
        fun dataChanged(ctx: Context) {
            if (!Prefs.running(ctx)) return
            try {
                ctx.startService(Intent(ctx, PetService::class.java).setAction(ACTION_DATA))
            } catch (t: Throwable) {
                Diag.record(ctx, "数据刷新通知失败", t)
            }
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

    // ---- 台词（没有单独的气泡窗口了：直接复用下面那块手绘气泡）----
    private var speechUntilMs = 0L
    private var speechText = ""

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
                ACTION_DATA -> {
                    updateDataBubble(true)
                    updateNotification()
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
            .setSmallIcon(R.drawable.ic_notif)   // 状态栏要单色剪影，用彩色图标会变白方块
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
                        // 气泡和宠物必须在同一帧里挪，否则拖动时气泡总慢半拍，看着像两块不相干的东西
                        try {
                            followDataBubble()
                        } catch (_: Throwable) {
                        }
                    }
                }
                pet.applyScale(sizeDp)
                pet.allowEdgeHide = Prefs.edgeHideOn(this)
                pet.onLongPress = { showActionMenu(pet) }
                pet.onTap = { onPetTapped(pet) }
                pet.onThrown = { onPetThrown(pet) }
                pet.requestFrame = { requestFrames() }
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
        choreographer.removeFrameCallback(frameCallback)
        handler.removeCallbacks(idleTick)
        frameScheduled = false
        dismissMenu()
        speechUntilMs = 0L
        speechText = ""
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
     * 单击 → 翻页（下一节课 / 校园卡余额 / 网费余额）；长按 → 立刻同步一次。
     * **全 App 只有这一块气泡**：说台词时把数据行换成一句话，到点切回来。
     */
    /**
     * 常驻气泡：dsh 鲸鱼挂件同款「手绘思考气泡」（几何见 res/drawable/btbu_bubble.xml）。
     *
     * 版式照上游：标题（北工商 · 第 N 周）/ 大数字（网 15.00  卡 25.80）/ 副行（下一节课）
     * + 底部绿色倒计时药丸（正在上课数到下课，否则数到下一节；Chronometer 系统驱动，零耗电）。
     * 字号随气泡宽度按上游 u 比例联动，ThoughtBubble 会按内容自动缩字，气泡本体永不变形。
     * 单击 → 翻页（下一节课 / 校园卡余额 / 网费余额）；长按 → 立刻同步一次。
     * **全 App 只有这一块气泡**：说台词时把数据行换成一句话，到点切回来。
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
            // 点一下 = 翻页（工作日：下一节课 → 校园卡 → 网费 → 下一节课；周末：网费 ↔ 校园卡）
            setOnClickListener {
                if (isSpeaking()) return@setOnClickListener
                try {
                    cycleBubblePage()
                    keepBubbleAlive()
                } catch (t: Throwable) {
                    Diag.record(this@PetService, "气泡翻页失败", t)
                }
            }
            // 长按 = 立刻同步一次（整周课表页已移除，这里改成刷数据）
            setOnLongClickListener {
                if (isSpeaking()) return@setOnLongClickListener false
                try {
                    keepBubbleAlive()
                    com.dshpet.android.btbu.BtbuSync.refreshAsync(this@PetService) { ok ->
                        updateDataBubble(true)
                        updateNotification()
                        Diag.record(this@PetService, if (ok) "长按气泡：同步完成" else "长按气泡：同步失败")
                    }
                    true
                } catch (t: Throwable) {
                    Diag.record(this@PetService, "长按气泡同步失败", t)
                    false
                }
            }
        }

        val bw = bubbleWidthPx(pet)
        box.mirrorBubble = pet.mirrored
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
        placeBubble(lp, bw, bh, pet, TAIL_COL_CX, com.dshpet.android.btbu.ThoughtBubble.TAIL_TIP)
        try {
            w.addView(box, lp)
            // 默认收起来：点一下桌宠/气泡才出现
            box.visibility = View.INVISIBLE
            bubbleShown = false
            box.onRevealEnd = { show ->
                if (!show) {
                    try {
                        box.visibility = View.INVISIBLE
                    } catch (_: Throwable) {
                    }
                }
            }
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

    /**
     * 内容层：按当前模式（数据 / 台词）把三行文字和倒计时药丸写好。
     * **只改内容，不碰窗口尺寸** —— 尺寸和位置统一由 [relayoutBubble] 管。
     */
    /** 气泡当前显示哪一页 */
    private enum class BubblePage { NEXT, CARD, NET }

    private var bubblePage = BubblePage.NEXT
    private var bubblePageAtMs = 0L
    /** 气泡现在是"露着"还是"收着"（默认收着，点一下才出现） */
    private var bubbleShown = false
    private val hideBubbleRunnable = Runnable { hideBubble() }

    /**
     * 工作日 / 周末模式：
     *  · 周六、周日 → 周末模式（默认显示网费，点一下切校园卡）
     *  · 周一~周五但"今天一节没课" → 也算周末模式（那天她真的没事干）
     *  · 周日 18:00 之后 → 切回工作日模式（明天要上课了）
     */
    private fun workdayMode(st: com.dshpet.android.btbu.BtbuStore.HudState?, now: java.time.LocalDateTime): Boolean {
        val wd = now.dayOfWeek.value
        if (wd == 7) return now.hour >= 18
        if (wd == 6) return false
        return st?.todayHasClass ?: false
    }

    /** 当前页：模式变了 / 停留超过 30 秒 → 回默认页 */
    private fun currentPage(workday: Boolean): BubblePage {
        val def = if (workday) BubblePage.NEXT else BubblePage.NET
        val stale = SystemClock.uptimeMillis() - bubblePageAtMs > PAGE_RESET_MS
        if (stale && bubblePage != def) {
            bubblePage = def
            bubblePageAtMs = SystemClock.uptimeMillis()
        }
        val valid = if (workday) {
            bubblePage == BubblePage.NEXT || bubblePage == BubblePage.CARD || bubblePage == BubblePage.NET
        } else {
            bubblePage == BubblePage.CARD || bubblePage == BubblePage.NET
        }
        if (!valid) bubblePage = def
        return bubblePage
    }

    /** 点一下气泡：在工作日/周末模式里循环 */
    private fun cycleBubblePage() {
        val st = try {
            com.dshpet.android.btbu.BtbuSync.state(this)
        } catch (t: Throwable) {
            null
        }
        val workday = workdayMode(st, java.time.LocalDateTime.now())
        bubblePage = if (workday) {
            when (bubblePage) {
                BubblePage.NEXT -> BubblePage.CARD
                BubblePage.CARD -> BubblePage.NET
                BubblePage.NET -> BubblePage.NEXT
            }
        } else {
            // 周末只有两页：网费 ↔ 校园卡
            if (bubblePage == BubblePage.CARD) BubblePage.NET else BubblePage.CARD
        }
        bubblePageAtMs = SystemClock.uptimeMillis()
        applyBubbleContent(true)
        relayoutBubble()
    }

    private fun applyBubbleContent(force: Boolean = false) {
        val head = dbHead ?: return
        val main = dbTitle ?: return
        val sub = dbBody ?: return
        val pill = dbPill

        // ---- 台词模式：同一块椭圆，只显示一句碎碎念 ----
        if (isSpeaking()) {
            head.visibility = View.GONE
            main.visibility = View.GONE
            pill?.visibility = View.GONE
            dbTimer?.stop()
            sub.visibility = View.VISIBLE
            sub.maxLines = 3
            sub.setTextColor(com.dshpet.android.btbu.ThoughtBubble.INK)
            sub.text = speechText
            return
        }

        // ---- 数据模式 ----
        head.visibility = View.VISIBLE
        main.visibility = View.VISIBLE
        sub.visibility = View.VISIBLE
        sub.maxLines = 2
        sub.setTextColor(com.dshpet.android.btbu.ThoughtBubble.HINT_COLOR)

        val st = try {
            com.dshpet.android.btbu.BtbuSync.state(this)
        } catch (t: Throwable) {
            null
        }
        if (st == null || !st.hasData) {
            head.text = "北工商桌宠"
            main.text = "未登录"
            sub.text = "点这里填学号密码"
            pill?.visibility = View.GONE
            dbTimer?.stop()
            dbSig = "empty"
            return
        }

        val now = java.time.LocalDateTime.now()
        val workday = workdayMode(st, now)
        val page = currentPage(workday)
        val noClassLine = "最近没课 摸鱼"

        when (page) {
            BubblePage.NEXT -> {
                head.text = st.headLine()          // 北工商 · 第 N 周
                main.text = st.classMain()         // 大字：几点上课 / 正在上则几点下课
                sub.text = st.classDetail()        // 哪天 + 课名 + 第几节 + 教室
            }
            BubblePage.CARD -> {
                head.text = "校园卡余额"
                main.text = st.cardMain()
                sub.text = if (workday) "点一下看网费" else noClassLine
            }
            BubblePage.NET -> {
                head.text = "网费余额"
                main.text = st.netMain()
                sub.text = if (workday) "点一下回到下一节课" else noClassLine
            }
        }

        // 倒计时药丸只在「下一节课」页有意义
        val remain = if (page == BubblePage.NEXT) st.countdownRemainMs() else null
        if (remain != null && remain > 0) {
            pill?.visibility = View.VISIBLE
            dbTimer?.let { t ->
                t.format = if (st.current != null) "距下课  %s" else "距上课  %s"
                t.base = android.os.SystemClock.elapsedRealtime() + remain
                t.isCountDown = true
                t.start()
            }
        } else {
            pill?.visibility = View.GONE
            dbTimer?.stop()
        }

        val sig = st.week.toString() + "|" + page.name + "|" + st.balanceLine() + "|" +
            st.classMain() + "|" + st.classDetail()
        if (!force && sig == dbSig) return
        dbSig = sig
    }

    // ------------------------------------------------------------ 气泡的显隐（点一下才出现）

    /** 让气泡出现（已经在的话只重置"5 秒自己收起来"的计时） */
    private fun showBubble() {
        val box = dbRoot ?: return
        handler.removeCallbacks(hideBubbleRunnable)
        handler.postDelayed(hideBubbleRunnable, BUBBLE_AUTO_HIDE_MS)
        if (bubbleShown) return
        bubbleShown = true
        try {
            updateDataBubble(true)          // 先按当前数据把内容/位置摆好
            box.visibility = View.VISIBLE
        } catch (t: Throwable) {
            Diag.record(this, "气泡显示失败", t)
        }
        // 从下往上依次显形（底下小圈 → 中间小圈 → 大椭圆），约 0.5 秒
        box.playReveal(true, BUBBLE_ANIM_MS)
    }

    /** 5 秒没人点：从上往下逐渐消失，约 0.5 秒 */
    private fun hideBubble() {
        handler.removeCallbacks(hideBubbleRunnable)
        val box = dbRoot ?: return
        if (!bubbleShown) return
        bubbleShown = false
        try {
            box.playReveal(false, BUBBLE_ANIM_MS)   // 收完由 onRevealEnd 把窗口设为 INVISIBLE
        } catch (t: Throwable) {
            box.visibility = View.INVISIBLE
        }
    }

    /** 点了气泡：重新计 5 秒 */
    private fun keepBubbleAlive() {
        if (!bubbleShown) return
        handler.removeCallbacks(hideBubbleRunnable)
        handler.postDelayed(hideBubbleRunnable, BUBBLE_AUTO_HIDE_MS)
    }

    /** 按内容重新量一次尺寸 + 摆放 + 提交（气泡只有一个窗口，尺寸会随台词/数据变化） */
    private fun relayoutBubble() {
        val box = dbRoot ?: return
        val pet = dbPet ?: return
        val lp = dbParams ?: return
        val bw = bubbleWidthPx(pet)
        box.mirrorBubble = pet.mirrored
        box.measure(
            View.MeasureSpec.makeMeasureSpec(bw, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val bh = box.measuredHeight.coerceAtLeast(dpPx(40))
        lp.width = bw
        lp.height = bh
        placeBubble(lp, bw, bh, pet, TAIL_COL_CX, com.dshpet.android.btbu.ThoughtBubble.TAIL_TIP)
        try {
            wm?.updateViewLayout(box, lp)
        } catch (_: Throwable) {
        }
    }

    private fun updateDataBubble(force: Boolean = false) {
        applyBubbleContent(force)
        relayoutBubble()
    }

    /**
     * 每帧 / 每次移动都跟着摆一次。**不量尺寸、不改尺寸** —— 气泡宽高只跟宠物大小有关，
     * 位置只跟宠物窗口位置有关，所以它俩永远是同一个整体（相对距离恒定）。
     */
    private fun followDataBubble() {
        if (!bubbleShown) return          // 收着的时候不用每帧跟着跑
        val box = dbRoot ?: return
        val lp = dbParams ?: return
        val pet = dbPet ?: return
        val oldX = lp.x
        val oldY = lp.y
        box.mirrorBubble = pet.mirrored
        placeBubble(lp, box.measuredWidth, box.measuredHeight, pet, TAIL_COL_CX, com.dshpet.android.btbu.ThoughtBubble.TAIL_TIP)
        if (lp.x == oldX && lp.y == oldY) return
        try {
            wm?.updateViewLayout(box, lp)
        } catch (_: Throwable) {
        }
    }

    private fun dismissDataBubble() {
        handler.removeCallbacks(hideBubbleRunnable)
        bubbleShown = false
        val box = dbRoot ?: return
        box.cancelReveal()
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
        speechUntilMs = 0L
        speechText = ""
    }

    // ------------------------------------------------------------ 台词（复用同一块气泡，不再有第二种气泡）

    /** 现在是不是在说台词（3.6 秒内） */
    private fun isSpeaking(): Boolean = SystemClock.uptimeMillis() < speechUntilMs

    /**
     * 说一句：**不开新窗口**，直接把手绘气泡切成「台词模式」。
     *
     * 以前这里会另开一个白色圆角矩形 + 小三角的窗口（近似矩形、半透明描边），
     * 和数据气泡锚点完全重合，只能靠 visibility 互相遮挡 —— 视觉上是两套 UI。
     * 现在只有一块气泡：说台词时把数据行藏起来、台词打在同一个椭圆里，到点自动切回数据。
     */
    private fun say(text: String) {
        if (dbRoot == null || dbPet == null) return      // 气泡没了就不说，不另开窗口
        if (text.isBlank()) return
        showBubble()                                     // 台词也要让气泡先显形
        val now = SystemClock.uptimeMillis()
        speechText = text
        speechUntilMs = now + SPEECH_MS
        lastBubbleMs = now
        applyBubbleContent(true)
        relayoutBubble()
        Diag.record(this, "台词「$text」")
    }

    /** 台词结束：恢复数据行 */
    private fun endSpeech() {
        if (!isSpeaking() && speechText.isEmpty()) return
        speechUntilMs = 0L
        speechText = ""
        applyBubbleContent(true)
        relayoutBubble()
    }

    // ------------------------------------------------------------ 气泡定位（几何固定）

    /**
     * 气泡里「尾巴那一列小圆点」的横向中心占窗口宽度的比例。
     * 路径实测：椭圆1 中心 x=352（r=37.5）、椭圆2 中心 x=442（r=24.5），
     * 两列的视觉中心 390.5 → 390.5 / 1026 ≈ 0.3806。
     *
     * 为什么锚这个、而不是椭圆中心（0.4425）：椭圆的中心跟"她"没有物理联系，
     * 尾巴才是"指向她"的那一笔。把尾巴柱对准她的盒子中心，气泡才像从她头顶长出来的。
     */
    private val TAIL_COL_CX = 390.5f / 1026f

    /**
     * 把气泡摆到宠物头顶，**几何完全固定**：
     *   水平：尾巴那一列对准她的盒子中心（不看镜像、不量墨迹 → 翻面/换动作都不横向跳）
     *   垂直：尾巴尖永远在她头顶上方 6dp
     *   尺寸：只由宠物大小决定（不随"头顶还剩多少空间"缩放 → 拖动时不会忽大忽小）
     *
     * 唯一会偏离的情况：她贴死屏幕最上面/最右边，气泡被夹进安全区 ——
     * 宁可夹住，也不翻到脚底、也不改尺寸。
     */
    private fun placeBubble(
        lp: WindowManager.LayoutParams,
        bw: Int,
        bh: Int,
        pet: Pet,
        anchorCx: Float = TAIL_COL_CX,
        tipRatio: Float = 1f,
    ) {
        if (bw <= 0 || bh <= 0) return
        val dm = resources.displayMetrics
        val d = dm.density
        val pad = (8 * d).toInt()
        // 她翻面时气泡也跟着翻（整对一起转），尾巴那一列换到对称位置：
        // 不补偿的话，镜像会让尾巴横跳 0.24×bw ≈ 160px，正是"脱节"的来源。
        val a = if (pet.mirrored) 1f - anchorCx else anchorCx
        var bx = (pet.boxCenterX - a * bw).toInt()
        // 允许最多 12% 出屏（FLAG_LAYOUT_NO_LIMITS 已开）：
        // 气泡比手机宽度窄不了多少，她贴着左右边时不出屏就必然被"推离"她的头，
        // 那一小段正好是椭圆的空白边，所以看起来还是完整的一整块。
        val slack = (bw * 0.12f).toInt()
        val minX = pad - slack
        val maxX = dm.widthPixels - bw - pad + slack
        bx = bx.coerceIn(minX, maxX.coerceAtLeast(minX))
        val gap = (6 * d).toInt()
        var by = pet.y.toInt() - (bh * tipRatio).toInt() - gap
        if (by < topInsetPx()) by = topInsetPx()
        lp.x = bx
        lp.y = by
    }

    // ------------------------------------------------------------ 碎碎念触发

    /** 说一句 / 说某类情境的一句。force 只跳过冷却，不跳过总开关（summon 除外）。 */
    private fun sayFrom(category: String, force: Boolean = false) {
        try {
            if (category != "summon" && !Prefs.bubbleOn(this)) return
            val l = lines ?: return
            val pet = dbPet ?: held.firstOrNull()?.pet ?: return
            val now = SystemClock.uptimeMillis()
            if (!force && now - lastBubbleMs < 20_000L) return
            val text = l.pick(category) ?: return
            // 气泡只挂在第一只身上（多只宠物时不抢别人）
            if (dbPet !== pet) return
            say(text)
        } catch (t: Throwable) {
            Diag.record(this, "sayFrom($category) 失败", t)
        }
    }

    private fun onPetTapped(pet: Pet) {
        showBubble()          // 点一下桌宠，气泡出现（0.5 秒从下往上显形）
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
                if (!isSpeaking()) sayFrom("edge")
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
            say(alert.text)
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

    // ------------------------------------------------------------ 帧循环（跟着屏幕刷新率走）

    /**
     * 帧循环用 [Choreographer] 而不是 `Handler.postDelayed(16)`。
     *
     * 为什么：postDelayed 只是"16 毫秒后再跑一次"，跟屏幕的 vsync 毫无关系 ——
     * 实际间隔常年落在 17~25ms（主线程还有别的事），也就是 40~58fps，
     * 而且相位是乱的，肉眼就是"甩出去以后一顿一顿的"。尤其 90/120Hz 屏上，
     * 它永远只有 60，且与刷新不同步，看起来比 60Hz 屏更抖。
     *
     * 换成 Choreographer 之后：
     *  · 回调就在 vsync 上，屏幕 60Hz 就给 60 帧、120Hz 就给 120 帧；
     *  · 每帧带 frameTimeNanos，宠物拿到的是**真实 dt**，物理不再假定 16.67ms；
     *  · 没有任何东西在动时不再每帧醒（掉到 66ms 慢拍），省电。
     */
    private val choreographer: Choreographer get() = Choreographer.getInstance()
    private var frameScheduled = false
    private var lastFrameNs = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            val dtMs = if (lastFrameNs == 0L) {
                1000.0 / 60.0
            } else {
                (frameTimeNanos - lastFrameNs) / 1_000_000.0
            }
            lastFrameNs = frameTimeNanos
            val active = tickAll(dtMs.coerceIn(2.0, MAX_FRAME_MS))
            if (active) {
                fpsSample(frameTimeNanos)
                requestFrames()
            } else {
                lastFrameNs = 0L
                // 这一帧里 push() 可能已经又要了一帧（比如刚好收尾），别再多挂一个慢拍
                if (!frameScheduled) {
                    handler.removeCallbacks(idleTick)
                    handler.postDelayed(idleTick, IDLE_TICK_MS)
                }
            }
        }
    }

    /** 空闲慢拍：只做台词/BTbu 那种低频检查；发现又有东西要动就切回 vsync */
    private val idleTick = object : Runnable {
        override fun run() {
            if (tickAll(IDLE_TICK_MS.toDouble())) {
                requestFrames()
            } else if (!frameScheduled) {
                handler.postDelayed(this, IDLE_TICK_MS)
            }
        }
    }

    /** 要下一帧。可重入（一帧里被叫多次也只排一帧）。 */
    private fun requestFrames() {
        if (frameScheduled) return
        frameScheduled = true
        handler.removeCallbacks(idleTick)
        choreographer.postFrameCallback(frameCallback)
    }

    private var fpsFrames = 0
    private var fpsWindowStartMs = 0L

    /** 每 2 秒在 logcat 打一条实测帧率，方便确认到底有没有跑满屏幕刷新率 */
    private fun fpsSample(frameTimeNanos: Long) {
        val nowMs = frameTimeNanos / 1_000_000
        fpsFrames++
        if (fpsWindowStartMs == 0L) {
            fpsWindowStartMs = nowMs
            return
        }
        val span = nowMs - fpsWindowStartMs
        if (span >= 2000) {
            val fps = fpsFrames * 1000.0 / span
            Log.i(TAG, "帧率自检 %.1f fps（屏幕 %.0fHz）".format(fps, displayHz()))
            fpsFrames = 0
            fpsWindowStartMs = nowMs
        }
    }

    /** 屏幕刷新率（拿不到就返回 0） */
    private fun displayHz(): Float = try {
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.refreshRate
    } catch (t: Throwable) {
        0f
    }

    /** 一帧的全部工作；返回 true = 还有东西在动，需要继续要帧 */
    private fun tickAll(dtMs: Double): Boolean {
        var active = false
        for (h in held) {
            try {
                if (h.pet.tick(dtMs)) active = true
            } catch (t: Throwable) {
                Diag.record(this@PetService, "tick 异常", t)
            }
        }
        // 台词到点 → 切回数据（同一块气泡，只是换内容）
        if (speechText.isNotEmpty() && !isSpeaking()) {
            try {
                endSpeech()
            } catch (t: Throwable) {
                Diag.record(this@PetService, "结束台词异常", t)
            }
        }
        try {
            followDataBubble()
        } catch (t: Throwable) {
            Diag.record(this@PetService, "followDataBubble 异常", t)
        }
        // 手动翻过页的话，30 秒没再点就自己回到默认页（下一节课 / 网费）
        if (bubblePageAtMs != 0L && !isSpeaking() &&
            SystemClock.uptimeMillis() - bubblePageAtMs > PAGE_RESET_MS
        ) {
            bubblePageAtMs = 0L
            try {
                applyBubbleContent(true)
                relayoutBubble()
            } catch (t: Throwable) {
                Diag.record(this@PetService, "气泡回默认页异常", t)
            }
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
        return active
    }

    private fun startTicker() {
        handler.removeCallbacks(idleTick)
        requestFrames()
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
