package com.dshpet.android

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 台词库编辑器：按情境分类，一行一句，改完点「保存并生效」。
 *
 * 保存写的是 `Android/data/com.dshpet.android/files/lines.json`，
 * 也就是桌宠真正读的那份文件，所以不需要再点「重新加载」。
 */
class LinesActivity : Activity() {

    private lateinit var lines: Lines
    private lateinit var chipRow: LinearLayout
    private lateinit var editor: EditText
    private lateinit var editorTitle: TextView
    private lateinit var countHint: TextView

    private val chipViews = HashMap<String, TextView>()
    private var current = Lines.CATEGORY_ORDER.first()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lines)
        lines = Lines.get(this)

        chipRow = findViewById(R.id.catChips)
        editor = findViewById(R.id.editor)
        editorTitle = findViewById(R.id.editorTitle)
        countHint = findViewById(R.id.countHint)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { saveCurrent() }
        findViewById<View>(R.id.btnRestoreOne).setOnClickListener { restoreCurrent() }
        findViewById<View>(R.id.btnRestoreAll).setOnClickListener { confirmRestoreAll() }

        buildChips()
        val all = lines.categories()
        select(all.firstOrNull() ?: current)
        Diag.record(this, "打开台词编辑器：${all.size} 类 / ${lines.count()} 句")
    }

    /** 顶部那一排分类胶囊 */
    private fun buildChips() {
        chipRow.removeAllViews()
        chipViews.clear()
        for (cat in Lines.CATEGORY_ORDER) {
            val tv = TextView(this)
            tv.text = Lines.labelOf(cat)
            tv.textSize = 13f
            tv.gravity = Gravity.CENTER
            tv.setPadding(dp(14), dp(9), dp(14), dp(9))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(6)
            tv.layoutParams = lp
            tv.setOnClickListener { select(cat) }
            chipRow.addView(tv)
            chipViews[cat] = tv
        }
    }

    /** 切到某一类：把这一类现在的台词按「一行一句」灌进编辑框 */
    private fun select(cat: String) {
        current = cat
        val list = lines.linesOf(cat)
        editor.setText(list.joinToString("\n"))
        editor.setSelection(editor.text.length)
        editorTitle.text = "${Lines.labelOf(cat)} · ${list.size} 句"
        countHint.text = getString(R.string.lines_editor_count, lines.count())
        for ((k, v) in chipViews) {
            val on = k == cat
            v.setBackgroundResource(if (on) R.drawable.bg_chip_ok else R.drawable.bg_chip_off)
            v.setTextColor(getColor(if (on) R.color.chip_ok_text else R.color.chip_off_text))
        }
    }

    private fun saveCurrent() {
        val list = editor.text.toString()
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        lines.save(this, current, list)
        PetService.linesChanged(this)
        Toast.makeText(
            this,
            getString(R.string.lines_saved, Lines.labelOf(current), lines.countOf(current)),
            Toast.LENGTH_SHORT,
        ).show()
        select(current)
    }

    private fun restoreCurrent() {
        lines.restoreCategory(this, current)
        PetService.linesChanged(this)
        Toast.makeText(this, getString(R.string.lines_restored, Lines.labelOf(current)), Toast.LENGTH_SHORT).show()
        select(current)
    }

    private fun confirmRestoreAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.lines_restore_all)
            .setMessage(R.string.lines_restore_all_msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lines.restoreAll(this)
                PetService.linesChanged(this)
                Toast.makeText(
                    this,
                    getString(R.string.lines_restored_all, lines.count()),
                    Toast.LENGTH_SHORT,
                ).show()
                select(lines.categories().firstOrNull() ?: current)
            }
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}