package com.dshpet.android

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * 点击音效：参考 dsh-whale-widget 的「拍一下」反馈——按下播 `Ya1`，抬手播 `Ya2`。
 * 两个片段都是短 mp3（3–6 KB），SoundPool 预载，播放零延迟、不阻塞主线程。
 */
object Sfx {

    private var pool: SoundPool? = null
    private var pressId = 0
    private var releaseId = 0
    private var ready = false

    /** 总开关：在设置页手动控制，关掉后 press/release 直接静默 */
    @Volatile
    var enabled: Boolean = true

    fun init(ctx: Context) {
        if (pool != null) return
        try {
            val p = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            p.setOnLoadCompleteListener { _, _, status ->
                if (status == 0) ready = true
            }
            // 用 getIdentifier 而不是 R.raw.xxx：这两个 mp3 属于第三方素材、不随仓库分发，
            // 直接写 R.raw.tap_press 会让"干净 clone"编译不过（AAPT 找不到资源）。
            // 没有就静默，有就播——行为不变，但工程随时可编译。
            pressId = p.load(ctx, rawId(ctx, "tap_press"), 1)
            releaseId = p.load(ctx, rawId(ctx, "tap_release"), 1)
            pool = p
        } catch (t: Throwable) {
            Diag.record(ctx, "音效初始化失败: ${t.message}")
        }
    }

    /** 按下 */
    fun press() = play(pressId)

    /** 抬手 */
    fun release() = play(releaseId)

    private fun play(id: Int) {
        if (!enabled) return
        val p = pool ?: return
        if (!ready || id == 0) return
        try {
            p.play(id, 0.85f, 0.85f, 1, 0, 1.0f)
        } catch (_: Throwable) {
            // 音效失败绝不影响交互
        }
    }

    /** 资源不存在时返回 0（SoundPool 会忽略），不抛异常 */
    private fun rawId(ctx: Context, name: String): Int = try {
        ctx.resources.getIdentifier(name, "raw", ctx.packageName)
    } catch (_: Throwable) {
        0
    }
}
