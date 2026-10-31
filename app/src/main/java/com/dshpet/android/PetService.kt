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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar
import kotlin.random.Random
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
        val stopIcon = Icon.createWithResource(this, R.drawable.ic_launcher)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(stopIcon, getString(R.string.notif_stop), stop).build())
            .build()
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

        val conf = try {
            PetConfig.load(this)
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

                val pet = Pet(this, conf, spec) { x, y, ww, hh ->
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

        // 打个招呼
        handler.postDelayed({ sayFrom("greet", force = true) }, 1600L)
    }

    private fun teardown() {
        handler.removeCallbacks(tickRunnable)
        dismissMenu()
        dismissBubble()
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

    private fun placeBubble(lp: WindowManager.LayoutParams, bw: Int, bh: Int, pet: Pet) {
        if (bw <= 0 || bh <= 0) return
        val dm = resources.displayMetrics
        val d = dm.density
        val pad = (8 * d).toInt()
        var bx = pet.windowLeftPx + pet.sizePx / 2 - bw / 2
        val maxX = dm.widthPixels - bw - pad
        bx = bx.coerceIn(pad, maxX.coerceAtLeast(pad))
        var by = pet.y.toInt() - bh - (6 * d).toInt()
        if (by < (28 * d).toInt()) {
            // 头顶放不下（宠物在屏幕最上面）就放到脚下
            by = pet.y.toInt() + pet.heightPx + (6 * d).toInt()
        }
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
            val n = SystemClock.uptimeMillis()
            if (n - lastSpeechCheck > 1000L) {
                lastSpeechCheck = n
                try {
                    tickSpeech(n)
                } catch (t: Throwable) {
                    Diag.record(this@PetService, "tickSpeech 异常", t)
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