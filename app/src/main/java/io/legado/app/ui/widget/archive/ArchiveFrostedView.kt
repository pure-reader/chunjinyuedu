package io.legado.app.ui.widget.archive

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.PixelCopy
import android.view.View
import androidx.core.content.ContextCompat
import io.legado.app.R

/**
 * 廉价版"毛玻璃"：把目标 View 的当前画面分两级降采样后画到自己身上。
 *
 * 为什么不用 RenderEffect：minSdk 26，`RenderEffect.createBlurEffect` 要 31；
 * Android 11 及以下没有 backdrop blur。这里用「先缩到 1/3、再缩到 1/9」的两跳采样，
 * 两次双线性过滤叠出来的衰减比一次 1/6 更接近高斯，块状感明显更少；
 * 再叠一层偏薄的纸色 tint + 顶部高光 + 内侧亮边，得到轻小说那种悬浮玻璃条。
 *
 * 只在目标滚动停下后刷新快照（220ms 防抖），拖动过程沿用上一帧，避免每帧截屏。
 */
class ArchiveFrostedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var target: View? = null
    private var snapshot: Bitmap? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1f, dp(0.7f))
        isAntiAlias = true
    }
    private val matrix = Matrix()
    private val clip = Path()
    private val shader = BitmapShader(
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
        Shader.TileMode.CLAMP,
        Shader.TileMode.CLAMP
    )

    var cornerRadius: Float = dp(24f)
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 纸色 tint 遮盖强度。导航胶囊用偏低值让下方色块透出（文字靠高倍模糊糊掉）；
     * 图书详情页压着静止的章节目录，用较高值压住字形避免文字重叠。
     */
    var tintAlpha: Float = 0.30f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** 降采样倍率：越大越糊。主导航 6 保留可辨识色块；详情页 10 把静止字形彻底糊掉。 */
    var blurDivisor: Int = 6
        set(value) {
            field = value.coerceIn(2, 20)
            capture()
        }

    /** 是否画整条外缘收边。主导航胶囊需要；详情页内部已有两个按钮各自的描边，关掉避免双层框。 */
    var showEdge: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private val surfaceColor: Int
        get() = ContextCompat.getColor(context, R.color.background_menu)

    private val lineColor: Int
        get() = ContextCompat.getColor(context, R.color.divider)

    fun attachTo(view: View) {
        target = view
        view.viewTreeObserver.addOnScrollChangedListener { scheduleRefresh() }
        post { capture() }
    }

    private var pending: Runnable? = null

    /**
     * 滚动中不断重置计时，只有停手 220ms 才截一次，避免拖动时每帧做软件渲染。
     */
    fun scheduleRefresh() {
        pending?.let { removeCallbacks(it) }
        val task = Runnable { capture() }
        pending = task
        postDelayed(task, 220L)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun capture() {
        val view = target ?: return
        if (width <= 0 || height <= 0) return
        val activity = context as? Activity ?: return
        // 用 PixelCopy 直接拷贝窗口在本 View 区域的像素。之前用 target.draw(软件Canvas)
        // 拿不到硬件加速层的 Fragment 内容，抓到的永远是空白，毛玻璃透不出东西。
        val loc = IntArray(2)
        getLocationInWindow(loc)
        val src = Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
        // 直接拷到 1/6 尺寸：一步完成降采样，放大回去自带模糊（毛玻璃）
        val bw = (width / blurDivisor).toInt().coerceAtLeast(1)
        val bh = (height / blurDivisor).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(
                activity.window, src, bmp,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        snapshot?.recycle()
                        snapshot = bmp
                        invalidate()
                    } else {
                        bmp.recycle()
                    }
                },
                mainHandler
            )
        }.onFailure { bmp.recycle() }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // 返回首页/重建 View 时尚未布局，宽高可能为 0，
        // 此时 addRoundRect / LinearGradient / setScale(0) 都会抛异常。
        if (w <= 0f || h <= 0f) return
        clip.reset()
        clip.addRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, Path.Direction.CW)

        val save = canvas.save()
        canvas.clipPath(clip)

        val bitmap = snapshot
        if (bitmap != null && !bitmap.isRecycled) {
            matrix.reset()
            matrix.setScale(w / bitmap.width, h / bitmap.height)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        // 纸色 tint：整条统一遮盖。下方内容已由高倍模糊糊成色块，
        // 这里压住残留字形即可，不需要按标签分块，避免补丁感。
        tint.color = withAlpha(surfaceColor, tintAlpha)
        canvas.drawRect(0f, 0f, w, h, tint)

        // 玻璃厚度：顶部 45% 高度叠一层很淡的白，模拟受光面
        sheen.shader = LinearGradient(
            0f, 0f, 0f, h * 0.45f,
            intArrayOf(withAlpha(Color.WHITE, 0.13f), withAlpha(Color.WHITE, 0f)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h * 0.45f, sheen)
        sheen.shader = null

        canvas.restoreToCount(save)

        // 边缘：上缘偏亮、下缘偏暗，形成一条有厚度的玻璃收边。
        // 详情页内部两个按钮已各有描边，关掉外层收边避免"双层框"。
        if (showEdge) {
            stroke.color = withAlpha(Color.WHITE, 0.55f)
            canvas.save()
            canvas.clipRect(0f, 0f, w, h * 0.5f)
            canvas.drawRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, stroke)
            canvas.restore()
            stroke.color = withAlpha(lineColor, 0.55f)
            canvas.save()
            canvas.clipRect(0f, h * 0.5f, w, h)
            canvas.drawRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, stroke)
            canvas.restore()
        }
    }

    override fun onDetachedFromWindow() {
        pending?.let { removeCallbacks(it) }
        pending = null
        snapshot?.recycle()
        snapshot = null
        super.onDetachedFromWindow()
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

}
