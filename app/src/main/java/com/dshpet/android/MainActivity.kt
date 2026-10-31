package com.dshpet.android

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.dshpet.android.btbu.BtbuSync

/** 「导出到文件」的请求码 */
private const val REQ_LINES_EXPORT = 1002

/** 「从文件导入」的请求码 */
private const val REQ_LINES_IMPORT = 1003

/**
 * 设置界面：授权 + 开关 + 大小 + 只数 + 碎碎念/贴边躲藏 + 音效 + 台词库。
 * 刻意不用任何第三方 UI 库（零依赖构建，包体只有素材）。
 */
class MainActivity : Activity() {

    private lateinit var petName: TextView
    private lateinit var status: TextView
    private lateinit var statusOverlay: TextView
    private lateinit var statusNotif: TextView
    private lateinit var statusRun: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var btnSound: TextView
    private lateinit var btnPets: TextView
    private lateinit var btnBubble: TextView
    private lateinit var btnEdge: TextView
    private lateinit var btnKeepAlive: TextView
    private lateinit var linesPath: TextView
    private lateinit var btnLinesEdit: TextView
    private lateinit var btnLinesExport: TextView
    private lateinit var btnLinesImport: TextView
    private lateinit var btnLinesReload: TextView
    private lateinit var accUser: EditText
    private lateinit var accPass: EditText
    private lateinit var btnBtbuLogin: TextView
    private lateinit var btnBtbuHud: TextView
    private lateinit var accStatus: TextView
    private lateinit var btnBtbuSchedule: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        petName = findViewById(R.id.petName)
        status = findViewById(R.id.status)
        statusOverlay = findViewById(R.id.statusOverlay)
        statusNotif = findViewById(R.id.statusNotif)
        statusRun = findViewById(R.id.statusRun)
        sizeLabel = findViewById(R.id.sizeLabel)
        seek = findViewById(R.id.seekSize)
        btnSound = findViewById(R.id.btnSound)
        btnPets = findViewById(R.id.btnPets)
        btnBubble = findViewById(R.id.btnBubble)
        btnEdge = findViewById(R.id.btnEdge)
        btnKeepAlive = findViewById(R.id.btnKeepAlive)
        linesPath = findViewById(R.id.linesPath)
        btnLinesEdit = findViewById(R.id.btnLinesEdit)
        btnLinesExport = findViewById(R.id.btnLinesExport)
        btnLinesImport = findViewById(R.id.btnLinesImport)
        btnLinesReload = findViewById(R.id.btnLinesReload)

        accUser = findViewById(R.id.accUser)
        accPass = findViewById(R.id.accPass)
        btnBtbuLogin = findViewById(R.id.btnBtbuLogin)
        btnBtbuHud = findViewById(R.id.btnBtbuHud)
        accStatus = findViewById(R.id.accStatus)
        btnBtbuSchedule = findViewById(R.id.btnBtbuSchedule)
        bindBtbu()

        // 宠物名字来自 pet-config.json（现在是「大肥鱼」）
        try {
            PetConfig.load(this).pets.firstOrNull()?.let {
                if (it.name.isNotBlank()) petName.text = it.name
            }
        } catch (_: Throwable) {
        }

        (findViewById<TextView>(R.id.versionTag))?.text = versionName()

        val sizeDp = Prefs.sizeDp(this)
        seek.max = Prefs.MAX_SIZE_DP - Prefs.MIN_SIZE_DP
        seek.progress = sizeDp - Prefs.MIN_SIZE_DP
        sizeLabel.text = getString(R.string.size_value, sizeDp)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val dp = Prefs.MIN_SIZE_DP + progress
                sizeLabel.text = getString(R.string.size_value, dp)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val dp = Prefs.MIN_SIZE_DP + (sb?.progress ?: 0)
                Prefs.setSizeDp(this@MainActivity, dp)
                reloadIfRunning()
                refresh()
            }
        })

        findViewById<TextView>(R.id.btnOverlay).setOnClickListener {
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            try {
                startActivity(i)
            } catch (_: Throwable) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        }

        findViewById<TextView>(R.id.btnNotif).setOnClickListener { requestNotif() }

        // 点击音效开关：立即生效，并给一声试听
        btnSound.setOnClickListener {
            val on = !Prefs.soundOn(this)
            Prefs.setSoundOn(this, on)
            Sfx.enabled = on
            chipSound(on)
            if (on) Sfx.press()
        }

        // 只数：1 → 2 → 3 → 1，改了要重启悬浮窗才生效
        btnPets.setOnClickListener {
            val cur = Prefs.petCount(this)
            val next = if (cur >= Prefs.MAX_PETS) Prefs.MIN_PETS else cur + 1
            Prefs.setPetCount(this, next)
            chipPets(next)
            reloadIfRunning()
        }

        // 碎碎念开关
        btnBubble.setOnClickListener {
            val on = !Prefs.bubbleOn(this)
            Prefs.setBubbleOn(this, on)
            chipToggle(btnBubble, on)
        }

        // 贴边躲藏开关
        btnEdge.setOnClickListener {
            val on = !Prefs.edgeHideOn(this)
            Prefs.setEdgeHideOn(this, on)
            chipToggle(btnEdge, on)
            reloadIfRunning()
        }

        // 后台保活开关：从最近任务隐藏 + 申请电池优化豁免
        btnKeepAlive.setOnClickListener {
            val on = !Prefs.keepAlive(this)
            Prefs.setKeepAlive(this, on)
            chipToggle(btnKeepAlive, on)
            applyKeepAlive(on, true)
            Sfx.press()
        }

        // 台词库：在 app 里直接改（按情境分类，一行一句）
        btnLinesEdit.setOnClickListener {
            startActivity(Intent(this, LinesActivity::class.java))
            Sfx.press()
        }

        // 台词库：导出到用户挑的位置（可以用电脑或别的编辑器批量改）
        btnLinesExport.setOnClickListener {
            try {
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "lines.json"),
                    REQ_LINES_EXPORT,
                )
            } catch (t: Throwable) {
                Diag.record(this, "打开台词导出选择器失败", t)
                Toast.makeText(this, getString(R.string.lines_export_fail), Toast.LENGTH_SHORT).show()
            }
        }

        // 台词库：从文件导入（把导出的那份改完再导回来）
        btnLinesImport.setOnClickListener {
            try {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*"),
                    REQ_LINES_IMPORT,
                )
            } catch (t: Throwable) {
                Diag.record(this, "打开台词导入选择器失败", t)
                Toast.makeText(this, getString(R.string.lines_import_fail), Toast.LENGTH_SHORT).show()
            }
        }

        // 台词库：重新加载（改完点这里，立即生效）
        btnLinesReload.setOnClickListener {
            Lines.reload()
            val l = Lines.get(this)
            if (l.source.startsWith("files/")) {
                Toast.makeText(
                    this,
                    getString(R.string.lines_reloaded, l.count(), l.source),
                    Toast.LENGTH_SHORT,
                ).show()
                reloadIfRunning()
            } else {
                Toast.makeText(this, getString(R.string.lines_reload_fail), Toast.LENGTH_LONG).show()
            }
            refreshLines()
        }

        findViewById<TextView>(R.id.btnStart).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                showHint(getString(R.string.need_overlay))
                return@setOnClickListener
            }
            hideHint()
            if (Build.VERSION.SDK_INT >= 33 && !hasNotif()) requestNotif()
            Prefs.setRunning(this, true)
            PetService.start(this)
            status.postDelayed({ refresh() }, 300)
        }

        findViewById<TextView>(R.id.btnStop).setOnClickListener {
            Prefs.setRunning(this, false)
            PetService.stop(this)
            status.postDelayed({ refresh() }, 300)
        }

        // 状态标签本身也能点，直接跳授权
        statusOverlay.setOnClickListener { findViewById<TextView>(R.id.btnOverlay).performClick() }
        statusNotif.setOnClickListener { findViewById<TextView>(R.id.btnNotif).performClick() }

        refreshLines()
    }

    override fun onResume() {
        super.onResume()
        // 每次回到设置页都重新对齐一次「从最近任务隐藏」；电池优化豁免只自动问一次
        val keep = Prefs.keepAlive(this)
        applyKeepAlive(keep, keep && !Prefs.keepAliveAsked(this))
        refresh()
        refreshLines()
    }

    /** 尺寸/只数/躲藏开关只有重建悬浮窗才生效 */
    private fun reloadIfRunning() {
        if (Prefs.running(this) && Settings.canDrawOverlays(this)) {
            PetService.reload(this)
        }
    }

    /** 显示当前生效的台词库来源与条数 */
    private fun refreshLines() {
        val l = try {
            Lines.get(this)
        } catch (_: Throwable) {
            null
        }
        val where = when {
            l == null -> Lines.externalFile(this).absolutePath
            l.source.startsWith("files/") -> Lines.externalFile(this).absolutePath
            else -> getString(R.string.lines_builtin)
        }
        linesPath.text = getString(R.string.lines_path, where, l?.count() ?: 0)
    }

    private fun versionName(): String = try {
        val pm = packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
        "v" + (info.versionName ?: "1.6")
    } catch (_: Throwable) {
        "v1.6"
    }

    /** 「导出到文件」「从文件导入」的回调 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQ_LINES_EXPORT -> {
                val ok = try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(Lines.currentJson(this).toByteArray(Charsets.UTF_8))
                        true
                    } ?: false
                } catch (t: Throwable) {
                    Diag.record(this, "台词导出失败", t)
                    false
                }
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.lines_export_ok, uri.lastPathSegment ?: "所选位置")
                    else getString(R.string.lines_export_fail),
                    Toast.LENGTH_LONG,
                ).show()
            }

            REQ_LINES_IMPORT -> {
                val text = try {
                    contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                } catch (t: Throwable) {
                    Diag.record(this, "台词导入读取失败", t)
                    null
                }
                if (text != null && Lines.importJson(this, text)) {
                    val l = Lines.get(this)
                    Toast.makeText(
                        this,
                        getString(R.string.lines_import_ok, l.count()),
                        Toast.LENGTH_SHORT,
                    ).show()
                    PetService.linesChanged(this)
                    refreshLines()
                } else {
                    Toast.makeText(this, getString(R.string.lines_import_fail), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun hasNotif(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requestNotif() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun showHint(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun hideHint() {
        status.visibility = View.GONE
    }

    private fun chip(v: TextView, ok: Boolean, onText: Int, offText: Int) {
        v.text = getString(if (ok) onText else offText)
        v.setBackgroundResource(if (ok) R.drawable.bg_chip_ok else R.drawable.bg_chip_warn)
        v.setTextColor(if (ok) Color.parseColor("#6BE3B0") else Color.parseColor("#FFC46B"))
    }

    /** 开/关两态胶囊（音效、碎碎念、贴边躲藏共用） */
    private fun chipToggle(v: TextView, on: Boolean) {
        v.text = getString(if (on) R.string.sound_on else R.string.sound_off)
        v.setBackgroundResource(if (on) R.drawable.bg_chip_ok else R.drawable.bg_chip_off)
        v.setTextColor(if (on) Color.parseColor("#6BE3B0") else Color.parseColor("#B9A6FF"))
    }

    private fun chipSound(on: Boolean) = chipToggle(btnSound, on)

    private fun chipPets(n: Int) {
        btnPets.text = getString(R.string.pets_value, n)
        val multi = n > 1
        btnPets.setBackgroundResource(if (multi) R.drawable.bg_chip_ok else R.drawable.bg_chip_off)
        btnPets.setTextColor(if (multi) Color.parseColor("#6BE3B0") else Color.parseColor("#B9A6FF"))
    }

    private fun refresh() {
        chip(statusOverlay, Settings.canDrawOverlays(this), R.string.chip_ok, R.string.chip_no)
        chip(statusNotif, hasNotif(), R.string.chip_ok, R.string.chip_no)

        val running = Prefs.running(this) && Settings.canDrawOverlays(this)
        statusRun.text = getString(if (running) R.string.chip_on_state else R.string.chip_off_state)
        statusRun.setBackgroundResource(if (running) R.drawable.bg_chip_ok else R.drawable.bg_chip_off)
        statusRun.setTextColor(
            if (running) Color.parseColor("#6BE3B0") else Color.parseColor("#B9A6FF")
        )

        sizeLabel.text = getString(R.string.size_value, Prefs.sizeDp(this))
        chipSound(Prefs.soundOn(this))
        chipToggle(btnBubble, Prefs.bubbleOn(this))
        chipToggle(btnEdge, Prefs.edgeHideOn(this))
        chipPets(Prefs.petCount(this))
        try {
            renderBtbuStatus()
        } catch (_: Throwable) {
        }
        chipToggle(btnKeepAlive, Prefs.keepAlive(this))
    }

    // ------------------------------------------------------------ 北工商：账号 / 同步 / 小卡

    private fun bindBtbu() {
        accUser.setText(Prefs.accUser(this))
        accPass.setText(Prefs.accPass(this))
        chipToggle(btnBtbuHud, Prefs.hudOn(this))
        renderBtbuStatus()

        btnBtbuLogin.setOnClickListener {
            val u = accUser.text.toString().trim()
            val p = accPass.text.toString()
            if (u.isEmpty() || p.isEmpty()) {
                Toast.makeText(this, "学号 / 密码都要填", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnBtbuLogin.isEnabled = false
            btnBtbuLogin.text = "登录中…"
            // 先验登录，成功再落盘：避免把错密码存下来反复重试把账号刷锁
            BtbuSync.loginTest(this, u, p) { name, err ->
                btnBtbuLogin.isEnabled = true
                btnBtbuLogin.text = "登录并同步"
                if (err != null) {
                    accStatus.text = "登录失败：$err"
                    Toast.makeText(this, "登录失败：$err", Toast.LENGTH_LONG).show()
                    return@loginTest
                }
                accStatus.text = "登录成功（$name），正在拉数据…"
                BtbuSync.refreshAsync(this) { ok ->
                    renderBtbuStatus()
                    Toast.makeText(
                        this,
                        if (ok) "同步完成" else "同步失败：${BtbuSync.lastError ?: "未知"}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

        btnBtbuSchedule.setOnClickListener {
            try {
                startActivity(android.content.Intent(this, com.dshpet.android.btbu.ScheduleActivity::class.java))
            } catch (t: Throwable) {
                Diag.record(this, "打开课表失败", t)
            }
        }

        // 打开设置页就顺手同步一次（静默，失败不打扰）
        if (BtbuSync.configured(this)) {
            BtbuSync.refreshAsync(this) { renderBtbuStatus() }
        }

        btnBtbuHud.setOnClickListener {
            val on = !Prefs.hudOn(this)
            Prefs.setHudOn(this, on)
            chipToggle(btnBtbuHud, on)
            // 桌宠在跑才需要通知它重建窗口
            if (Prefs.running(this)) PetService.reload(this)
        }
    }

    private fun renderBtbuStatus() {
        val st = try {
            BtbuSync.state(this)
        } catch (t: Throwable) {
            null
        }
        accStatus.text = when {
            st == null || !st.hasData ->
                if (BtbuSync.configured(this)) "已存账号，还没拉到数据（点上面按钮）" else "还没配账号"
            else -> {
                val min = ((System.currentTimeMillis() - st.syncedAt) / 60_000L).toInt()
                "第${st.week}周 · ${st.balanceLine()} · 同步于 " +
                    (if (min < 1) "刚刚" else "${min}分钟前") +
                    "\n" + st.nextLine()
            }
        }
    }

    /**
     * 后台保活：把本任务从「最近任务」列表里隐藏，清理后台时就不容易被误删。
     * askBattery=true 时顺带申请「忽略电池优化」（只在用户点开关时问一次）。
     */
    private fun applyKeepAlive(on: Boolean, askBattery: Boolean) {
        try {
            getSystemService(ActivityManager::class.java)?.appTasks?.forEach {
                it.setExcludeFromRecents(on)
            }
        } catch (t: Throwable) {
            Diag.record(this, "设置 excludeFromRecents 失败", t)
        }
        if (!on || !askBattery) return
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm?.isIgnoringBatteryOptimizations(packageName) == true) return
            Prefs.setKeepAliveAsked(this, true)
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (t: Throwable) {
            Diag.record(this, "申请电池优化豁免失败", t)
        }
    }
}