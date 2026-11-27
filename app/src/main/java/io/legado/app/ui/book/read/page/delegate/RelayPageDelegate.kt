package io.legado.app.ui.book.read.page.delegate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import io.legado.app.BuildConfig
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.utils.canvasrecorder.CanvasRecorder
import java.util.TreeSet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

/**
 * 档案流动翻页：整页按段落横切成条带，各条带沿横向滑走/滑入，条与条错峰。
 *
 * 转场时钟照搬轻小说 ReaderTurnScope 的 relay（lib/reader_turn_motion.dart）：
 *   start = 0.02 + 0.18 × rank     条的起跑时间，按条带在页面上的位置排
 *   run   = 0.80                   单条带自己的行程占比（0.20 + 0.80 = 1）
 *   curve = easeInOutSine          起停都软，中段快
 *   位移 = 整屏宽度                  条带整体平移一整页，不是轻微晃动
 *   opacity 恒为 1                  没有淡入淡出、没有阴影、没有斜切
 *
 * 为什么看着像"抖"：同一时刻，靠上的条带已经走了一半，靠下的还没起步，
 * 页面被切成若干段各自平移，段与段错位错峰，像被抖了一下再归位。
 *
 * 与斜切版的区别：斜切是"一张纸被抽走"，这里是"文字自己流走"。
 * 所以两页都得先光栅化成 Bitmap 才能按条带 blit —— RenderNode 只能在硬件画布上画，
 * 转 Bitmap 走的是 CanvasRecorder 里 Picture 那条分支。
 *
 * 两页各按自己的段落切条：新页的条带边界落在新页的段间，旧页的落在旧页的段间，
 * 谁的文字都不会被横切。旧页画在上层，旧页某段滑走后，那一块自然露出下层的新页。
 */
class RelayPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    companion object {
        /** 轻小说整段转场 620ms */
        private const val DURATION = 620

        /** 条带起跑错峰窗口：页面最深处的条带比最上面晚 18% 的时间出发 */
        private const val STAGGER = 0.18f

        /** 单条带自身的行程占比 */
        private const val RUN = 0.80f

        /** 最早出发的条带也不是第 0 毫秒就走 */
        private const val LEAD = 0.02f

        private const val MAX_BANDS = 12
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()

    private var oldFrame: Bitmap? = null
    private var newFrame: Bitmap? = null

    /** 旧页/新页各自的条带边界，含首尾 0 与 height */
    private var oldBands: IntArray = intArrayOf(0)
    private var newBands: IntArray = intArrayOf(0)

    /** 松手瞬间的线性进度，缓动重映射的锚点 */
    private var releaseProgress = 0f

    override fun setBitmap() {
        super.setBitmap()
        releaseFrames()
        if (viewWidth <= 0 || viewHeight <= 0) return
        val target: TextPage?
        when (mDirection) {
            PageDirection.NEXT -> {
                oldFrame = rasterize(curRecorder)
                newFrame = rasterize(nextRecorder)
                target = nextPage.textPage
                oldBands = bandsOf(oldFrame, curPage.textPage, curPage.contentTop)
                newBands = bandsOf(newFrame, target, nextPage.contentTop)
            }

            PageDirection.PREV -> {
                oldFrame = rasterize(curRecorder)
                newFrame = rasterize(prevRecorder)
                target = prevPage.textPage
                oldBands = bandsOf(oldFrame, curPage.textPage, curPage.contentTop)
                newBands = bandsOf(newFrame, target, prevPage.contentTop)
            }

            else -> return
        }
    }

    /**
     * 把整页快照摊成一张 Bitmap。
     * 软件画布上 CanvasRecorder 会退回 Picture 绘制，RenderNode 那条路走不通。
     */
    private fun rasterize(recorder: CanvasRecorder): Bitmap? {
        val w = recorder.width
        val h = recorder.height
        if (w <= 0 || h <= 0) return null
        val startAt = System.nanoTime()
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                recorder.draw(Canvas(it))
            }.also {
                if (BuildConfig.DEBUG) {
                    Log.d("RelayPage", "rasterize ${w}x$h in ${(System.nanoTime() - startAt) / 1_000_000}ms")
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 单页的条带边界 = 该页每个段落的起始行。
     * lineTop 只是候选值（ContentTextView 内部还有一层坐标平移），
     * 所以再用页面像素的横向投影把候选值吸附到附近最空的一行 ——
     * 那一行一定是行与行之间或段与段之间，绝不会切在字上。
     */
    private fun bandsOf(frame: Bitmap?, page: TextPage?, contentTop: Int): IntArray {
        val height = frame?.height ?: viewHeight
        if (height <= 0) return intArrayOf(0)
        val cuts = TreeSet<Int>()
        cuts.add(0)
        cuts.add(height)

        page?.lines?.let { lines ->
            var lastParagraph = -1
            for (line in lines) {
                val no = line.paragraphNum
                if (no <= 0) continue
                if (lastParagraph != -1 && no != lastParagraph) {
                    val y = (line.lineTop + contentTop).toInt()
                    if (y > height * 0.04f && y < height * 0.96f) cuts.add(y)
                }
                lastParagraph = no
            }
        }

        var list = cuts.toIntArray()
        if (list.size - 1 > MAX_BANDS) {
            val picked = IntArray(MAX_BANDS + 1)
            for (i in 0..MAX_BANDS) picked[i] = list[i * (list.size - 1) / MAX_BANDS]
            picked[0] = 0
            picked[MAX_BANDS] = height
            list = picked
        }
        if (list.size < 2) return intArrayOf(0, height)

        val lineHeight = estimateLineHeight(page, height)
        val minGap = (lineHeight * 0.85f).toInt().coerceAtLeast(24)

        if (frame == null) return list
        val radius = (lineHeight * 0.8f).toInt().coerceAtLeast(16)
        for (i in 1 until list.size - 1) {
            list[i] = snapToBlank(frame, list[i], radius)
        }
        // 吸附后可能挤到一起：近邻只留一个，且每条带至少一行高，否则会出现"半行文字"
        val fixed = ArrayList<Int>(list.size)
        fixed.add(0)
        for (i in 1 until list.size - 1) {
            val y = list[i]
            if (y - fixed.last() < minGap) continue
            if (height - y < minGap) continue
            fixed.add(y)
        }
        fixed.add(height)
        return fixed.toIntArray()
    }

    /** 行高取页内相邻行间距的中位数，标题行与图片行不参与 */
    private fun estimateLineHeight(page: TextPage?, height: Int): Float {
        val gaps = ArrayList<Float>()
        page?.lines?.let { lines ->
            for (i in 1 until lines.size) {
                val gap = lines[i].lineTop - lines[i - 1].lineTop
                if (gap > 8f && gap < height * 0.2f) gaps.add(gap)
            }
        }
        if (gaps.isEmpty()) return height / 24f
        gaps.sort()
        return gaps[gaps.size / 2]
    }

    /**
     * 在 [y - radius, y + radius] 里找横向投影最空的一行。
     * 采样列数不用多：一行字几乎每列都有墨，行间空白则整行同色。
     */
    private fun snapToBlank(frame: Bitmap, y: Int, radius: Int): Int {
        val w = frame.width
        if (w <= 0) return y
        val from = (y - radius).coerceAtLeast(1)
        val to = (y + radius).coerceAtMost(frame.height - 2)
        if (to <= from) return y
        val step = (w / 48).coerceAtLeast(1)
        val row = IntArray(w)
        var bestY = y
        var bestInk = Int.MAX_VALUE
        for (rowY in from..to) {
            frame.getPixels(row, 0, w, 0, rowY, w, 1)
            var ink = 0
            var x = 0
            while (x < w) {
                val p = row[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // 深色像素视为字迹，纸底和浅色装饰都不计
                if (r + g + b < 480) ink++
                x += step
            }
            if (ink < bestInk) {
                bestInk = ink
                bestY = rowY
            }
        }
        return bestY
    }

    override fun onDraw(canvas: Canvas) {
        if (!isRunning) return
        val old = oldFrame ?: return
        val incoming = newFrame ?: return
        val offsetX = touchX - startX
        val isNext = mDirection == PageDirection.NEXT
        val isPrev = mDirection == PageDirection.PREV
        // 反向越界交给基类的取消逻辑
        if ((isNext && offsetX > 0) || (isPrev && offsetX < 0)) return
        val w = viewWidth.toFloat()
        if (w <= 0f) return

        val linear = (abs(offsetX) / w).coerceIn(0f, 1f)
        if (linear <= 0f) return
        val time = if (isStarted && !isCancel) settleEase(linear) else linear
        if (time <= 0f) return

        // NEXT 时旧页向左走，PREV 时向右走
        val outward = if (isNext) -1f else 1f
        // 新页先铺下层：旧页各段滑走时从这里过
        drawBands(canvas, incoming, newBands, time, outward, outgoing = false, w = w)
        // 旧页压在上层，整页各段一起流走
        drawBands(canvas, old, oldBands, time, outward, outgoing = true, w = w)
    }

    private fun drawBands(
        canvas: Canvas,
        frame: Bitmap,
        bands: IntArray,
        time: Float,
        outward: Float,
        outgoing: Boolean,
        w: Float
    ) {
        val count = bands.size - 1
        if (count <= 0) return
        val h = frame.height.toFloat()
        srcRect.set(0, 0, frame.width, frame.height)
        for (i in 0 until count) {
            val top = bands[i]
            val bottom = bands[i + 1]
            if (bottom <= top) continue
            val rank = if (count <= 1) 0f else i / (count - 1f)
            val progress = relayProgress(rank, time)
            // 同一 progress 下两页位移正好互补，接缝处一页的右缘贴着另一页的左缘
            val dx = if (outgoing) outward * w * progress else -outward * w * (1f - progress)
            dstRect.set(0f, 0f, w, h)
            dstRect.offsetTo(dx, 0f)
            canvas.save()
            canvas.clipRect(0f, top.toFloat(), w, bottom.toFloat())
            canvas.drawBitmap(frame, srcRect, dstRect, paint)
            canvas.restore()
        }
    }

    /**
     * 单条带的进度：按 rank 错峰起跑，再套 easeInOutSine。
     * rank 取条带序号而非段落序号，短段与长段得到相同的时间梯度，波浪更均匀。
     */
    private fun relayProgress(rank: Float, time: Float): Float {
        val start = LEAD + STAGGER * rank
        val raw = ((time - start) / RUN).coerceIn(0f, 1f)
        return (0.5f - 0.5f * cos(PI * raw)).toFloat()
    }

    /**
     * 松手后的进度缓动：以松手瞬间的线性进度为锚点把剩余行程铺开，
     * 免得直接换曲线把 0.4 映射成 0.75 造成跳帧。
     */
    private fun settleEase(linear: Float): Float {
        if (releaseProgress >= 0.999f) return 1f
        val span = 1f - releaseProgress
        val x = ((linear - releaseProgress) / span).coerceIn(0f, 1f)
        val eased = 1f - (1f - x).pow(2.2f)
        return releaseProgress + span * eased
    }

    override fun onAnimStop() {
        if (!isCancel) {
            readView.fillPage(mDirection)
        }
        // 等 fillPage 和随之而来的那一帧画完再回收，避免 onDraw 摸到已释放的位图。
        // 若这一 post 落地时用户已经开始下一次翻页，就不能回收（那是新一轮的帧）。
        readView.post { releaseFrames(force = false) }
    }

    override fun onAnimStart(animationSpeed: Int) {
        val distanceX: Float
        when (mDirection) {
            PageDirection.NEXT -> distanceX =
                if (isCancel) {
                    var dis = viewWidth - startX + touchX
                    if (dis > viewWidth) {
                        dis = viewWidth.toFloat()
                    }
                    viewWidth - dis
                } else {
                    -(touchX + (viewWidth - startX))
                }

            else -> distanceX =
                if (isCancel) {
                    -(touchX - startX)
                } else {
                    viewWidth - (touchX - startX)
                }
        }
        releaseProgress = if (isCancel) 0f else (abs(touchX - startX) / viewWidth).coerceIn(0f, 1f)

        // 基类按位移比例算时长，滑半屏只剩一百多毫秒；反推 speed 把落位压满 DURATION
        val distance = abs(distanceX)
        val speed = if (distance > 1f) {
            (DURATION * viewWidth / distance).toInt()
        } else {
            DURATION
        }
        startScroll(touchX.toInt(), 0, distanceX.toInt(), 0, speed.coerceIn(1, 200_000))
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseFrames(force = true)
    }

    private fun releaseFrames(force: Boolean = true) {
        if (!force && isRunning) return
        oldFrame?.recycle()
        newFrame?.recycle()
        oldFrame = null
        newFrame = null
        oldBands = intArrayOf(0)
        newBands = intArrayOf(0)
    }
}
