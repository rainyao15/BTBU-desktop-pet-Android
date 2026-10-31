package com.dshpet.android.btbu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import com.dshpet.android.R

/**
 * 手绘思考气泡（几何 100% 沿用 DSH 鲸鱼挂件 MeteorNOX/DeepSeek-Balance-Whale-Widget, MIT）。
 *
 * 上游那份不是图片素材，是代码里现画的 SVG，画布 1026×700：
 *   - 大椭圆 + 尾巴是同一条连通路径（见 res/drawable/btbu_bubble.xml 顶部注释，坐标一字未改）
 *   - 两个小气泡各自独立（浮在白底上，所以只有自己的描边）
 *   - 描边 #203170 宽 18，round join/cap，填充纯白
 * 排版也照上游比例：文字块中心 (44.25%, 36%)，字号按 u = 画布宽/1026 联动：
 *   label 66u/600、amount 128u/800、hint 56u/800(#9FB0D9)。
 *
 * 和上游唯一的差别：他那边文字是固定三行短文本（¥ 16.73），我们这边长度会变
 * （"网 15.00  卡 25.80" 随时变长），所以多了一步**自动缩字**：量出自然宽度，
 * 塞不进安全框就整体等比缩小，最多迭代 4 次。气泡本体绝不变形 —— 永远 1026:700 等比铺满。
 */
class ThoughtBubble(context: Context) : FrameLayout(context) {

    companion object {
        const val INK = 0xFF203170.toInt()          // 描边/标题色
        const val TEXT_COLOR = 0xFF536BA9.toInt()   // 大数字（上游 .dshwv-text 的 color）
        const val HINT_COLOR = 0xFF9FB0D9.toInt()   // 副行
        const val GREEN = 0xFF3DAD4F.toInt()        // 倒计时药丸

        /** SVG 画布尺寸，和 drawable 的 viewport 必须一致 */
        private const val VW = 1026f
        private const val VH = 700f

        /** 上游字号：都是「画布宽度的千分比」 */
        private const val LABEL_U = 66f / VW
        private const val AMOUNT_U = 128f / VW
        private const val HINT_U = 56f / VW
        private const val CHIP_U = 46f / VW
        private const val TIMER_U = 56f / VW

        /** 文字块中心点（上游 left:44.25% / top:36%） */
        private const val TEXT_CX = 0.4425f
        private const val TEXT_CY = 0.36f

        /**
         * 可用文字框占画布的比例。
         * 坑：SVG 画布 1026×700，而椭圆本体只占 x 81..827 / y 15..479 —— 画布左右是空边。
         * 早先按 0.80 取宽，真机上字直接把描边顶穿了。按椭圆内沿并扣掉描边重算：
         * 宽 (827-81-18*2)/1026 ≈ 0.69、高 (479-15-18*2)/700 ≈ 0.61，各留余量取 0.68 / 0.50。
         */
        private const val TEXT_W = 0.68f
        private const val TEXT_H = 0.50f

        /** 单行最多缩到原字号的 62%（再小就宁可挤一点，也不能看不清） */
        private const val MIN_LINE_SCALE = 0.62f
    }

    /**
     * 气泡底图 = res/drawable/btbu_bubble.xml（照抄上游 SVG 的那套坐标）。
     * 矢量路径万一解析失败（pathData 写错 / 某些 ROM 的脾气），下面有手画圆角矩形兜底，
     * 绝不让整块气泡连着文字一起消失。
     */
    private val frame: Drawable? = try {
        context.getDrawable(R.drawable.btbu_bubble)
    } catch (t: Throwable) {
        null
    }

    private val fallbackFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val fallbackStroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        color = INK
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val fallbackRect = android.graphics.RectF()

    private var label: TextView? = null
    private var amount: TextView? = null
    private var hint: TextView? = null
    private var chip: TextView? = null
    private var timer: Chronometer? = null

    /** 1 个 SVG 单位 = 多少像素 */
    private var unit = 0f

    /** onMeasure 算出来的自动缩字系数（1 = 没有缩） */
    var textScale = 1f
        private set

    fun bind(label: TextView, amount: TextView, hint: TextView) {
        this.label = label
        this.amount = amount
        this.hint = hint
        label.setTextColor(INK)
        label.typeface = Typeface.DEFAULT_BOLD
        label.includeFontPadding = false
        label.gravity = android.view.Gravity.CENTER
        label.letterSpacing = 0.06f

        amount.setTextColor(TEXT_COLOR)
        amount.typeface = Typeface.DEFAULT_BOLD
        amount.includeFontPadding = false
        amount.gravity = android.view.Gravity.CENTER

        hint.setTextColor(HINT_COLOR)
        hint.includeFontPadding = false
        hint.gravity = android.view.Gravity.CENTER
        hint.letterSpacing = 0.02f

        applySizes(textScale)
        requestLayout()
    }

    fun bindCountdown(chip: TextView, timer: Chronometer) {
        this.chip = chip
        this.timer = timer
        chip.setTextColor(0xFFFFFFFF.toInt())
        chip.typeface = Typeface.DEFAULT_BOLD
        chip.includeFontPadding = false
        chip.gravity = android.view.Gravity.CENTER
        timer.setTextColor(GREEN)
        timer.typeface = Typeface.DEFAULT_BOLD
        timer.includeFontPadding = false
        timer.gravity = android.view.Gravity.CENTER
        applySizes(textScale)
        requestLayout()
    }

    /**
     * 把 u 比例换算成像素字号。三个 s 分别是标题/大数字/副行的**各自**缩放系数。
     * 为什么要分开：我们这边的三行长短差得多（"北工商 · 第 8 周" / "网 15.00" / "今天 14:00 毛概 @工3-106"），
     * 统一缩放会让最短那行被最长那行拖到看不清。上游是固定短文本，没这个问题。
     * 气泡没量到宽度之前 unit 是 0，先给个安全的占位，onMeasure 会重算。
     */
    private fun applySizes(sLabel: Float, sAmount: Float, sHint: Float) {
        val k = unit
        label?.setTextSize(TypedValue.COMPLEX_UNIT_PX, k * VW * LABEL_U * sLabel)
        amount?.setTextSize(TypedValue.COMPLEX_UNIT_PX, k * VW * AMOUNT_U * sAmount)
        hint?.setTextSize(TypedValue.COMPLEX_UNIT_PX, k * VW * HINT_U * sHint)

        val timerPx = k * VW * TIMER_U * sHint
        timer?.setTextSize(TypedValue.COMPLEX_UNIT_PX, timerPx)
        val chipPx = k * VW * CHIP_U * sHint
        chip?.setTextSize(TypedValue.COMPLEX_UNIT_PX, chipPx)
        val padH = (chipPx * 0.55f).toInt()
        val padV = (chipPx * 0.18f).toInt()
        chip?.setPadding(padH, padV, padH, padV)
    }

    /** 三条一起缩（bind 时还不知道尺寸，先用 1 倍占位） */
    private fun applySizes(s: Float) = applySizes(s, s, s)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        var w = MeasureSpec.getSize(widthMeasureSpec)
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || w <= 0) {
            w = (200 * density).toInt()
        }
        unit = w / VW
        val h = (w * VH / VW).toInt()

        val child = getChildAt(0)
        if (child == null) {
            setMeasuredDimension(w, h)
            return
        }

        val maxW = (w * TEXT_W).toInt()
        val maxH = (h * TEXT_H).toInt()
        val uns = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val atMostW = MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST)

        // ① 每条按自己的自然宽度决定缩多少（只在超框时才缩）
        applySizes(1f, 1f, 1f)
        var sLabel = 1f
        var sAmount = 1f
        label?.let {
            it.measure(uns, uns)
            if (it.measuredWidth > maxW) {
                sLabel = (maxW.toFloat() / it.measuredWidth).coerceAtLeast(MIN_LINE_SCALE)
            }
        }
        amount?.let {
            it.measure(uns, uns)
            if (it.measuredWidth > maxW) {
                sAmount = (maxW.toFloat() / it.measuredWidth).coerceAtLeast(MIN_LINE_SCALE)
            }
        }
        applySizes(sLabel, sAmount, 1f)

        // ② 整块跑出圈外就再整体压一点（副行可以折行，所以还缩不动时优先压高度）
        var hScale = 1f
        for (i in 0 until 3) {
            child.measure(atMostW, uns)
            val chh = child.measuredHeight
            if (chh <= maxH || chh <= 0) break
            hScale *= (maxH.toFloat() / chh) * 0.98f
            applySizes(sLabel * hScale, sAmount * hScale, hScale)
        }
        child.measure(atMostW, uns)
        textScale = minOf(sLabel, sAmount) * hScale

        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val child = getChildAt(0) ?: return
        val cx = (w * TEXT_CX).toInt()
        val cy = (h * TEXT_CY).toInt()
        val cw = child.measuredWidth
        val ch = child.measuredHeight
        child.layout(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val f = frame
        if (f != null) {
            f.setBounds(0, 0, width, height)
            f.draw(canvas)
        } else {
            fallbackRect.set(1.5f, 1.5f, width - 1.5f, height - 1.5f)
            val r = minOf(width, height) * 0.18f
            canvas.drawRoundRect(fallbackRect, r, r, fallbackFill)
            canvas.drawRoundRect(fallbackRect, r, r, fallbackStroke)
        }
        super.dispatchDraw(canvas)
    }

    /** 气泡尾巴尖（最后那个小气泡底边）离视图顶部的像素距离，上层拿它算贴头顶的偏移 */
    fun tailTipY(): Int = (height * 673f / VH).toInt()

}
