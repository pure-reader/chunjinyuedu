package io.legado.app.ui.widget.archive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import io.legado.app.ui.book.read.page.delegate.RelayPageDelegate

/**
 * 档案风入场幕布。
 *
 * 这里不再"抽走一张纸"，而是和正文翻页 RelayPageDelegate 用完全相同的两条快照：
 * 上层是档案封套（旧页），下层是已经排好版的阅读页（新页）。两页各自按段落切成
 * 条带，条带按同一套 relay 时钟错峰横移——旧页向左、新页向右补位，接缝始终咬合。
 * 没有阴影、没有斜边、没有纸厚描边，所以进入阅读和正常翻页的流动观感一致。
 */
class ArchiveCurtainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context) {

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
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
    private val srcRect = Rect()
    private val dstRect = RectF()

    /** 0 = 封面完全盖住，1 = 阅读页完全露出 */
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

    /** 幕布上的墨色：封套编号与书名都由它派生 */
    var inkColor: Int = 0xFF3A3A36.toInt()
        set(value) {
            field = value
            labelPaint.color = withAlpha(value, 0.82f)
            codePaint.color = withAlpha(value, 0.48f)
            invalidate()
        }

    /** 封套上印的书名与档案编号 */
    var label: String = ""
    var fileCode: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private var oldFrame: Bitmap? = null
    private var newFrame: Bitmap? = null
    private var oldBands: IntArray = intArrayOf(0)
    private var newBands: IntArray = intArrayOf(0)

    /**
     * 把封面快照和阅读页快照交给幕布。
     * [incomingBands] 直接复用 RelayPageDelegate 从正文段落算出来的条带边界，
     * 因此入场和翻页切在同一批段间，不会横切文字。
     */
    fun prepare(old: Bitmap, new: Bitmap, incomingBands: IntArray) {
        releaseFrames()
        oldFrame = old
        newFrame = new
        oldBands = uniformBands(old.height, RelayPageDelegate.MAX_BANDS)
        newBands = if (incomingBands.size >= 2 && incomingBands.last() == new.height) {
            incomingBands
        } else {
            uniformBands(new.height, RelayPageDelegate.MAX_BANDS)
        }
        invalidate()
    }

    /** 把静态封套烘成一张旧页位图，文字随条带一起流走 */
    fun snapshotCover(): Bitmap? {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                drawCover(Canvas(it), w.toFloat(), h.toFloat())
            }
        } catch (e: Throwable) {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || progress >= 1f) return
        val incoming = newFrame ?: return
        val outgoing = oldFrame ?: return

        // 与 RelayPageDelegate.onDraw 同一条时间曲线：先套 settle，再让每条带跑 relay
        val time = RelayPageDelegate.settleFromStart(progress)
        drawBandFrame(canvas, incoming, newBands, time, w, h, outgoing = false)
        drawBandFrame(canvas, outgoing, oldBands, time, w, h, outgoing = true)
    }

    private fun drawBandFrame(
        canvas: Canvas,
        frame: Bitmap,
        bands: IntArray,
        time: Float,
        w: Float,
        h: Float,
        outgoing: Boolean
    ) {
        val count = bands.size - 1
        if (count <= 0) return
        srcRect.set(0, 0, frame.width, frame.height)
        for (i in 0 until count) {
            val top = bands[i]
            val bottom = bands[i + 1]
            if (bottom <= top) continue
            val rank = if (count <= 1) 0f else i / (count - 1f)
            val p = RelayPageDelegate.relayProgress(rank, time)
            // 旧页向左滑走，新页从右侧补进来；同一 p 下两页位移互补
            val dx = if (outgoing) -w * p else w * (1f - p)
            dstRect.set(dx, 0f, dx + w, h)
            canvas.save()
            canvas.clipRect(0f, top.toFloat(), w, bottom.toFloat())
            canvas.drawBitmap(frame, srcRect, dstRect, bitmapPaint)
            canvas.restore()
        }
    }

    private fun drawCover(canvas: Canvas, w: Float, h: Float) {
        canvas.drawRect(0f, 0f, w, h, paperPaint)
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

    private fun uniformBands(height: Int, count: Int): IntArray {
        if (height <= 0) return intArrayOf(0)
        val n = count.coerceAtLeast(1)
        return IntArray(n + 1) { i -> (i * height.toFloat() / n).toInt() }
            .also { it[n] = height }
    }

    fun releaseFrames() {
        oldFrame?.recycle()
        newFrame?.recycle()
        oldFrame = null
        newFrame = null
        oldBands = intArrayOf(0)
        newBands = intArrayOf(0)
    }

    override fun onDetachedFromWindow() {
        releaseFrames()
        super.onDetachedFromWindow()
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }
}
