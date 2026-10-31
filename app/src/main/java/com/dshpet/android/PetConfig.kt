package com.dshpet.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一只宠物的配置（对应 config.jsonc 的 pets[]） */
data class PetSpec(
    val id: String,
    val name: String,
    /** 宠物包围盒宽度（dp，运行时乘 density 得到 px） */
    val size: Int,
    val corner: String,
    val marginX: Int,
    val marginY: Int,
)

/** moves.actions 里的一条：动画 slug + 距离范围（基准 462px 下的像素） */
data class MoveAction(val slug: String, val minDist: Double, val maxDist: Double)

/** 随机动作分类（小动作/玩耍/吃什么/时节/文字） */
data class AnimCategory(
    val id: String,
    val weight: Int,
    val actions: List<String>,
    val noMirror: Boolean,
)

/**
 * 运行期配置：由 tools/gen-config.mjs 从 dsh-pet 的 assets/config.jsonc 生成，
 * 只保留手机端需要的部分（动画池、权重、物理），去掉碎碎念/对话/余额/通知。
 */
class PetConfig(
    val pets: List<PetSpec>,
    val names: Map<String, String>,
    /** 动画 slug → 时长（毫秒）。手机端靠它在"播完一遍"时切下一段。 */
    val durations: Map<String, Int>,
    val idle: List<String>,
    val turn: List<String>,
    val drag: List<String>,
    val clicks: List<String>,
    val moves: List<MoveAction>,
    val moveMinDist: Double,
    val moveMaxDist: Double,
    val moveMargin: Double,
    val categories: List<AnimCategory>,
    val weightIdle: Int,
    val weightTurn: Int,
    val weightMove: Int,
    val physics: PhysicsParams,
) {
    /** slug → 可读动画名（长按菜单里显示） */
    fun nameOf(slug: String): String = names[slug] ?: slug

    /** slug → 该段动画播一遍的毫秒数（配置缺失时给个保守默认） */
    fun durationOf(slug: String): Int = durations[slug] ?: 9000

    companion object {
        /** dsh-pet 的移动距离是「基准宠物宽 462px」下的绝对像素，运行时按 size/462 等比缩放 */
        const val PET_REF_WIDTH = 462.0
        fun load(ctx: Context): PetConfig {
            val text = ctx.assets.open("pet-config.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)

            val pets = ArrayList<PetSpec>()
            val petsArr = root.optJSONArray("pets") ?: JSONArray()
            for (i in 0 until petsArr.length()) {
                val p = petsArr.getJSONObject(i)
                val pos = p.optJSONObject("position") ?: JSONObject()
                pets.add(
                    PetSpec(
                        id = p.optString("id", "main$i"),
                        name = p.optString("name", "宠物"),
                        size = p.optInt("size", 462),
                        corner = pos.optString("corner", "top-right"),
                        marginX = pos.optInt("marginX", 24),
                        marginY = pos.optInt("marginY", 100),
                    )
                )
            }

            val anims = root.getJSONObject("animations")
            val names = HashMap<String, String>()
            root.optJSONObject("names")?.let { n ->
                for (k in n.keys()) names[k] = n.optString(k, k)
            }
            val durations = HashMap<String, Int>()
            root.optJSONObject("durations")?.let { d ->
                for (k in d.keys()) durations[k] = d.optInt(k, 0)
            }
            val idle = anims.stringList("idle")
            val turn = anims.stringList("turn")
            val drag = anims.stringList("drag")
            val clicks = anims.stringList("clicks")

            var moveMin = 60.0
            var moveMax = 240.0
            var moveMargin = 20.0
            val moves = ArrayList<MoveAction>()
            anims.optJSONObject("moves")?.let { mv ->
                mv.optJSONObject("default")?.let { d ->
                    moveMin = d.optDouble("minDist", moveMin)
                    moveMax = d.optDouble("maxDist", moveMax)
                    moveMargin = d.optDouble("margin", moveMargin)
                }
                mv.optJSONArray("actions")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val a = arr.getJSONObject(i)
                        moves.add(
                            MoveAction(
                                slug = a.optString("name", ""),
                                minDist = a.optJSONObject("params")?.optDouble("minDist", moveMin) ?: moveMin,
                                maxDist = a.optJSONObject("params")?.optDouble("maxDist", moveMax) ?: moveMax,
                            )
                        )
                    }
                }
            }

            val cats = ArrayList<AnimCategory>()
            anims.optJSONArray("categories")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    cats.add(
                        AnimCategory(
                            id = c.optString("id", "cat$i"),
                            weight = c.optInt("weight", 0),
                            actions = c.stringList("actions"),
                            noMirror = c.optBoolean("noMirror", false),
                        )
                    )
                }
            }

            val w = root.optJSONObject("animationWeights") ?: JSONObject()
            val ph = root.optJSONObject("physics") ?: JSONObject()

            val cfg = PetConfig(
                pets = pets,
                names = names,
                durations = durations,
                idle = idle,
                turn = turn,
                drag = drag,
                clicks = clicks,
                moves = moves,
                moveMinDist = moveMin,
                moveMaxDist = moveMax,
                moveMargin = moveMargin,
                categories = cats,
                weightIdle = w.optInt("idle", 10),
                weightTurn = w.optInt("turn", 5),
                weightMove = w.optInt("move", 5),
                physics = PhysicsParams(
                    gravity = ph.optDouble("gravity", 1400.0),
                    restitution = ph.optDouble("restitution", 0.78),
                    groundFriction = ph.optDouble("groundFriction", 2.5),
                    ceilingBounce = ph.optBoolean("ceilingBounce", true),
                    throwPower = ph.optDouble("throwPower", 1.0),
                ),
            )
            if (cfg.pets.isEmpty()) throw IllegalStateException("pet-config.json 里没有任何宠物")
            if (cfg.idle.isEmpty() && cfg.categories.all { it.actions.isEmpty() }) {
                throw IllegalStateException("pet-config.json 里没有可用动画")
            }
            return cfg
        }

        private fun JSONObject.stringList(key: String): List<String> {
            val arr = optJSONArray(key) ?: return emptyList()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotEmpty()) out.add(v)
            }
            return out
        }
    }
}
