package com.sudisoft.lightoff

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 棋盘自绘视图：格子、按压涟漪、提示圈、胜利粒子。
 * 布局为正方形（宽度自适应，高度 = 宽度）。
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var n: Int = 8
        set(value) {
            field = value.coerceAtLeast(2)
            requestLayout()
            invalidate()
        }

    var lights: BooleanArray? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 格子点击回调（参数为格子下标 0..n*n-1） */
    var onCellClickListener: ((Int) -> Unit)? = null

    private var hintIndex: Int? = null
    private val hintHandler = Handler(Looper.getMainLooper())

    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val offStroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val cellRect = RectF()
    private val ripples = ArrayList<Ripple>()
    private val particles = ArrayList<Particle>()
    private var lastFrameNs = 0L

    private class Ripple(val cx: Float, val cy: Float, val radius: Float) {
        var t = 0f
    }

    private class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val color: Int,
        val size: Float
    ) {
        var life = 1f
    }

    private class Geom(val pad: Float, val gap: Float, val cell: Float)

    init {
        offPaint.color = Color.parseColor("#1B2540")
        offStroke.color = Color.parseColor("#2A3A5F")
        offStroke.style = Paint.Style.STROKE
        offStroke.strokeWidth = 1.5f
        onPaint.color = Color.parseColor("#FFC24D")
        glowPaint.color = Color.parseColor("#FFB858")
        corePaint.color = Color.parseColor("#FFF3C4")
        hintPaint.color = Color.parseColor("#2FE3C0")
        hintPaint.style = Paint.Style.STROKE
        hintPaint.strokeWidth = 3.5f
        hintPaint.pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        fxPaint.color = Color.WHITE
        framePaint.color = Color.parseColor("#1FFFFFFF")
        framePaint.style = Paint.Style.STROKE
        framePaint.strokeWidth = 2f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = (resources.displayMetrics.heightPixels * 0.52f).toInt()
        val size = minOf(w, maxH).coerceAtLeast(1)
        setMeasuredDimension(size, size)
    }

    private fun geom(): Geom {
        val pad = width * 0.014f
        val gap = (width * 0.008f).coerceAtLeast(1.5f)
        val cell = (width - pad * 2 - gap * (n - 1)) / n
        return Geom(pad, gap, cell)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lights = lights ?: return
        if (width <= 0 || n <= 0) return
        val g = geom()
        val radius = g.cell * 0.22f

        // 棋盘底板
        cellRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(cellRect, width * 0.02f, width * 0.02f, framePaint)

        for (i in 0 until n * n) {
            val r = i / n
            val c = i % n
            val x = g.pad + c * (g.cell + g.gap)
            val y = g.pad + r * (g.cell + g.gap)
            cellRect.set(x, y, x + g.cell, y + g.cell)
            if (lights[i]) {
                // 光晕 + 灯体 + 高光
                glowPaint.alpha = 55
                canvas.drawCircle(x + g.cell / 2f, y + g.cell / 2f, g.cell * 0.74f, glowPaint)
                canvas.drawRoundRect(cellRect, radius, radius, onPaint)
                corePaint.alpha = 190
                canvas.drawCircle(x + g.cell * 0.36f, y + g.cell * 0.3f, g.cell * 0.15f, corePaint)
            } else {
                canvas.drawRoundRect(cellRect, radius, radius, offPaint)
                canvas.drawRoundRect(cellRect, radius, radius, offStroke)
            }
        }

        // 提示圈
        hintIndex?.let { h ->
            if (h in lights.indices) {
                val r = h / n
                val c = h % n
                val x = g.pad + c * (g.cell + g.gap)
                val y = g.pad + r * (g.cell + g.gap)
                cellRect.set(x + g.cell * 0.1f, y + g.cell * 0.1f, x + g.cell * 0.9f, y + g.cell * 0.9f)
                canvas.drawRoundRect(cellRect, radius, radius, hintPaint)
            }
        }

        // 动画帧推进
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.016f
        else ((now - lastFrameNs) / 1e9f).coerceIn(0.0005f, 0.05f)
        lastFrameNs = now
        val f = dt * 60f // 归一化到 60fps

        val ri = ripples.iterator()
        while (ri.hasNext()) {
            val rp = ri.next()
            rp.t += dt * 2.4f
            if (rp.t >= 1f) {
                ri.remove()
                continue
            }
            val scale = 0.2f + rp.t * 1.7f
            fxPaint.color = Color.WHITE
            fxPaint.alpha = ((1f - rp.t) * 200).toInt().coerceIn(0, 255)
            canvas.drawCircle(rp.cx, rp.cy, rp.radius * scale, fxPaint)
        }

        val pi = particles.iterator()
        while (pi.hasNext()) {
            val p = pi.next()
            p.x += p.vx * f
            p.y += p.vy * f
            p.vy += 0.16f * f
            p.vx *= 0.995f
            p.life -= 0.011f * f
            if (p.life <= 0f) {
                pi.remove()
                continue
            }
            fxPaint.color = p.color
            fxPaint.alpha = (p.life * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.x, p.y, p.size, fxPaint)
        }

        if (ripples.isNotEmpty() || particles.isNotEmpty()) {
            postInvalidateOnAnimation()
        } else {
            lastFrameNs = 0L
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val lights = lights ?: return true
        if (n <= 0 || width <= 0) return true
        val g = geom()
        val c = ((event.x - g.pad) / (g.cell + g.gap)).toInt()
        val r = ((event.y - g.pad) / (g.cell + g.gap)).toInt()
        if (r in 0 until n && c in 0 until n) {
            val x = g.pad + c * (g.cell + g.gap)
            val y = g.pad + r * (g.cell + g.gap)
            // 必须落在格子内（不能是间隙）
            if (event.x >= x && event.x <= x + g.cell && event.y >= y && event.y <= y + g.cell) {
                ripples.add(Ripple(x + g.cell / 2f, y + g.cell / 2f, g.cell * 0.5f))
                lastFrameNs = 0L
                invalidate()
                onCellClickListener?.invoke(r * n + c)
            }
        }
        return true
    }

    /** 显示提示圈，3 秒后自动消失 */
    fun showHint(index: Int?) {
        hintHandler.removeCallbacksAndMessages(null)
        hintIndex = index
        invalidate()
        if (index != null) {
            hintHandler.postDelayed({
                hintIndex = null
                invalidate()
            }, 3000L)
        }
    }

    /** 胜利粒子礼花 */
    fun startWinParticles() {
        particles.clear()
        val colors = intArrayOf(
            Color.parseColor("#FFD166"),
            Color.parseColor("#2FE3C0"),
            Color.WHITE,
            Color.parseColor("#FF9F43"),
            Color.parseColor("#7CC4FF")
        )
        repeat(90) {
            val a = Random.nextDouble() * 2 * PI
            val sp = (2 + Random.nextDouble() * 6).toFloat()
            particles.add(
                Particle(
                    x = width / 2f + (Random.nextDouble() - 0.5).toFloat() * width * 0.5f,
                    y = height / 2f + (Random.nextDouble() - 0.5).toFloat() * height * 0.4f,
                    vx = (cos(a) * sp).toFloat(),
                    vy = (sin(a) * sp - 2).toFloat(),
                    color = colors[Random.nextInt(colors.size)],
                    size = (3 + Random.nextDouble() * 5).toFloat()
                )
            )
        }
        lastFrameNs = 0L
        invalidate()
    }
}
