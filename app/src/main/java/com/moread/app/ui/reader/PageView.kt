package com.moread.app.ui.reader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.StaticLayout
import android.view.GestureDetector
import android.view.VelocityTracker
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 自绘阅读页（Legado 路线：StaticLayout 逐页绘制）。
 * 翻页模式：0 平移（两页同向移动）/ 1 覆盖（新页盖住旧页，带边缘阴影）/ 2 纯点击。
 * 支持音量键翻页、自动阅读时任意点按退出。
 */
class PageView(context: Context) : View(context) {

    data class PageRender(
        val chapterTitle: String,
        val pageIndex: Int,
        val pageCount: Int,
        val layout: StaticLayout?,
    )

    private var prevRender: PageRender? = null
    private var curRender: PageRender? = null
    private var nextRender: PageRender? = null

    private var pageBaseColor: Int = Color.BLACK
    private var dimColor: Int = Color.GRAY

    var onTurnForward: (() -> Unit)? = null
    var onTurnBack: (() -> Unit)? = null
    var onCenterTap: (() -> Unit)? = null
    /** 自动阅读中：任意点按先退出自动模式 */
    var onAutoStop: (() -> Unit)? = null

    /** 0 平移 1 覆盖 2 纯点击 */
    var pageMode: Int = 0
    var volumeKeyTurn: Boolean = true
    var autoReading: Boolean = false

    private var dragOffset = 0f
    private var animating = false

    private val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f }
    private val bgPaint = Paint().apply { color = pageBaseColor }

    /** 覆盖模式下移动页的边缘阴影 */
    private val edgePaint = Paint().apply {
        shader = LinearGradient(
            0f, 0f, SHADOW_W, 0f,
            intArrayOf(Color.TRANSPARENT, 0x33000000, Color.TRANSPARENT),
            floatArrayOf(0f, 1f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (autoReading) {
                onAutoStop?.invoke()
                return true
            }
            when {
                e.x < width * 0.3f -> onTurnBack?.invoke()
                e.x > width * 0.7f -> onTurnForward?.invoke()
                else -> onCenterTap?.invoke()
            }
            return true
        }
    })

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null

    private val ease = android.view.animation.PathInterpolator(0.22f, 0.61f, 0.36f, 1f)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeKeyTurn && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (autoReading) {
                onAutoStop?.invoke()
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                onTurnBack?.invoke()
            } else {
                onTurnForward?.invoke()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 主题底色即时生效（首帧到达前也先铺对底色，避免进书黑屏一闪）。 */
    fun setBase(bg: Int, dim: Int) {
        pageBaseColor = bg
        bgPaint.color = bg
        dimColor = dim
        footerPaint.color = dim
        invalidate()
    }

    fun setFrame(
        prev: PageRender?,
        cur: PageRender,
        next: PageRender?,
        backgroundColor: Int,
        dimColor: Int,
        enterDir: Int,
    ) {
        prevRender = prev
        curRender = cur
        nextRender = next
        setBase(backgroundColor, dimColor)
        if (enterDir != 0 && width > 0 && pageMode != 2) {
            // 满幅滑入 + 材质缓动，动画时长随距离微调
            dragOffset = enterDir * width.toFloat()
            animateTo(0f)
        } else if (!animating) {
            dragOffset = 0f
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gesture.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = true
                animating = false
                velocity?.clear()
                velocity = velocity ?: VelocityTracker.obtain()
                velocity?.addMovement(event)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (pageMode != 2 && !autoReading) {
                    dragOffset = event.x - downX
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                velocity?.addMovement(event)
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                if (pageMode == 2 || autoReading || width == 0) {
                    dragOffset = 0f
                    invalidate()
                    return true
                }
                val threshold = width / 3f
                // 快速轻扫（惯性）直接判定翻页；慢速拖拽按 1/3 屏阈值
                val fling = vx < -1500f || vx > 1500f
                val forward = dragOffset <= -threshold || (fling && vx < 0 && dragOffset < 0)
                val back = dragOffset >= threshold || (fling && vx > 0 && dragOffset > 0)
                when {
                    forward -> onTurnForward?.invoke()
                    back -> onTurnBack?.invoke()
                    else -> animateTo(0f)
                }
                if (forward || back) {
                    // 提交翻页后由新帧接管；先清零避免残影
                    dragOffset = 0f
                }
            }
        }
        return true
    }

    private fun animateTo(target: Float) {
        animating = true
        val from = dragOffset
        ValueAnimator.ofFloat(from, target).apply {
            // 距离越远动画稍长（120~280ms），材质标准曲线，接近市面阅读器手感
            duration = (90 + 190 * (kotlin.math.abs(from - target) / kotlin.math.max(width, 1))).toLong().coerceIn(120, 280)
            interpolator = ease
            addUpdateListener {
                dragOffset = it.animatedValue as Float
                invalidate()
                if (it.animatedFraction >= 1f) animating = false
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(pageBaseColor)
        val cur = curRender ?: return

        if (dragOffset != 0f && pageMode == 1) {
            // 覆盖：旧页静止，新页压在其上滑入
            if (dragOffset < 0) {
                drawRender(canvas, cur, 0f)
                drawRender(canvas, nextRender, dragOffset + width)
                drawEdge(canvas, dragOffset + width)
            } else {
                drawRender(canvas, cur, 0f)
                drawRender(canvas, prevRender, dragOffset - width)
                drawEdge(canvas, dragOffset)
            }
        } else if (dragOffset != 0f) {
            // 平移：两页同向移动
            if (dragOffset < 0) {
                drawRender(canvas, cur, dragOffset)
                drawRender(canvas, nextRender, dragOffset + width)
            } else {
                drawRender(canvas, prevRender, dragOffset - width)
                drawRender(canvas, cur, dragOffset)
            }
        } else {
            drawRender(canvas, cur, 0f)
        }
    }

    private fun drawEdge(canvas: Canvas, edgeX: Float) {
        if (edgeX <= 0f || edgeX >= width) return
        canvas.save()
        canvas.clipRect((edgeX - SHADOW_W).coerceAtLeast(0f), 0f, edgeX, height.toFloat())
        canvas.translate(edgeX - SHADOW_W, 0f)
        canvas.drawRect(0f, 0f, SHADOW_W, height.toFloat(), edgePaint)
        canvas.restore()
    }

    private fun drawRender(canvas: Canvas, render: PageRender?, offsetX: Float) {
        val layout = render?.layout ?: return
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.translate(offsetX, 0f)
        // 覆盖模式两层页面重叠：每页必须自带不透明底色，否则新旧页文字互相透出
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        layout.draw(canvas)
        footerPaint.color = dimColor
        val footer = "${render.pageIndex + 1}/${render.pageCount}"
        val tw = footerPaint.measureText(footer)
        canvas.drawText(footer, width - tw - 8f, height - 12f, footerPaint)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        animating = false
        super.onDetachedFromWindow()
    }

    companion object {
        private const val SHADOW_W = 28f
    }
}
