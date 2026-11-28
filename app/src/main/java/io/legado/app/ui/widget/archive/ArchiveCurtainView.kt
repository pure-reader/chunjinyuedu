package io.legado.app.ui.widget.archive

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos

/**
 * 档案风入场幕布（进入阅读页时盖住正文的那张纸）。
 *
 * 采用与正文翻页 RelayPageDelegate 一致的"档案流动"：纸幕按水平条带切分，
 * 各条带沿横向错峰滑走、露出下方正文，条与条起跑时间依次错开，
 * 单条用 easeInOutSine 起停。视觉上就是"文字自己流走"，而不是斜切/仿真翻页。
 *
 * 内容保持静止——流动转场里是纸幕条带滑走，正文不做斜向落位，
 * 这样入场与进入后的翻页观感完全统一。
 */
class ArchiveCurtainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context) {

    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * resources.displayMetrics.density
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        textSize = 13f * resources.displayMetrics.scaledDensity
    }
    private val codePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        textSize = 9f * resources.displayMetrics.scaledDensity
    }

    private val density = resources.displayMetrics.density

    /** 0 = 完全遮住，1 = 完全揭开 */
    var progress: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var paperColor: Int = 0xFFE7E4DA.toInt()
        set(value) {
            field = value
            paperPaint.color = value
            invalidate()
        }

    /** 幕布上的墨色：纸边、封套编号与书名都由它派生 */
    var inkColor: Int = 0xFF3A3A36.toInt()
        set(value) {
            field = value
            edgePaint.color = withAlpha(value, 0.24f)
            labelPaint.color = withAlpha(value, 0.82f)
            codePaint.color = withAlpha(value, 0.48f)
            invalidate()
        }

    /** 幕布上印的书名与档案编号，像一张档案封套；揭开时随纸幕条带一起流走 */
    var label: String = ""
    var fileCode: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** 流动转场里正文静止，只有纸幕条带滑走 */
    fun applyLanding(target: View) {
        target.translationX = 0f
        target.translationY = 0f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (progress >= 1f) return

        // 与 RelayPageDelegate 完全相同的错峰时钟
        val count = BAND_COUNT
        val bandH = h / count
        val depth = 26f * density
        for (i in 0 until count) {
            val top = bandH * i
            val bottom = bandH * (i + 1)
            val rank = if (count <= 1) 0f else i / (count - 1f)
            val p = relayProgress(rank, progress)
            // 纸带向左滑走，右侧依次露出正文
            val dx = -w * p
            val save = canvas.save()
            canvas.clipRect(0f, top, w, bottom)
            canvas.translate(dx, 0f)

            // 纸色条带
            canvas.drawRect(0f, top, w, bottom, paperPaint)
            // 纸厚：沿条带右缘向外的柔和投影 + 一条墨线，强化"纸片流走"的质感
            shadowPaint.shader = LinearGradient(
                w - depth, 0f, w, 0f,
                intArrayOf(0x001A1A14, 0x4D1A1A14), null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(w - depth, top, w, bottom, shadowPaint)
            shadowPaint.shader = null
            canvas.drawLine(w, top, w, bottom, edgePaint)

            // 文字只在自己所处的那几条带上显示，随条带一起被切开流走
            drawCurtainText(canvas, w, h)
            canvas.restoreToCount(save)
        }
    }

    private fun drawCurtainText(canvas: Canvas, w: Float, h: Float) {
        val textX = w * 0.70f
        if (fileCode.isNotEmpty()) {
            canvas.drawText(fileCode, textX, h * 0.46f, codePaint)
        }
        if (label.isNotEmpty()) {
            val text = TextUtils.ellipsize(
                label, labelPaint, w * 0.5f, TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(text, textX, h * 0.46f + 26f * density, labelPaint)
        }
    }

    /** 单条带进度：按 rank 错峰起跑，再套 easeInOutSine */
    private fun relayProgress(rank: Float, time: Float): Float {
        val start = LEAD + STAGGER * rank
        val raw = ((time - start) / RUN).coerceIn(0f, 1f)
        return (0.5f - 0.5f * cos(PI * raw)).toFloat()
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    companion object {
        /** 纸幕切分的水平条带数，与正文流动翻页的量级一致 */
        private const val BAND_COUNT = 12

        /** 条带起跑错峰窗口：最深处的条带比最上面晚 18% 才出发 */
        private const val STAGGER = 0.18f

        /** 单条带自身的行程占比 */
        private const val RUN = 0.80f

        /** 最早出发的条带也不是第 0 毫秒就走 */
        private const val LEAD = 0.02f
    }
}
