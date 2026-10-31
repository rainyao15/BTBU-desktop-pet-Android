package com.dshpet.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * 本地台词库（碎碎念）。
 *
 * 读取顺序：`Android/data/com.dshpet.android/files/lines.json`（**用户自己改的就是这个**）
 * → 读不到或者格式不对，回落到内置的 `assets/lines.json`。
 *
 * 纯离线，不联网；同一个情境里最近说过的 12 句不会再出现，
 * 所以连续触发也不会一直听到同一句。
 */
class Lines private constructor(
    private val map: MutableMap<String, List<String>>,
    /** 实际生效的来源，设置页会显示出来 */
    var source: String,
) {

    /** 每个类别最近说过的句子（FIFO），用于去重 */
    private val recent = HashMap<String, ArrayDeque<String>>()

    fun has(category: String): Boolean = !map[category].isNullOrEmpty()

    fun count(): Int = map.values.sumOf { it.size }

    fun countOf(category: String): Int = map[category]?.size ?: 0

    fun linesOf(category: String): List<String> = map[category] ?: emptyList()

    /** 有台词的类别：先按内置顺序排，多出来的排在后面 */
    fun categories(): List<String> {
        val out = ArrayList<String>()
        for (k in CATEGORY_ORDER) if (!map[k].isNullOrEmpty()) out.add(k)
        for (k in map.keys) if (k !in out && !map[k].isNullOrEmpty()) out.add(k)
        return out
    }

    /** 取一句该情境的台词；类别不存在或为空时返回 null */
    fun pick(category: String): String? {
        val list = map[category] ?: return null
        if (list.isEmpty()) return null
        if (list.size == 1) return list[0]

        val seen = recent.getOrPut(category) { ArrayDeque() }
        val keep = minOf(12, list.size - 1)
        var text = list[Random.nextInt(list.size)]
        var tries = 0
        while (text in seen && tries < 20) {
            text = list[Random.nextInt(list.size)]
            tries++
        }
        seen.addLast(text)
        while (seen.size > keep) seen.removeFirst()
        return text
    }

    /** 保存某一类台词：自动去空白、去重复；传空列表 = 这一类不再说话 */
    fun save(ctx: Context, category: String, list: List<String>) {
        val clean = ArrayList<String>()
        for (s in list) {
            val t = s.trim()
            if (t.isNotEmpty() && !clean.contains(t)) clean.add(t)
        }
        if (clean.isEmpty()) map.remove(category) else map[category] = clean
        recent.remove(category)
        persist(ctx, "保存「${labelOf(category)}」${clean.size} 句")
    }

    /** 把这一类恢复成内置台词 */
    fun restoreCategory(ctx: Context, category: String) {
        val builtin = builtinMap(ctx)[category] ?: return
        map[category] = builtin
        recent.remove(category)
        persist(ctx, "「${labelOf(category)}」恢复内置 ${builtin.size} 句")
    }

    /** 全部恢复内置：直接删掉自定义文件 */
    fun restoreAll(ctx: Context) {
        try {
            externalFile(ctx).delete()
        } catch (t: Throwable) {
            Diag.record(ctx, "删除自定义台词失败", t)
        }
        map.clear()
        map.putAll(builtinMap(ctx))
        recent.clear()
        source = "assets/$ASSET_NAME"
        Diag.record(ctx, "台词库已全部恢复内置：${count()} 句")
    }

    /** 写回 `files/lines.json`（这就是「保存」的全部动作） */
    private fun persist(ctx: Context, what: String) {
        try {
            val cats = JSONObject()
            for (k in map.keys) {
                val arr = JSONArray()
                map[k]?.forEach { arr.put(it) }
                cats.put(k, arr)
            }
            val root = JSONObject()
            root.put("version", 1)
            root.put("categories", cats)

            val f = externalFile(ctx)
            f.parentFile?.mkdirs()
            f.writeText(root.toString(1))
            source = "files/$FILE_NAME"
            Diag.record(ctx, "台词已保存：$what · 共 ${count()} 句 → ${f.absolutePath}")
        } catch (t: Throwable) {
            Diag.record(ctx, "台词保存失败：$what", t)
        }
    }

    companion object {
        const val ASSET_NAME = "lines.json"
        const val FILE_NAME = "lines.json"

        /** 设置页/编辑页里类别的排列顺序 */
        val CATEGORY_ORDER = listOf(
            "greet", "idle", "tapped", "drag", "morning", "noon",
            "night", "late", "edge", "summon", "busy",
        )

        private val LABELS = mapOf(
            "greet" to "打招呼", "idle" to "自言自语", "tapped" to "被连点",
            "drag" to "被拖／甩出", "morning" to "早上", "noon" to "中午",
            "night" to "晚上", "late" to "深夜", "edge" to "贴边躲藏",
            "summon" to "让她说句话", "busy" to "久没人理",
        )

        fun labelOf(category: String): String = LABELS[category] ?: category

        @Volatile
        private var cached: Lines? = null

        /** 用户可以自己改的台词文件：`Android/data/com.dshpet.android/files/lines.json` */
        fun externalFile(ctx: Context): File =
            File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, FILE_NAME)

        /** 丢掉缓存，下一次 get() 重新读盘 */
        fun reload() {
            cached = null
        }

        /** 把内置台词导出成模板文件；`overwrite=false` 时文件已存在就不覆盖 */
        fun exportTemplate(ctx: Context, overwrite: Boolean = false): File {
            val f = externalFile(ctx)
            try {
                if (!f.exists() || overwrite) {
                    f.parentFile?.mkdirs()
                    ctx.assets.open(ASSET_NAME).use { input ->
                        f.outputStream().use { out -> input.copyTo(out) }
                    }
                }
            } catch (t: Throwable) {
                Diag.record(ctx, "台词模板导出失败", t)
            }
            return f
        }

        /** 当前生效的台词原文（自定义文件优先，否则内置），用于「导出到文件」 */
        fun currentJson(ctx: Context): String {
            val f = externalFile(ctx)
            if (f.isFile) {
                try {
                    return f.readText()
                } catch (t: Throwable) {
                    Diag.record(ctx, "读取自定义台词失败", t)
                }
            }
            return assetText(ctx)
        }

        /** 从用户选的文件导入台词（SAF）；成功返回 true */
        fun importJson(ctx: Context, text: String): Boolean {
            val m = parseMap(text) ?: return false
            if (m.isEmpty()) return false
            return try {
                val f = externalFile(ctx)
                f.parentFile?.mkdirs()
                f.writeText(text)
                reload()
                Diag.record(ctx, "台词库已从文件导入：${m.values.sumOf { it.size }} 句")
                true
            } catch (t: Throwable) {
                Diag.record(ctx, "台词库导入失败", t)
                false
            }
        }

        fun get(ctx: Context): Lines {
            cached?.let { return it }

            // 1) 先看用户自己改的那份
            val ext = externalFile(ctx)
            var parsed: Lines? = null
            if (ext.isFile) {
                val text = try {
                    ext.readText()
                } catch (t: Throwable) {
                    Diag.record(ctx, "外部台词库读取失败", t)
                    ""
                }
                parsed = parse(text, "files/$FILE_NAME")
                if (parsed == null && text.isNotBlank()) {
                    Diag.record(ctx, "外部台词库格式不对，这次先用内置台词")
                }
            }

            // 2) 回落到内置
            val l = parsed
                ?: parse(assetText(ctx), "assets/$ASSET_NAME")
                ?: Lines(HashMap(), "空")
            cached = l
            Diag.record(ctx, "台词库就绪：${l.count()} 句，来源 ${l.source}")
            return l
        }

        /** 内置那份的类别 → 台词，用于「恢复默认」 */
        fun builtinMap(ctx: Context): Map<String, List<String>> = parseMap(assetText(ctx)) ?: emptyMap()

        private fun assetText(ctx: Context): String = try {
            ctx.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            Diag.record(ctx, "lines.json 读取失败", t)
            ""
        }

        private fun parseMap(text: String): Map<String, List<String>>? {
            if (text.isBlank()) return null
            return try {
                val cats = JSONObject(text).optJSONObject("categories") ?: return null
                val out = HashMap<String, List<String>>()
                for (k in cats.keys()) {
                    val arr = cats.optJSONArray(k) ?: continue
                    val list = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i, "").trim()
                        if (s.isNotEmpty()) list.add(s)
                    }
                    if (list.isNotEmpty()) out[k] = list
                }
                out
            } catch (t: Throwable) {
                null
            }
        }

        /** 解析台词 json；解析不出来返回 null，好让上层回落到内置那份 */
        private fun parse(text: String, source: String): Lines? {
            val m = parseMap(text) ?: return null
            return if (m.isEmpty()) null else Lines(HashMap(m), source)
        }
    }
}