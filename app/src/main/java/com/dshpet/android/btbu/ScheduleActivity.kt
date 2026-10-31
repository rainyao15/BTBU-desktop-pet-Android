package com.dshpet.android.btbu

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 整周课表页：7 列 × N 节 的网格，程序化搭的（不为它加一份 XML，改起来更快）。
 *
 * 数据全部来自 BtbuStore 的本地缓存，所以**断网也能看**；顶栏可切周（±1），
 * 默认落在当前教学周，课程按 skzc 位图过滤，非本周的课不显示。
 */
class ScheduleActivity : Activity() {

    private lateinit var grid: LinearLayout
    private lateinit var title: TextView
    private var week = 1
    private var totalWeek = 25
    private var density = 1f

    private val palette = intArrayOf(
        0xFF35507F.toInt(), 0xFF3F6B5A.toInt(), 0xFF6B4A6F.toInt(), 0xFF7F5A35.toInt(),
        0xFF355F6B.toInt(), 0xFF5A5F35.toInt(), 0xFF6B3548.toInt(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        density = resources.displayMetrics.density

        val store = BtbuStore(this)
        totalWeek = store.totalWeekOf().coerceAtLeast(1)
        week = store.weekOf().coerceIn(1, totalWeek)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B1220.toInt())
            setPadding(dp(10), dp(18), dp(10), dp(10))
        }

        // ---------- 顶栏 ----------
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(button("‹") { switchWeek(-1) })
        title = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFEEF3FF.toInt())
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button("今天") { week = store.weekOf().coerceIn(1, totalWeek); render() })
        bar.addView(button("›") { switchWeek(1) })
        root.addView(bar)

        val term = TextView(this).apply {
            text = store.termOf()
            setTextColor(0xFF7A8BAE.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(term)

        // ---------- 网格 ----------
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hsv = HorizontalScrollView(this).apply { isFillViewport = true }
        val sv = ScrollView(this).apply { addView(grid) }
        hsv.addView(sv)
        root.addView(hsv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        render()
    }

    private fun switchWeek(d: Int) {
        val n = (week + d).coerceIn(1, totalWeek)
        if (n == week) return
        week = n
        render()
    }

    private fun render() {
        val store = BtbuStore(this)
        val courses = store.coursesOf(week)
        val lessons = store.lessonsOf()
        val maxJc = (lessons.keys.maxOrNull() ?: 12).coerceAtLeast(
            courses.maxOfOrNull { it.jcTo } ?: 12,
        )

        title.text = "第 $week 周 / 共 $totalWeek 周"
        grid.removeAllViews()

        // 表头：节次 + 周一…周日
        grid.addView(rowOf(listOf("") + (1..7).map { BtbuStore.wdName(it).removePrefix("周") }, header = true))

        // 把课程铺进 (weekday, jc) 的格子
        val cell = HashMap<Int, Course>()
        for (c in courses) {
            for (jc in c.jcFrom..c.jcTo) {
                cell[c.weekday * 100 + jc] = c
            }
        }

        for (jc in 1..maxJc) {
            val cells = ArrayList<Pair<String, Course?>>()
            cells.add(jc.toString() to null)
            for (wd in 1..7) {
                val c = cell[wd * 100 + jc]
                val label = when {
                    c == null -> ""
                    jc == c.jcFrom -> shortName(c.name)
                    else -> "·"
                }
                cells.add(label to c)
            }
            grid.addView(rowOf(cells, header = false, jc = jc, lessons = lessons))
        }

        if (courses.isEmpty()) {
            grid.addView(TextView(this).apply {
                text = "这周没课（或者还没同步过课表）"
                setTextColor(0xFF7A8BAE.toInt())
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, 0)
            })
        }
    }

    private fun rowOf(cells: List<Any>, header: Boolean, jc: Int = 0,
                      lessons: Map<Int, Pair<String, String>> = emptyMap()): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, raw) in cells.withIndex()) {
            val tv = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = if (header) 11f else 9.5f
                setTextColor(if (header) 0xFF9DB1D5.toInt() else 0xFFEEF3FF.toInt())
                maxLines = if (header) 1 else 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(2), if (header) dp(4) else dp(6), dp(2), if (header) dp(4) else dp(6))
            }
            val lp = LinearLayout.LayoutParams(
                if (i == 0) dp(26) else dp(46),
                if (header) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) }

            if (header) {
                tv.text = raw as String
            } else {
                val pair = raw as Pair<*, *>
                val label = pair.first as String
                val c = pair.second as Course?
                tv.text = label
                if (c != null) {
                    val color = palette[Math.abs(c.name.hashCode()) % palette.size]
                    tv.background = GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        setColor(color)
                    }
                    if (jc == c.jcFrom) {
                        tv.setOnClickListener {
                            Toast.makeText(
                                this,
                                "${c.name}\n${c.room} · ${c.teacher}\n第${c.jcFrom}-${c.jcTo}节 ${c.start}-${c.end}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                } else if (jc % 2 == 1) {
                    tv.background = GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        setColor(0x14FFFFFF)
                    }
                }
            }
            row.addView(tv, lp)
        }
        return row
    }

    /** 课名太长就在格子里只留前几个字，点开看全名 */
    private fun shortName(s: String): String = if (s.length <= 4) s else s.substring(0, 4)

    private fun button(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFFB9A6FF.toInt())
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(0x33FFFFFF)
        }
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * density).toInt()
}
