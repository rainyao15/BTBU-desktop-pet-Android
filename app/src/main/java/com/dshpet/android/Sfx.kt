package com.dshpet.android

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 点击音效：参考 dsh-whale-widget 的「拍一下」反馈——按下播 `Ya1`，抬手播 `Ya2`。
 * 两个片段都是短 mp3（3–6 KB），SoundPool 预载，播放零延迟、不阻塞主线程。
 *
 * 抬手音是**排期**播的，这点照抄上游 `pressUp()`：
 * 快速点一下时按压音还没放完，上游会把松开音排到「按压音结束前 40ms」
 * （whale-widget.js：`playReleaseAt(Math.max(0, remainSec - RELEASE_LEAD_MS / 1000))`，
 * 而 `var RELEASE_LEAD_MS = 40`），这样 Ya1 能完整听完，Ya2 紧接着接上，两个音不会互相掐断。
 * 长按（Ya1 已经放完）则抬手立刻响 Ya2，与上游 `playRelease()` 分支一致。
 */
object Sfx {

    /** 上游同名常量：松开音提前 40ms 接上 */
    private const val RELEASE_LEAD_MS = 40L

    /** 排期上限：超过这个就不排了，直接播（避免某些机型时长读错导致一直不响） */
    private const val MAX_SCHEDULE_MS = 3_000L

    /** 时长三级兜底都失败时的经验值（Ya1 实际约 0.4s） */
    private const val FALLBACK_PRESS_MS = 430L

    private var pool: SoundPool? = null
    private var pressId = 0
    private var releaseId = 0
    private var ready = false

    /** 按压音时长（ms），抬手时用来算「还差多久放完」 */
    private var pressDurMs = 0L

    /** 本次按下的时刻（uptimeMillis），0 = 这次按键没有播过按压音 */
    private var pressAtMs = 0L

    /** 已排期但还没触发的松开音 */
    private var pendingRelease: Runnable? = null

    /** init 时存下的 Application Context，仅用于 Diag 打点 */
    private var appCtx: Context? = null

    private val ui = Handler(Looper.getMainLooper())

    /** 总开关：在设置页手动控制，关掉后 press/release 直接静默 */
    @Volatile
    var enabled: Boolean = true

    fun init(ctx: Context) {
        if (pool != null) return
        appCtx = ctx
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
            // 音源来自 dsh-whale-widget（MIT）的「音效1」预设：
            // res/raw/tap_press.mp3 = assets/Ya1.mp3（按下），res/raw/tap_release.mp3 = assets/Ya2.mp3（抬手）。
            // 仍然走 getIdentifier 而不是 R.raw.xxx：素材缺失时静默降级，不会让编译失败。
            val pressRes = rawId(ctx, "tap_press")
            val releaseRes = rawId(ctx, "tap_release")
            pressId = p.load(ctx, pressRes, 1)
            releaseId = p.load(ctx, releaseRes, 1)
            // 注意：上面 load() 返回的是 SoundPool 的 soundId，读时长必须用**资源 id**
            pressDurMs = rawDurationMs(ctx, pressRes)
            pool = p
            Diag.record(ctx, "音效就绪: press=$pressId(${pressDurMs}ms) release=$releaseId")
        } catch (t: Throwable) {
            Diag.record(ctx, "音效初始化失败: ${t.message}")
        }
    }

    /** 按下：立刻播 Ya1，并撤掉上一次还没触发的松开音 */
    fun press() {
        pendingRelease?.let { ui.removeCallbacks(it) }
        pendingRelease = null
        pressAtMs = SystemClock.uptimeMillis()
        play(pressId)
    }

    /**
     * 抬手：Ya1 还没放完 → 排到它放完前 40ms 再响（这样快速点按也听得完整）；
     * 已经放完（长按）→ 立刻响。
     */
    fun release() {
        if (!enabled) return
        if (pressAtMs == 0L || pressDurMs <= 0L) {
            play(releaseId)
            return
        }
        val elapsed = SystemClock.uptimeMillis() - pressAtMs
        val remain = pressDurMs - elapsed - RELEASE_LEAD_MS
        if (remain >= 1L && remain <= MAX_SCHEDULE_MS) {
            val r = Runnable {
                pendingRelease = null
                play(releaseId)
            }
            pendingRelease = r
            ui.postDelayed(r, remain)
            if (appCtx != null) Diag.record(appCtx!!, "抬手音排期 ${remain}ms")
        } else {
            play(releaseId)
        }
    }

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

    /**
     * 读 raw 里音频的时长（毫秒）。三级兜底，最后一级是常量：
     * MediaMetadataRetriever 对某些短小的 mp3 会解析不出 duration（实测返回 0），
     * 所以先用 MediaPlayer.create（内部已 prepare，时长最可靠），再退回 retriever，
     * 都失败就用经验常量 —— 排期只需要「大概多久放完」，差几十毫秒听不出来。
     */
    private fun rawDurationMs(ctx: Context, resId: Int): Long {
        if (resId == 0) return 0L

        // ① MediaPlayer.create：对 raw 资源最稳
        val fromPlayer = try {
            val mp = android.media.MediaPlayer.create(ctx, resId)
            val d = if (mp == null) 0L else mp.duration.toLong()
            try { mp?.release() } catch (_: Throwable) { }
            d
        } catch (t: Throwable) {
            Diag.record(ctx, "音效时长(MediaPlayer)失败: ${t.message}")
            0L
        }
        if (fromPlayer > 0L) return fromPlayer

        // ② MediaMetadataRetriever
        var fd: AssetFileDescriptor? = null
        var mmr: MediaMetadataRetriever? = null
        val fromRetriever = try {
            fd = ctx.resources.openRawResourceFd(resId)
            mmr = MediaMetadataRetriever()
            mmr.setDataSource(fd.fileDescriptor, fd.startOffset, fd.declaredLength)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            Diag.record(ctx, "音效时长(Retriever)失败: ${t.message}")
            0L
        } finally {
            try { mmr?.release() } catch (_: Throwable) { }
            try { fd?.close() } catch (_: Throwable) { }
        }
        if (fromRetriever > 0L) return fromRetriever

        // ③ 经验值：够用就行
        Diag.record(ctx, "音效时长读不到，退回 ${FALLBACK_PRESS_MS}ms")
        return FALLBACK_PRESS_MS
    }

    /** 资源不存在时返回 0（SoundPool 会忽略），不抛异常 */
    private fun rawId(ctx: Context, name: String): Int = try {
        ctx.resources.getIdentifier(name, "raw", ctx.packageName)
    } catch (_: Throwable) {
        0
    }
}
