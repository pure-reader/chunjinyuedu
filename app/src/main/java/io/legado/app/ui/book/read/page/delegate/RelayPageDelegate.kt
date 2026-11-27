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
import io.legado.app.utils.canvasrecorder.CanvasRecorder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

/**
 * 档案流动翻页：整页平滑横移，不再按段落切成条带。
 *
 * 之前的版本把每页按段间空白切成最多 12 条，逐条错峰起跑，看起来像页面
 * 被抖散成几段。现在两页各光栅化为一整个位图，整页同步位移：
 *   - 旧页在上层向左滑出（上一页则向右）
 *   - 新页在下层从相反方向滑入，两页位移互补，接缝始终贴合
 *   - 位移套 easeInOutSine，松手后按松手瞬间的进度锚点铺开剩余行程
 *
 * 上位图用 CanvasRecorder 的 Picture 分支光栅化，软件画布也能工作；
 * 不再需要找空白行、吸附条带边界，少掉逐条 clip 与多次 drawBitmap。
 */
class RelayPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    companion object {
        /** 整页转场时长，与原来的错峰条带总时长一致 */
        private const val DURATION = 620
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()

    private var oldFrame: Bitmap? = null
    private var newFrame: Bitmap? = null

    /** 松手瞬间的线性进度，缓动重映射的锚点 */
    private var releaseProgress = 0f

    override fun setBitmap() {
        super.setBitmap()
        releaseFrames()
        if (viewWidth <= 0 || viewHeight <= 0) return
        when (mDirection) {
            PageDirection.NEXT -> {
                oldFrame = rasterize(curRecorder)
                newFrame = rasterize(nextRecorder)
            }

            PageDirection.PREV -> {
                oldFrame = rasterize(curRecorder)
                newFrame = rasterize(prevRecorder)
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

        val eased = (0.5f - 0.5f * cos(PI * time)).toFloat()
        // NEXT 时旧页向左走，PREV 时向右走
        val outward = if (isNext) -1f else 1f
        val shift = w * eased
        drawPage(canvas, incoming, -outward * (w - shift))
        drawPage(canvas, old, outward * shift)
    }

    private fun drawPage(canvas: Canvas, frame: Bitmap, dx: Float) {
        val w = viewWidth.toFloat()
        srcRect.set(0, 0, frame.width, frame.height)
        dstRect.set(0f, 0f, w, frame.height.toFloat())
        dstRect.offsetTo(dx, 0f)
        canvas.drawBitmap(frame, srcRect, dstRect, paint)
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
    }
}
