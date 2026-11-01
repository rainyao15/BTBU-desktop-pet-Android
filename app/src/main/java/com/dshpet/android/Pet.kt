package com.dshpet.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.ImageDecoder
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 一只桌宠的完整运行时：动画链 + 触摸交互 + 拖拽/甩抛物理 + 屏幕漫游 + 贴边躲藏。
 *
 * 与电脑版的对应关系（dsh-pet 源码）：
 *  - 物理与常量        → src/shared/physics.ts（见 Physics.kt，逐行移植，未改数值）
 *  - 命中框/比例        → src/shared/constants.ts（HIT_BOX 200..440 / 640）
 *  - 角落定位          → src/shared/motion.ts anchorPixel()
 *  - 动画链权重        → assets/config.jsonc animationWeights + categories
 * 手机端去掉了对话/余额/通知，因此没有任何网络与 LLM 调用（v1.6 起加了本地台词气泡，
 * 台词全部来自 assets/lines.json，不联网）。
 */
class Pet(
    private val context: Context,
    val cfg: PetConfig,
    val spec: PetSpec,
    /** 宠物盒高宽比：素材是 16:9 时用默认值；换成方形的立绘/动图时传 1.0 */
    private val aspect: Double = 9.0 / 16.0,
    private val onMove: (x: Int, y: Int, w: Int, h: Int) -> Unit,
) {
    companion object {
        private const val TAG = "DshPet"
        /** 移动动画名义时长（10s 片段减去两端 lead/tail 约 2s+2s） */
        private const val MOVE_WINDOW_MS = 6000L
        /** 拖出这个距离才算拖拽（dp） */
        private const val DRAG_SLOP_DP = 5.0
        /** 松手时离边缘多近就吸附过去（dp） */
        private const val SNAP_SLOP_DP = 30.0
        /** 吸附后闲置多久缩进去躲起来 */
        private const val HIDE_DELAY_MS = 12_000L
        /** 躲起来时藏掉多少比例的身子（0.55 = 只露 45%） */
        private const val HIDE_RATIO = 0.55
        /** 躲藏位移的每帧平滑系数（按 16.67ms 一帧标定，实际按真实 dt 换算） */
        private const val HIDE_LERP = 0.18
        /** 抛掷物理的固定积分步长（秒）：与屏幕刷新率解耦 */
        private const val PHYS_STEP_S = 1.0 / 120.0
        /** 载入解压/解码动画用的后台线程 */
        private val io = Executors.newSingleThreadExecutor()
        /** 资源前缀：slug 以它开头时走 res 而不是 assets */
        const val RES_PREFIX = "res:"

        // ---- 按压压扁：数值逐个照抄上游 DSH 鲸鱼挂件 assets/whale-widget.js ----
        //   var SQUISH = 'scaleY(0.88) scaleX(1.05)'
        //   .dshwv-body{transform-origin:50% 100%; transition:transform .22s cubic-bezier(.34,1.56,.64,1)}
        //   var PRESS_VISUAL_TAP_MS = 32   // 极短点按要补一次强制 reflow，否则看不见压扁
        private const val SQUISH_Y = 0.88f
        private const val SQUISH_X = 1.05f
        private const val PRESS_TRANSITION_MS = 220L
        private const val PRESS_TAP_GUARD_MS = 32L
    }

    private val density = context.resources.displayMetrics.density

    /** 宠物盒宽度（屏幕 px） */
    var sizePx: Int = 0
        private set
    var heightPx: Int = 0
        private set

    /** 包围盒左上角（屏幕 px，不含躲藏位移） */
    var x = 0.0
        private set
    var y = 0.0
        private set

    private var facing = 1                 // 1 = 朝右，-1 = 朝左（只决定「往哪走」，不再决定画法）
    /**
     * 朝向规则（只按位置算，与走路/站定无关，跟上游 applyFlip() 一致）：
     * true  = 面朝屏幕外侧：在左半屏朝左、在右半屏朝右 —— 默认，也是上游的行为；
     * false = 面朝屏幕中间（"看着你"）。
     * 动作菜单里的「翻转朝向」会切换这个值。
     */
    private var faceOutward = true
    private var lastWantLeft: Boolean? = null   // 只为 Diag 打点：朝向变化时记一条
    private var forcedNoMirror = false      // 文字类动作不镜像

    private var current: Drawable? = null
    private var currentSlug: String? = null
    private var loadToken = 0

    private enum class Mode { IDLE, DRAG, THROW, MOVE }
    private var mode = Mode.IDLE

    // ---- 拖拽 ----
    private val trail = ArrayList<DragSample>(32)
    private var dragTargetX = 0.0
    private var dragTargetY = 0.0
    private var dragVX = 0.0
    private var dragVY = 0.0
    private var grabDX = 0.0
    private var grabDY = 0.0
    private var downRawX = 0.0
    private var downRawY = 0.0
    private var downAtMs = 0.0
    private var dragging = false
    /** 跟手延迟统计：MOVE 帧数 + 从硬件事件时间到窗口提交的累计延时（ms） */
    private var dragFrames = 0
    private var dragLagSumMs = 0L

    // ---- 抛掷 ----
    private var body = Body(0.0, 0.0, 0.0, 0.0)

    // ---- 漫游 ----
    private var moveFromX = 0.0
    private var moveToX = 0.0
    private var moveStartMs = 0L
    private var moveDurationMs = MOVE_WINDOW_MS

    // ---- 贴边躲藏 ----
    /** 是否允许贴边躲藏（设置页可关） */
    var allowEdgeHide = true
    /** -1 = 贴在左边缘，+1 = 贴在右边缘，0 = 没贴边 */
    private var snapEdge = 0
    /** 躲藏位移目标（负数往左缩，正数往右缩） */
    private var hideTargetPx = 0.0
    /** 躲藏位移当前值（每帧平滑逼近目标） */
    private var hideNowPx = 0.0
    private var hidden = false
    private var lastInteractMs = 0L

    // ---- Q 弹 ----
    private var squashAtMs = 0L
    private var squashAmount = Physics.SQ_SQUASH
    private var squashActive = false

    // ---- 按压压扁（照抄上游 DSH 鲸鱼挂件的点击效果）----
    // 上游 assets/whale-widget.js：
    //   var SQUISH = 'scaleY(0.88) scaleX(1.05)'
    //   pressDown(): body.style.transform = SQUISH
    //   pressUp():   body.style.transform = 'scaleY(1) scaleX(1)'
    //   极短点按（< PRESS_VISUAL_TAP_MS=32ms）时补一次 transition:none + 强制 reflow，
    //   让"按下去的那一下"至少能看见，然后立刻回弹
    //   样式：.dshwv-body{transform-origin:50% 100%; transition:transform .22s cubic-bezier(.34,1.56,.64,1)}
    // 我们这边 onDraw 本来就用「脚底中点」当锚点做 canvas.scale，跟 transform-origin:50% 100% 等价，
    // 所以只要把 (sx, sy) 喂进去就一模一样。
    /** 上游那条 cubic-bezier(.34,1.56,.64,1)：会冲过头再回来，就是"弹"的来源 */
    private val pressEase = android.view.animation.PathInterpolator(0.34f, 1.56f, 0.64f, 1f)

    /** 0 = 原样，1 = 压到最瘪 */
    private var pressFromP = 0f
    private var pressToP = 0f
    private var pressAnimStartMs = 0L
    private var pressDownAtMs = 0L

    /** 当前压扁进度（含回弹过冲，可能略微 <0 或 >1，和浏览器里一样） */
    private fun pressProgress(): Float {
        val t = ((SystemClock.uptimeMillis() - pressAnimStartMs).toFloat() / PRESS_TRANSITION_MS).coerceIn(0f, 1f)
        val e = pressEase.getInterpolation(t)
        return pressFromP + (pressToP - pressFromP) * e
    }

    private fun startPress(target: Float) {
        requestFrame?.invoke()
        pressFromP = pressProgress()
        pressToP = target
        pressAnimStartMs = SystemClock.uptimeMillis()
        canvasView.invalidate()
    }

    private val canvasView = PetCanvas(context)

    /**
     * 需要连续帧时叫一声（Service 收到就去 Choreographer 排一帧）。
     * 没有它的话，空闲期（66ms 慢拍）里按下/拖动要等到下一拍才开始动。
     */
    var requestFrame: (() -> Unit)? = null

    /** 长按回调（Service 里挂上一个「动作菜单」） */
    var onLongPress: (() -> Unit)? = null
    /** 点一下（Service 用来统计连点 → 说一句「被戳烦了」） */
    var onTap: (() -> Unit)? = null
    /** 被甩出去 */
    var onThrown: (() -> Unit)? = null
    /** 拖拽/抛掷彻底停下来（含落到边缘吸附） */
    var onSettled: (() -> Unit)? = null
    /** 缩进去躲起来 / 探出来 */
    var onHiddenChange: ((Boolean) -> Unit)? = null

    private var pressed = false
    private var longPressFired = false
    private val longPressRunnable = object : Runnable {
        override fun run() {
            if (pressed && !dragging) {
                longPressFired = true
                onLongPress?.invoke()
            }
        }
    }

    init {
        applyScale(spec.size)
        val (ax, ay) = anchorPixel()
        x = ax
        y = ay
    }

    val view: View get() = canvasView

    // ---------------------------------------------------------------- 尺寸/位置

    fun applyScale(sizeDp: Int) {
        sizePx = (sizeDp * density).toInt().coerceAtLeast(64)
        heightPx = (sizePx * aspect).toInt().coerceAtLeast(36)
        canvasView.updateLayoutSize(sizePx, heightPx)
        push()
    }

    private fun sideAllow(): Double = sizePx * 200.0 / 640.0

    private fun bounds(): Bounds {
        val dm = context.resources.displayMetrics
        return Physics.bounds(
            dm.widthPixels.toDouble(),
            dm.heightPixels.toDouble(),
            sizePx.toDouble(),
            sideAllow(),
            heightPx.toDouble(),   // 真实盒高（立绘是 1:1，不能再按 16:9 算）
        )
    }

    /** motion.ts anchorPixel()：角落 + 边距 → 左上角 */
    private fun anchorPixel(): Pair<Double, Double> {
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels.toDouble()
        val h = dm.heightPixels.toDouble()
        val d = density.toDouble()
        val left = spec.marginX * d
        val top = spec.marginY * d
        val right = w - sizePx - spec.marginX * d
        val bottom = h - heightPx - spec.marginY * d
        return when (spec.corner) {
            "top-left" -> Pair(left, top)
            "bottom-left" -> Pair(left, bottom)
            "bottom-right" -> Pair(right, bottom)
            else -> Pair(right, top)   // top-right
        }
    }

    /** 实际提交给 WindowManager 的 x（含躲藏位移） */
    private fun windowX(): Double = x + hideNowPx

    /** 给台词气泡等外部定位用的窗口左边缘（含躲藏位移） */
    val windowLeftPx: Int get() = windowX().toInt()

    /** 回到配置的角落（右键菜单/设置里用） */
    fun resetPosition() {
        mode = Mode.IDLE
        snapEdge = 0
        wake()
        body = Body(x, y, 0.0, 0.0)
        val (ax, ay) = anchorPixel()
        x = ax
        y = ay
        push()
        playIdle()
    }

    /** 供 Service 摆放「第二只、第三只」：整体位移一段距离 */
    fun shiftBy(dx: Double, dy: Double) {
        x += dx
        y += dy
        clampToScreen()
        push()
    }

    /** 初始摆放用：位移之后把「整只」夹进屏幕，保证一定看得见（物理边界允许半只出屏） */
    fun shiftByVisible(dx: Double, dy: Double) {
        x += dx
        y += dy
        val dm = context.resources.displayMetrics
        x = x.coerceIn(0.0, (dm.widthPixels - sizePx).coerceAtLeast(0).toDouble())
        y = y.coerceIn(0.0, (dm.heightPixels - heightPx).coerceAtLeast(0).toDouble())
        push()
    }

    private fun push() {
        requestFrame?.invoke()
        syncSide()
        onMove(windowX().toInt(), y.toInt(), sizePx, heightPx)
    }

    /**
     * 拖拽跟手：直接落在手指位置，**不做任何平滑**，同帧立刻 updateViewLayout。
     *
     * 触摸事件本身就是按屏幕刷新率投递的（60/120Hz），所以每来一个 MOVE 就提交一次；
     * 位置与手指之间不存在“目标 → 弹簧 → 积分”这一串中间环节，也就是 0 帧跟随。
     */
    private fun dragTo(nx: Double, ny: Double) {
        dragTargetX = nx
        dragTargetY = ny
        x = nx
        y = ny
        clampToScreen()
        push()
    }

    /**
     * 位置变了就检查一次朝向：宠物是靠 updateViewLayout 挪窗口的，
     * 窗口位置变了 View 内容并不会自动重绘 —— 跨过屏幕中线时必须手动 invalidate，
     * 否则就会出现「贴着右边却还朝右」这种"没反应"的现象。
     * （拖拽/抛掷/走路/贴边藏都会走 push()，所以挂在 push 上最保险。）
     */
    private fun syncSide() {
        val midX = context.resources.displayMetrics.widthPixels / 2f
        val sideLeft = (x + sizePx / 2f) < midX
        val want = if (faceOutward) sideLeft else !sideLeft
        if (lastWantLeft != want) {
            Diag.record(context, "syncSide x=${x.toInt()} mid=${midX.toInt()} side=$sideLeft want=$want last=$lastWantLeft")
            canvasView.invalidate()
        }
    }

    /** 朝向规则（只看位置）：true = 应该朝左画 */
    private fun wantLeftNow(): Boolean {
        val midX = context.resources.displayMetrics.widthPixels / 2f
        val onLeftHalf = x + sizePx / 2f < midX
        return if (faceOutward) onLeftHalf else !onLeftHalf
    }

    /** 当前是不是镜像画的（文字类动作不镜像）。气泡要靠它决定跟哪一边。 */
    val mirrored: Boolean get() = !forcedNoMirror && wantLeftNow()

    // ---- 可见中心：气泡挂在这个点上，而不是盒子中心 ----
    /** 当前形象「看得见的墨迹」在盒子宽度上的中心比例（0..1，未镜像）；量不出来时 0.5 */
    private var inkCxRatio = 0.5
    private val inkCxCache = HashMap<String, Double>()

    /**
     * 屏幕 px：她**看得见的那半个身子**的中心（已考虑镜像）。
     *
     * 为什么不能直接用盒子中心：素材四周的留白不是对称的。实测这张立绘
     * （610×610）墨迹横向只占 x 45..609，中心在 **0.536**，而不是 0.5；
     * 镜像之后它又翻到 1-0.536 = 0.464。用盒子中心去摆气泡，
     * 左边和右边会各自偏一段（而且偏的方向相反）—— 就是“气泡不跟着她对称”的主因。
     */
    val visibleCenterX: Double
        get() = windowX() + sizePx * (if (mirrored) 1.0 - inkCxRatio else inkCxRatio)

    /**
     * 量一个 Drawable 的墨迹横向中心：等比画进 64×64，扫 alpha>8 的像素取 bbox 中心。
     * 只在换形象时算一次（结果按 slug 缓存），一次 4096 像素，不影响帧率。
     */
    private fun measureInkCx(d: Drawable): Double {
        val n = 64
        return try {
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, n, n)
            d.draw(c)
            val px = IntArray(n * n)
            bmp.getPixels(px, 0, n, 0, 0, n, n)
            bmp.recycle()
            var minX = n
            var maxX = -1
            var cnt = 0
            for (y in 0 until n) {
                for (x in 0 until n) {
                    if (((px[y * n + x] ushr 24) and 0xFF) > 8) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        cnt++
                    }
                }
            }
            // 全透明（还没解码完）或只有零星几个像素（描边噪点）→ 不信这个数，退回 0.5
            if (maxX < 0 || cnt < n * n / 100) 0.5 else ((minX + maxX + 1) / 2.0 / n).coerceIn(0.2, 0.8)
        } catch (t: Throwable) {
            0.5
        }
    }

    /** 外部（Service）把窗口加上去之后调用一次，把当前位置同步给 WindowManager */
    fun refreshWindow() = push()

    private fun clampToScreen() {
        val b = bounds()
        x = x.coerceIn(b.minX, b.maxX)
        y = y.coerceIn(b.minY, b.maxY)
    }

    // ---------------------------------------------------------------- 贴边躲藏

    private fun hideAmountPx(): Double = sizePx * HIDE_RATIO

    /** 任何一次交互都要「叫醒」她：探出来，并重置闲置计时 */
    private fun wake() {
        lastInteractMs = SystemClock.uptimeMillis()
        if (hidden) setHidden(false)
    }

    private fun setHidden(on: Boolean) {
        val want = if (on && snapEdge != 0) snapEdge * hideAmountPx() else 0.0
        if (want == hideTargetPx) return
        hideTargetPx = want
        if (on != hidden) {
            hidden = on
            Log.i(TAG, "hide=$on offset=${want.toInt()}")
            onHiddenChange?.invoke(on)
        }
    }

    /**
     * 松手/落地后判断：贴着左右边缘就吸附过去（贴边时朝向屏幕内侧）。
     * 这是桌宠的常见行为——往边上一丢，她就自己靠过去，闲置一会儿还会缩起来。
     */
    private fun snapIfNearEdge() {
        lastInteractMs = SystemClock.uptimeMillis()
        if (!allowEdgeHide) {
            snapEdge = 0
            setHidden(false)
            return
        }
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels.toDouble()
        val slop = SNAP_SLOP_DP * density
        val leftGap = x
        val rightGap = w - (x + sizePx)
        val edge = when {
            leftGap <= slop -> -1
            rightGap <= slop -> 1
            else -> 0
        }
        if (edge == 0) {
            snapEdge = 0
            setHidden(false)
            return
        }
        snapEdge = edge
        x = if (edge < 0) 0.0 else w - sizePx
        facing = if (edge < 0) 1 else -1
        forcedNoMirror = false
        Log.i(TAG, "snap edge=$edge x=${x.toInt()}")
        push()
        canvasView.invalidate()
    }

    // ---------------------------------------------------------------- 动画播放

    /** 当前是否在播（供链式调度判断） */
    private fun isPlaying(): Boolean {
        val d = current ?: return false
        return (d as? AnimatedImageDrawable)?.isRunning ?: false
    }

    /**
     * 解析一段素材：
     *  - "res:pet_idle"  → res/drawable-nodpi 里同名资源（PNG 立绘 / GIF 动图都行）
     *  - 其它             → assets/webp/<slug>.webp（上游那 106 段透明动画）
     *
     * 这样"用上游鲸鱼娘的静态图/动图当形象"和"播 106 段动画"能共存，切换只改配置。
     */
    private fun decodeSlug(slug: String): Drawable? {
        if (slug.startsWith(RES_PREFIX)) {
            val name = slug.removePrefix(RES_PREFIX)
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (id == 0) return null
            val src = ImageDecoder.createSource(context.resources, id)
            return ImageDecoder.decodeDrawable(src)
        }
        val src = ImageDecoder.createSource(context.assets, "webp/$slug.webp")
        return ImageDecoder.decodeDrawable(src)
    }

    private fun play(slug: String, onEnd: (() -> Unit)? = null) {
        if (slug.isEmpty()) {
            onEnd?.invoke()
            return
        }
        loadToken++
        val token = loadToken
        io.execute {
            var d: Drawable? = null
            try {
                d = decodeSlug(slug)
                if (d == null) Log.w(TAG, "解码为空 $slug")
            } catch (t: Throwable) {
                Log.w(TAG, "解码失败 $slug: ${t.message}")
            }
            canvasView.post {
                if (token != loadToken) return@post      // 已被更新的动画取代
                val dd = d ?: run { onEnd?.invoke(); return@post }
                val anim = dd as? AnimatedImageDrawable
                if (anim != null) {
                    anim.repeatCount = 1
                    anim.callback = canvasView
                }
                (current as? AnimatedImageDrawable)?.stop()
                current = dd
                currentSlug = slug
                // 量一次「看得见的中心」（每张素材只算一次）：气泡挂她身上靠这个，
                // 不能假定墨迹居中 —— 立绘四周留白是不对称的。
                inkCxRatio = inkCxCache.getOrPut(slug) { measureInkCx(dd) }
                anim?.start()
                canvasView.invalidate()
                Log.i(TAG, "play $slug ${cfg.durationOf(slug)}ms")
                scheduleClipEnd(slug, token, onEnd)
            }
        }
    }

    /**
     * 按「这段动画播一遍的时长」收尾，与 repeat 语义彻底解耦。
     *
     * 旧实现用轮询 `isRunning` 判断播完，但 `AnimatedImageDrawable.repeatCount = 1`
     * 在多数机型上表示「再重复一次」= 一共播两遍，于是用户看到每个动作都做两遍。
     * 改成毫秒级定时：到点强制 stop，再交给 onEnd 接下一段。
     */
    private fun scheduleClipEnd(slug: String, token: Int, onEnd: (() -> Unit)?) {
        val ms = cfg.durationOf(slug).coerceIn(500, 30_000).toLong()
        canvasView.postDelayed({
            if (token != loadToken) return@postDelayed
            Log.i(TAG, "end $slug token=$token cur=$loadToken")
            (current as? AnimatedImageDrawable)?.stop()
            onEnd?.invoke()
        }, ms)
    }

    private fun pickByWeight(items: List<AnimCategory>): AnimCategory? {
        val total = items.sumOf { max(it.weight, 0) }
        if (total <= 0) return items.randomOrNull()
        var r = Random.nextInt(total)
        for (c in items) {
            if (c.weight <= 0) continue
            r -= c.weight
            if (r < 0) return c
        }
        return items.lastOrNull()
    }

    /**
     * 权重选下一段：idle/turn/move 各占配置权重，剩余 80% 全给「随机动作分类」。
     * 与 dsh-pet 客户端一致（默认 idle 10 / turn 5 / move 5，其余随机动作）。
     */
    private fun scheduleNext() {
        Log.i(TAG, "-> scheduleNext")
        val wIdle = max(cfg.weightIdle, 0)
        val wTurn = max(cfg.weightTurn, 0)
        val wMove = max(cfg.weightMove, 0)
        val wRandom = 80
        val total = wIdle + wTurn + wMove + wRandom
        var r = Random.nextInt(total.coerceAtLeast(1))
        if (r < wIdle) { playIdle(); return }
        r -= wIdle
        if (r < wTurn) { playTurn(); return }
        r -= wTurn
        if (r < wMove) { playMove(); return }
        playRandomAction()
    }

    private fun playIdle() {
        Log.i(TAG, "-> playIdle")
        if (cfg.idle.isEmpty()) { playRandomAction(); return }
        mode = Mode.IDLE
        play(cfg.idle.random()) { scheduleNext() }
    }

    private fun playTurn() {
        if (cfg.turn.isEmpty()) { playIdle(); return }
        facing = if (facing == 1) -1 else 1
        forcedNoMirror = false
        mode = Mode.IDLE
        canvasView.invalidate()
        play(cfg.turn.random()) { scheduleNext() }
    }

    private fun playRandomAction() {
        Log.i(TAG, "-> playRandomAction")
        val cat = pickByWeight(cfg.categories)
        if (cat == null || cat.actions.isEmpty()) { playIdle(); return }
        forcedNoMirror = cat.noMirror
        mode = Mode.IDLE
        play(cat.actions.random()) {
            forcedNoMirror = false
            scheduleNext()
        }
    }

    /** 漫游：朝当前朝向走一段（距离按 size/462 等比缩放），越界则夹回可走范围 */
    private fun playMove() {
        Log.i(TAG, "-> playMove")
        if (cfg.moves.isEmpty()) { playIdle(); return }
        // 缩在屏幕边上时不要乱走，否则会「半只身子悬在屏幕中间」
        if (hidden || hideTargetPx != 0.0) { playIdle(); return }
        val act = cfg.moves.random()
        val scale = sizePx / PetConfig.PET_REF_WIDTH
        val dist = Random.nextDouble(act.minDist, max(act.maxDist, act.minDist + 1.0)) * scale
        // 统一换算到「框中心」再比较：`x` 是左上角，直接拿它和中心边界比会偏半个身位，
        // 这正是随机漫游时她会跑出屏幕的原因。
        val halfW = sizePx / 2.0
        val margin = cfg.moveMargin * density
        val dm = context.resources.displayMetrics
        val centerX = x + halfW
        val targetCenter = centerX + facing * dist
        // 漫游必须整只留在屏幕内：这里不沿用拖拽/甩抛的 sideAllow（那会允许贴边出屏）。
        val minLeft = minOf(margin, (dm.widthPixels - sizePx) / 2.0).coerceAtLeast(0.0)
        val maxLeft = maxOf(minLeft, dm.widthPixels - sizePx - minLeft)
        val leftBound = minLeft + halfW
        val rightBound = maxLeft + halfW
        // 越界时不再「掉头待机」——把目标夹回可走范围，让她走到边上就停，
        // 这样既不会出屏，也不会因为一次越界就浪费掉整条漫游。
        val clamped = targetCenter.coerceIn(leftBound, rightBound)
        if (abs(clamped - centerX) < 4.0) {
            // 已经贴边且朝外，没得走：掉头，下一条再来
            facing = -facing
            canvasView.invalidate()
            Log.i(TAG, "move skip: at edge center=$centerX facing=$facing")
            playIdle()
            return
        }
        mode = Mode.MOVE
        moveFromX = x
        moveToX = (clamped - halfW).coerceIn(minLeft, maxLeft)
        Log.i(TAG, "move dist=${dist.toInt()} facing=$facing center=$centerX -> ${clamped.toInt()}  left=[$minLeft..$maxLeft]  x=$x -> $moveToX")
        moveStartMs = SystemClock.uptimeMillis()
        moveDurationMs = MOVE_WINDOW_MS
        play(act.slug) {
            x = moveToX
            push()
            mode = Mode.IDLE
            // 走完再判一次贴边：走过来正好停在边上就自己靠过去
            snapIfNearEdge()
            scheduleNext()
        }
    }

    // ---------------------------------------------------------------- 触摸

    private fun nowMs(): Double = SystemClock.uptimeMillis().toDouble()

    private fun startThrow(vx: Double, vy: Double) {
        body = Body(x, y, vx, vy)
        mode = Mode.THROW
        playDragIfAny()
    }

    private fun playDragIfAny() {
        if (cfg.drag.isEmpty()) return
        play(cfg.drag.random())
    }

    private fun squash(amount: Double = Physics.SQ_SQUASH) {
        squashAmount = amount
        squashAtMs = SystemClock.uptimeMillis()
        squashActive = true
        canvasView.invalidate()
    }

    private fun onTap() {
        Log.i(TAG, "-> onTap")
        wake()
        // 点击效果 = 上游那套「按一下变瘪、松手弹回来」，已经在 ACTION_DOWN/UP 里做了，
        // 这里不再播「摸摸头」动画（clicks 配置项保留，只是默认不再被点到）。
        onTap?.invoke()
    }

    // ---------------------------------------------------------------- 每帧

    /**
     * 一帧。[dtMs] 是「距上一帧的真实毫秒数」（由 Choreographer 给），
     * 不再假定 16.67ms —— 60/90/120Hz 下弹跳速度、躲藏平滑都一样，掉帧也不会突然变快。
     */
    fun tick(dtMs: Double = 16.0): Boolean {
        val now = SystemClock.uptimeMillis()
        var active = false

        when (mode) {
            Mode.DRAG -> {
                // 跟手＝手指在哪儿她就在哪儿，这里只做「最后一帧的补位」。
                //
                // 以前这里是 dragVX = springStep(...) 再 x += dragVX*dt：
                // 位置去追一个弹簧目标（K=200, C=30，时间常数 ≈70ms），
                // 手指停下来她还在后面晃，快速拖时她永远慢半拍 —— 这就是「拖拽延迟」的来源。
                // 拖动过程必须是 1:1 直跟，弹簧只留给抛掷后的物理。
                if (x != dragTargetX || y != dragTargetY) {
                    x = dragTargetX
                    y = dragTargetY
                    clampToScreen()
                    push()
                }
                // 拖动期间保持帧循环跑在 16ms：气泡/通知栏/挂件都是在 tick 里跟位置的，
                // 这里不返 true 的话它们会降到 66ms，变成「她跟上了、气泡在后边追」。
                active = true
            }
            Mode.THROW -> {
                // 物理用固定小步长（1/120s）积分，渲染跟着 vsync 走：
                //  · 120Hz 屏上每帧补 1 步、60Hz 屏上补 2 步 → 弹跳轨迹/速度完全一致
                //  · 掉一帧（dt 变大）也不会「穿墙 / 弹飞」，只是多补几步
                val b = bounds()
                val vyBefore = body.vy
                var remain = (dtMs / 1000.0).coerceIn(0.0, 0.05)
                var atRest = false
                var bouncedHard = false
                while (remain > 0.0) {
                    val step = min(PHYS_STEP_S, remain)
                    val r = Physics.throwStep(Body(x, y, body.vx, body.vy), step, b, cfg.physics)
                    x = r.body.x
                    y = r.body.y
                    body = r.body
                    if (r.bounced && abs(r.body.y - b.maxY) < 1.5 && abs(vyBefore) > Physics.SQ_SOFT_SPEED) {
                        bouncedHard = true
                    }
                    remain -= step
                    if (r.atRest) {
                        atRest = true
                        break
                    }
                }
                if (bouncedHard) squash(Physics.landingSquash(vyBefore))
                push()
                if (atRest) {
                    mode = Mode.IDLE
                    snapIfNearEdge()
                    playIdle()
                    onSettled?.invoke()
                } else {
                    active = true
                }
            }
            Mode.MOVE -> {
                val elapsed = now - moveStartMs
                val p = (elapsed.toDouble() / moveDurationMs).coerceIn(0.0, 1.0)
                // 中段匀速、两端缓入缓出（近似 leadSec/tailSec 的效果）
                val eased = if (p < 0.2) (p / 0.2).let { it * it * (3 - 2 * it) * 0.2 }
                else if (p > 0.8) 1.0 - ((1 - p) / 0.2).let { it * it * (3 - 2 * it) } * 0.2
                else p
                x = moveFromX + (moveToX - moveFromX) * eased
                push()
                active = p < 1.0
            }
            Mode.IDLE -> {}
        }

        // 朝向只跟位置有关，而移动宠物是靠 updateViewLayout 挪窗口的 —— 窗口动了，
        // View 内容不一定会重绘，所以跨过屏幕中线时必须主动 invalidate 一次，
        // 否则就会出现「贴着右边却还朝右」这种"没反应"的现象。
        val midX = context.resources.displayMetrics.widthPixels / 2f
        val sideLeft = (x + sizePx / 2f) < midX
        val wantSide = if (faceOutward) sideLeft else !sideLeft
        if (lastWantLeft != wantSide) {
            canvasView.invalidate()
            active = true
        }

        // 贴边躲藏：先按当前位置重新判定贴边状态。
        // 不能只信 snapEdge——松手贴边后又被拖走时它会过期，过期会导致在屏幕中间缩出去。
        if (!dragging) {
            val dm = context.resources.displayMetrics
            val slop = SNAP_SLOP_DP * density
            val near = when {
                x <= slop -> -1
                dm.widthPixels - (x + sizePx) <= slop -> 1
                else -> 0
            }
            if (near != snapEdge) {
                snapEdge = near
                if (near == 0 && hidden) setHidden(false)
            }
        }
        // 闲置够久 → 缩进去；位移每帧平滑逼近目标
        if (allowEdgeHide && !hidden && snapEdge != 0 && now - lastInteractMs > HIDE_DELAY_MS) {
            setHidden(true)
        }
        if (hideNowPx != hideTargetPx) {
            val diff = hideTargetPx - hideNowPx
            // HIDE_LERP 是「每 16.67ms 走 18%」，换算成本帧的真实比例，
            // 否则 120Hz 屏上会变成两倍速缩进去、掉帧时又变成慢动作
            val k = 1.0 - Math.pow(1.0 - HIDE_LERP, (dtMs / 16.6667).coerceIn(0.05, 4.0))
            hideNowPx = if (abs(diff) < 1.0) hideTargetPx else hideNowPx + diff * k
            push()
            active = true
        }

        if (squashActive) {
            val u = (now - squashAtMs).toDouble() / Physics.SQ_DURATION_MS
            if (u >= 1.0) {
                squashActive = false
            } else {
                active = true
            }
            canvasView.invalidate()
            active = true
        }

        // 按压压扁 / 回弹还在 220ms 过渡里 → 继续驱动重绘（否则松手后停在半瘪）
        if (SystemClock.uptimeMillis() - pressAnimStartMs < PRESS_TRANSITION_MS) {
            canvasView.invalidate()
            active = true
        }

        // 动画链由 start() 启动、由每段动画的 onEnd 续接；
        // 这里绝不能兜底调用 scheduleNext()——解码是异步的，每帧重触发会不停取消上一段解码。
        return active
    }

    fun start() {
        val (ax, ay) = anchorPixel()
        x = ax
        y = ay
        lastInteractMs = SystemClock.uptimeMillis()
        push()
        playIdle()
    }

    fun release() {
        loadToken++
        (current as? AnimatedImageDrawable)?.stop()
        current = null
    }

    /** 点播指定动画（长按动作菜单用），播完自动回到随机链 */
    fun playAction(slug: String) {
        wake()
        mode = Mode.IDLE
        dragging = false
        pressed = false
        forcedNoMirror = false
        play(slug) { scheduleNext() }
    }

    /** 左右翻转朝向：切换「面朝外侧 / 面朝中间」两套规则 */
    fun flip() {
        faceOutward = !faceOutward
        facing = if (facing == 1) -1 else 1
        forcedNoMirror = false
        canvasView.invalidate()
    }

    // ---------------------------------------------------------------- 画布

    inner class PetCanvas(ctx: Context) : View(ctx), Drawable.Callback {
        init {
            setWillNotDraw(false)
        }

        fun updateLayoutSize(w: Int, h: Int) {
            // 注意：View.setLayoutParams(null) 会抛 NPE，而视图挂到 WindowManager 之前
            // layoutParams 就是 null。窗口尺寸由 Service 的 WindowManager.LayoutParams 决定，
            // 这里只在已存在 LP 时同步一下，绝不能让 null 回流。
            val lp = layoutParams ?: return
            lp.width = w
            lp.height = h
            layoutParams = lp
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = current ?: return
            val w = width.toFloat()
            val h = height.toFloat()
            // ⚠ 这里必须用 this@Pet.x：PetCanvas 是 inner class，直接写 x 会被解析成
            // View.getX()（也就是这个 View 在父容器里的位置，恒为 0），
            // 结果就是"永远判成在左半屏"、朝向永远不翻转 —— 之前所有"贴右边仍朝右"
            // 的现象都是这一行造成的。y 同理。
            val petX = this@Pet.x
            val wantLeft = wantLeftNow()
            val mirror = !forcedNoMirror && wantLeft
            if (lastWantLeft != wantLeft) {
                lastWantLeft = wantLeft
                Diag.record(
                    context,
                    "朝向 wantLeft=$wantLeft mirror=$mirror mode=$mode facing=$facing " +
                        "x=${petX.toInt()} mid=${context.resources.displayMetrics.widthPixels / 2} " +
                        "outward=$faceOutward"
                )
            }
            var sy = 1.0
            if (squashActive) {
                val u = ((SystemClock.uptimeMillis() - squashAtMs).toDouble() / Physics.SQ_DURATION_MS)
                    .coerceIn(0.0, 1.0)
                sy = Physics.squashScale(u, squashAmount)
            }
            // 按压压扁：上游 SQUISH = scaleY(0.88) scaleX(1.05)；松手回 scale(1,1)。
            // pp 的过冲（<0 / >1）就是回弹时那一下"鼓回来"。
            val pp = pressProgress()
            val sx = 1f + (SQUISH_X - 1f) * pp
            sy *= (1f + (SQUISH_Y - 1f) * pp)
            canvas.save()
            canvas.translate(w / 2f, h)          // 以脚底中点为锚点
            canvas.scale((if (mirror) -1f else 1f) * sx, sy.toFloat())
            canvas.translate(-w / 2f, -h)
            d.setBounds(0, 0, width, height)
            d.draw(canvas)
            canvas.restore()
        }

        // Drawable.Callback：动画帧推进时重绘
        override fun invalidateDrawable(who: Drawable) = invalidate()
        override fun scheduleDrawable(who: Drawable, what: Runnable, whenMs: Long) {
            postDelayed(what, whenMs)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            removeCallbacks(what)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val rawX = event.rawX.toDouble()
            val rawY = event.rawY.toDouble()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    Log.i(TAG, "-> down raw=(${rawX.toInt()},${rawY.toInt()}) win=(${windowX().toInt()},${this@Pet.y.toInt()})")
                    wake()
                    downRawX = rawX
                    downRawY = rawY
                    downAtMs = nowMs()
                    grabDX = rawX - windowX()
                    grabDY = rawY - this@Pet.y
                    dragging = false
                    pressed = true
                    Sfx.press()
                    // 上游 pressDown()
                    pressDownAtMs = SystemClock.uptimeMillis()
                    startPress(1f)
                    longPressFired = false
                    canvasView.removeCallbacks(longPressRunnable)
                    canvasView.postDelayed(longPressRunnable, 450L)
                    mode = Mode.IDLE
                    trail.clear()
                    trail.add(DragSample(downAtMs, rawX, rawY))
                    body = Body(this@Pet.x, this@Pet.y, 0.0, 0.0)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    trail.add(DragSample(nowMs(), rawX, rawY))
                    if (trail.size > 64) trail.removeAt(0)
                    val dist = Math.hypot(rawX - downRawX, rawY - downRawY)
                    if (!dragging && dist > DRAG_SLOP_DP * density) {
                        dragging = true
                        dragFrames = 0
                        dragLagSumMs = 0L
                        pressed = false   // 一旦拖动就不再算长按
                        canvasView.removeCallbacks(longPressRunnable)
                        // 拖起来的瞬间把「贴边缩进去」的位移并进 x：
                        // 之后跟手就是纯 1:1，不会出现「她在缩、窗口在追」的双重位移。
                        // （windowX() = x + hideNowPx，这里加进去再清零，视觉位置不变）
                        if (hideNowPx != 0.0) {
                            this@Pet.x += hideNowPx
                            hideNowPx = 0.0
                            hideTargetPx = 0.0
                        }
                        forcedNoMirror = false
                        playDragIfAny()
                    }
                    if (dragging) {
                        mode = Mode.DRAG
                        dragVX = 0.0
                        dragVY = 0.0
                        // 关键：在**触摸回调里**直接改位置并立刻提交给 WindowManager。
                        // 以前只在这里记一个 target，真正挪窗口要等下一次 tick
                        // （handler 16ms + 频移 + 弹簧惯性 ≈ 100ms）—— 这就是跟手延迟。
                        dragTo(rawX - grabDX, rawY - grabDY)
                        dragFrames++
                        dragLagSumMs += (SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0L)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    Log.i(TAG, "-> up drag=$dragging long=$longPressFired")
                    if (dragging && dragFrames > 0) {
                        // 自检用：正常应该是 1~3 帧 + 8~20ms（硬件事件 → 提交），
                        // 以前带弹簧的那版这一项在 60~120ms。
                        Log.i(
                            TAG,
                            "拖拽跟手：帧=$dragFrames 平均=${dragLagSumMs / dragFrames}ms " +
                                "(输入→提交，0 表示同一帧内完成)",
                        )
                    }
                    pressed = false
                    canvasView.removeCallbacks(longPressRunnable)
                    // 上游 pressUp()：极短点按先"补提交"一次压扁，再回弹，否则 30ms 的点击看不见
                    if (SystemClock.uptimeMillis() - pressDownAtMs < PRESS_TAP_GUARD_MS) {
                        pressFromP = 1f
                        pressToP = 1f
                        pressAnimStartMs = SystemClock.uptimeMillis() - PRESS_TRANSITION_MS
                    }
                    startPress(0f)
                    val now = nowMs()
                    if (longPressFired) {
                        // 长按已经弹出动作菜单，抬手不再触发点击回应
                        longPressFired = false
                        dragging = false
                        return true
                    }
                    // 拖拽结束时把残余的躲藏位移并进 x，避免松手后她自己往边上滑
                    if (dragging && hideNowPx != 0.0) {
                        this@Pet.x += hideNowPx
                        hideNowPx = 0.0
                        hideTargetPx = 0.0
                    }
                    if (!dragging) {
                        Sfx.release()
                        onTap()
                    } else {
                        val trimmed = Physics.trimTrail(trail, now)
                        val v = Physics.estimateReleaseVelocity(trimmed, now, cfg.physics)
                        if (v == null) {
                            mode = Mode.IDLE
                            snapIfNearEdge()
                            playIdle()
                            onSettled?.invoke()
                        } else {
                            startThrow(v.first, v.second)
                            onThrown?.invoke()
                        }
                    }
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    canvasView.removeCallbacks(longPressRunnable)
                    dragging = false
                    mode = Mode.IDLE
                    startPress(0f)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}