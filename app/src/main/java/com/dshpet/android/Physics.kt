package com.dshpet.android

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 拖拽抛掷物理：dsh-pet `src/shared/physics.ts` 的逐行移植。
 *
 * 坐标语义与桌面版一致：全部是「宠物包围盒左上角」的像素坐标；速度单位 px/s。
 * 常量取自原文件，未改任何数值，保证手感与电脑版一致。
 */

/** 抛掷物理参数（对应 config 的 physics 段） */
data class PhysicsParams(
    val gravity: Double = 1400.0,
    val restitution: Double = 0.78,
    val groundFriction: Double = 2.5,
    val ceilingBounce: Boolean = true,
    val throwPower: Double = 1.0,
)

/** 包围盒左上角的允许活动范围 */
data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

/** 抛体状态 */
data class Body(val x: Double, val y: Double, val vx: Double, val vy: Double)

data class StepResult(val body: Body, val bounced: Boolean, val atRest: Boolean)

/** 一次拖拽轨迹采样（t = 毫秒时间戳，x/y = 指针绝对坐标） */
data class DragSample(val t: Double, val x: Double, val y: Double)

object Physics {
    // ---- 拖拽弹簧 ----
    const val SPRING_K = 200.0
    const val SPRING_C = 30.0

    // ---- 初速估算 ----
    const val TRAIL_KEEP_MS = 200.0
    const val RELEASE_WINDOW_MS = 150.0
    const val RELEASE_STALE_MS = 150.0
    const val MIN_SPAN_MS = 20.0
    const val SEG_MIN_DT_MS = 8.0
    const val DEAD_ZONE_SPEED = 500.0
    const val MAX_THROW_SPEED = 3600.0
    const val PEAK_WEIGHT = 0.5
    const val ACCEL_REF = 8000.0
    const val ACCEL_GAIN_MAX = 0.6

    // ---- 抛掷积分 ----
    const val REST_VY = 40.0
    const val REST_VX = 15.0
    const val MAX_STEP_DT = 0.05

    // ---- Q 弹挤压 ----
    const val SQ_SQUASH = 0.55
    const val SQ_DURATION_MS = 220.0
    const val SQ_SOFT_SPEED = 300.0
    const val SQ_HARD_SPEED = 1500.0
    const val SQ_MAX_SQUASH = 0.55

    /** 画布是 16:9：宠物盒高 = 宽 × 9/16 */
    fun heightFor(size: Double): Double = size * 9.0 / 16.0

    /** 抛掷边界：身体（盒内缩 sideAllow）贴屏幕边缘才反弹 */
    fun bounds(screenW: Double, screenH: Double, size: Double, sideAllow: Double): Bounds {
        val h = heightFor(size)
        return Bounds(
            minX = -sideAllow,
            minY = 0.0,
            maxX = screenW - size + sideAllow,
            maxY = screenH - h,
        )
    }

    /** 过阻尼弹簧单轴速度步进：调用方随后 x += v*dt */
    fun springStep(v: Double, x: Double, target: Double, dt: Double, power: Double = 1.0): Double =
        v + ((target - x) * SPRING_K - v * SPRING_C) * power * dt

    /** 剔除超过保留窗口的旧采样 */
    fun trimTrail(trail: List<DragSample>, now: Double): List<DragSample> {
        val cutoff = now - TRAIL_KEEP_MS
        val i = trail.indexOfFirst { it.t >= cutoff }
        return if (i <= 0) trail else trail.subList(i, trail.size).toList()
    }

    private fun softClampSpeed(speed: Double): Double =
        if (speed <= 0) 0.0 else MAX_THROW_SPEED * (1 - exp(-speed / MAX_THROW_SPEED))

    /**
     * 由拖拽轨迹估算松手初速 (vx, vy)，px/s。
     * 返回 null = 温柔放下（轨迹空 / 停留过久 / 窗口太短 / 低于死区），调用方不抛。
     */
    fun estimateReleaseVelocity(
        trail: List<DragSample>,
        now: Double,
        p: PhysicsParams,
    ): Pair<Double, Double>? {
        if (trail.isEmpty()) return null
        if (now - trail.last().t > RELEASE_STALE_MS) return null
        val win = trail.filter { now - it.t <= RELEASE_WINDOW_MS }
        if (win.size < 2) return null
        val t0 = win.first().t
        val x0 = win.first().x
        val y0 = win.first().y
        val t1 = win.last().t
        val x1 = win.last().x
        val y1 = win.last().y
        val spanMs = t1 - t0
        if (spanMs < MIN_SPAN_MS) return null
        val baseVx = (x1 - x0) / spanMs * 1000
        val baseVy = (y1 - y0) / spanMs * 1000
        val baseSpeed = hypot(baseVx, baseVy)
        if (baseSpeed < 1e-6) return null

        // 分段速度（过密采样向前合并，dt 下限 SEG_MIN_DT_MS）
        val segSpeed = ArrayList<Double>(win.size)
        val segTEnd = ArrayList<Double>(win.size)
        var px = x0
        var py = y0
        var pt = t0
        for (i in 1 until win.size) {
            val s = win[i]
            val dt = s.t - pt
            if (dt >= SEG_MIN_DT_MS) {
                segSpeed.add(hypot(s.x - px, s.y - py) / dt * 1000)
                segTEnd.add(s.t)
                px = s.x
                py = s.y
                pt = s.t
            }
        }
        val peakSpeed = if (segSpeed.isNotEmpty()) segSpeed.max() else baseSpeed
        var accel = 0.0
        if (segSpeed.size >= 2) {
            val lastI = segSpeed.size - 1
            accel = (segSpeed[lastI] - segSpeed[0]) /
                max((segTEnd[lastI] - segTEnd[0]) / 1000, MIN_SPAN_MS / 1000)
        }
        val speedBeforeClamp = ((1 - PEAK_WEIGHT) * baseSpeed + PEAK_WEIGHT * peakSpeed) *
            (1 + min(max(accel, 0.0) / ACCEL_REF, 1.0) * ACCEL_GAIN_MAX)
        val speed = softClampSpeed(speedBeforeClamp) * p.throwPower
        if (speed < DEAD_ZONE_SPEED) return null
        return Pair(baseVx / baseSpeed * speed, baseVy / baseSpeed * speed)
    }

    /** 抛体单步积分 + 边界反弹 */
    fun throwStep(s: Body, dtRaw: Double, b: Bounds, p: PhysicsParams): StepResult {
        val dt = min(max(dtRaw, 0.0), MAX_STEP_DT)
        var x = s.x
        var y = s.y
        var vx = s.vx
        var vy = s.vy
        vy += p.gravity * dt
        x += vx * dt
        y += vy * dt
        var bounced = false
        if (x < b.minX) {
            x = b.minX
            vx = abs(vx) * p.restitution
            bounced = true
        } else if (x > b.maxX) {
            x = b.maxX
            vx = -abs(vx) * p.restitution
            bounced = true
        }
        if (y < b.minY) {
            // ceilingBounce=false：顶部无边，宠物飞出屏幕顶部靠重力落回
            if (p.ceilingBounce) {
                y = b.minY
                vy = abs(vy) * p.restitution
                bounced = true
            }
        } else if (y >= b.maxY) {
            y = b.maxY
            vx *= max(0.0, 1 - p.groundFriction * dt)
            if (abs(vy) < REST_VY) vy = 0.0 else vy = -abs(vy) * p.restitution
            bounced = true
        }
        val speed = hypot(vx, vy)
        val atRest = (y >= b.maxY - 1 && abs(vy) < 1 && abs(vx) < REST_VX) ||
            (bounced && speed < REST_VY && abs(vy) < 1)
        return StepResult(Body(x, y, vx, vy), bounced, atRest)
    }

    /** Q 弹挤压曲线：u∈[0,1] → scaleY。下压段 ease-in，回弹段 easeOutBack 过冲 */
    fun squashScale(u: Double, squash: Double = SQ_SQUASH): Double {
        if (u < 0.45) {
            val p = u / 0.45
            return 1 - (1 - squash) * p * p
        }
        val p = (u - 0.45) / 0.55
        val c1 = 1.70158
        val c3 = c1 + 1
        val f = 1 + c3 * (p - 1).pow(3) + c1 * (p - 1).pow(2)
        return min(1.12, squash + (1 - squash) * max(f, 0.0))
    }

    /** 落地冲击速度 → 下压幅度（越大砸得越狠） */
    fun landingSquash(impactSpeed: Double): Double {
        val t = min(max((abs(impactSpeed) - SQ_SOFT_SPEED) / (SQ_HARD_SPEED - SQ_SOFT_SPEED), 0.0), 1.0)
        return min(0.8, 1 - t * (1 - SQ_MAX_SQUASH))
    }
}
