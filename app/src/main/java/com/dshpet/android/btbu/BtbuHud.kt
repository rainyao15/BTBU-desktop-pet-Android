package com.dshpet.android.btbu

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.dshpet.android.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌面小卡（悬浮窗版）：时间 + 周次 + 两个余额 + 下一节课。
 *
 * 为什么单独一个悬浮窗而不是塞进宠物那个 View：
 *  宠物窗口是按 16:9 素材比例裁的，往里塞文字会跟动画打架；单独一层卡片可以
 *  独立定位、独立高度、独立点击，也不影响宠物的命中框。
 *
 * 刷新节奏：
 *  - 时间：1 秒（纯本地，不耗电）
 *  - 数据派生（当前课/下一节）：20 秒重算一次（只读本地缓存）
 *  - 网络：15 分钟拉一次，且必须已配账号
 *
 * 交互：拖动可移动（位置记在 Prefs），轻点是"立刻同步"。
 */
class BtbuHud(private val ctx: Context, private val wm: WindowManager) {

    private val handler = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var tvClock: TextView? = null
    private var tvBalance: TextView? = null
    private var tvNext: TextView? = null

    private var cached: BtbuStore.HudState? = null
    private var cachedAt = 0L
    private var lastNetSyncAt = 0L
    private var released = false

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            try {
                render()
            } catch (_: Throwable) {
            }
            handler.postDelayed(this, 1000L)
        }
    }

    fun show() {
        if (root != null) return
        val d = ctx.resources.displayMetrics.density
        val dp: (Int) -> Int = { (it * d).toInt() }

        val cClock = TextView(ctx).apply {
            setTextColor(0xFFEEF3FF.toInt())
            textSize = 20f
            letterSpacing = 0.04f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        val cBalance = TextView(ctx).apply {
            setTextColor(0xFF6BE3B0.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        val cNext = TextView(ctx).apply {
            setTextColor(0xFF9DB1D5.toInt())
            textSize = 11.5f
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }
        tvClock = cClock
        tvBalance = cBalance
        tvNext = cNext

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(10), dp(14), dp(11))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xE81A2336.toInt())
                setStroke(dp(1), 0x803C5385.toInt())
            }
            addView(cClock)
            addView(cBalance)
            addView(cNext)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.hudX(ctx, dp(12))
            y = Prefs.hudY(ctx, dp(96))
        }
        lp = params

        box.setOnTouchListener { _, ev ->
            val p = lp ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) dragging = true
                    if (dragging) {
                        p.x = startX + dx
                        p.y = startY + dy
                        try {
                            wm.updateViewLayout(box, p)
                        } catch (_: Throwable) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        Prefs.setHudPos(ctx, p.x, p.y)
                    } else {
                        // 轻点 = 立刻同步一次（跟"戳宠物有反应"是同一套心智）
                        lastNetSyncAt = System.currentTimeMillis()
                        BtbuSync.refreshAsync(ctx)
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }

        root = box
        try {
            wm.addView(box, params)
        } catch (_: Throwable) {
            root = null
            return
        }
        render()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun render() {
        val now = System.currentTimeMillis()
        if (cached == null || now - cachedAt > 20_000L) {
            cached = runCatching { BtbuSync.state(ctx) }.getOrNull()
            cachedAt = now
        }
        if (now - lastNetSyncAt > 15 * 60_000L && BtbuSync.configured(ctx)) {
            lastNetSyncAt = now
            BtbuSync.refreshAsync(ctx)
        }

        tvClock?.text = fmt.format(Date())
        val st = cached
        if (st == null || !st.hasData) {
            tvBalance?.setTextColor(0xFF9DB1D5.toInt())
            tvBalance?.text = if (BtbuSync.configured(ctx)) "等待第一次同步…" else "未配置账号"
            tvNext?.text = "设置里填学号"
            return
        }

        val staleMin = ((now - st.syncedAt) / 60_000L).toInt()
        tvBalance?.setTextColor(if (staleMin > 60) 0xFFFFC46B.toInt() else 0xFF6BE3B0.toInt())
        tvBalance?.text = "第${st.week}周 · " + st.balanceLine() +
            if (staleMin > 30) "  (${staleMin / 60}h 前)" else ""

        val netWarn = st.net?.status?.contains("限") == true
        tvNext?.setTextColor(if (netWarn) 0xFFFFC46B.toInt() else 0xFF9DB1D5.toInt())
        tvNext?.text = if (netWarn && st.current == null) st.nextLine() + " · 网被限速"
        else st.nextLine()
    }

    fun release() {
        released = true
        handler.removeCallbacks(tick)
        root?.let {
            try {
                wm.removeView(it)
            } catch (_: Throwable) {
            }
        }
        root = null
        lp = null
        tvClock = null
        tvBalance = null
        tvNext = null
    }
}
