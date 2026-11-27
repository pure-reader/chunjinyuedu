package io.legado.app.ui.widget.archive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.R
import io.legado.app.data.entities.Book
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 封面位图缓存上限：一张 104x118dp 封面约 0.3~0.4MB，24 张约 10MB 封顶。 */
private const val COVER_CACHE_MAX = 24

/** 竖排书名截断用的省略号，避免每次拼字符串 */
private const val ELLIPSIS = "…"

/**
 * 档案风"书脊书架"。
 *
 * 直接对应 Wenku8 的 ArchiveFileCarousel（archive_shelf_geometry.dart）：
 *  - 一排竖立的书脊，选中那本向前抽出并向右展开封面
 *  - 书脊宽度 spineFor(w) = w*0.043 clamp 14~18dp，书距 pitch = spine + 11dp
 *  - 封面宽度 coverFor(w) = w*0.32 clamp 100~128dp
 *  - 抽出距离 pullDistance(cover) = cover*0.84（这里取封面展开宽度）
 *
 * 与 Flutter 版一致：滚动位置是唯一时钟，选中项由 offset 四舍五入得到，
 * 拖动时所有书脊按 offset 连续平移，松手 fling + 吸附到最近一本。
 */
class ArchiveSpineShelfView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onSelect: ((Int) -> Unit)? = null
    var onOpen: ((Book) -> Unit)? = null
    var onSettle: ((Int) -> Unit)? = null

    private val books = arrayListOf<Book>()

    /**
     * 封面位图缓存，按访问顺序淘汰。
     * 之前是无限增长的 HashMap，只在书单变化时清空，翻几百本书就会一直涨。
     */
    private val coverCache = object : LinkedHashMap<String, Bitmap?>(COVER_CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>?): Boolean =
            size > COVER_CACHE_MAX
    }

    /** 每本书的静态文案，submit 时算一次，绘制时不再做字符串拼接 */
    private class SpineLabel(
        val ordinal: String,
        val titleChars: CharArray,
        val authorChars: CharArray,
        val blankChars: CharArray,
        val code: String,
    )

    private val labels = ArrayList<SpineLabel>()

    // 绘制期复用的缓冲，避免每帧分配
    private var drawOrder = IntArray(0)
    private var orderCount = 0
    private val tmpRect = RectF()
    private val srcRect = Rect()
    private var shadowShader: LinearGradient? = null
    private var coverShadowShader: LinearGradient? = null
    private var fadeLeftShader: LinearGradient? = null
    private var fadeRightShader: LinearGradient? = null
    private var fadeBuiltWidth = -1
    private val innerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val spineW = dp(16f)
    private val pitch = spineW + dp(11f)
    private val coverW = dp(104f)

    private val spineFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spineStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, dp(0.7f))
    }
    private val pageEdge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private val serifTypeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    private val spineText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = serifTypeface
        textAlign = Paint.Align.CENTER
    }
    private val codeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 绘制热路径上不再查资源：颜色在 attach / 数据变化时刷一次
    private var ink = 0
    private var paper = 0
    private var spinePaper = 0
    private var spinePaperSelected = 0
    private var coverPaper = 0
    private var pageEdgeColor = 0

    /** 书脊纸面走颜色资源，夜间自动换成深色卡纸（见 values-night/colors.xml）。 */
    private fun refreshColors() {
        ink = ArchiveTokens.ink(context)
        paper = ArchiveTokens.paper(context)
        spinePaper = ContextCompat.getColor(context, R.color.archive_spine_paper)
        spinePaperSelected = ContextCompat.getColor(context, R.color.archive_spine_paper_selected)
        coverPaper = ContextCompat.getColor(context, R.color.archive_cover_paper)
        pageEdgeColor = ContextCompat.getColor(context, R.color.archive_page_edge)
        // 颜色变了，按色值建的渐变也要作废
        fadeLeftShader = null
        fadeRightShader = null
        fadeBuiltWidth = -1
    }

    private var offset = 0f
    private var selected = 0
    private var baseY = 0f
    private var bookHeight = 0f
    private var spineTop = 0f
    // coverHeight() 只依赖固定的 coverW，不依赖布局；必须在声明处初始化，
    // 否则 submit() 先于 onSizeChanged 到达时，loadCover 会把 0 传给 Glide 的
    // CustomTarget，触发 Bitmap.createBitmap(w, 0) 的 IllegalArgumentException。
    private var coverH = coverHeight()

    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downOffset = 0f
    private var dragging = false
    private var verticalGesture = false
    private var settling = false
    /** 吸附落定后是否回调 onSettle（列表滚动驱动的跟随不需要再回写列表） */
    private var notifySettle = true

    private val coverRect = RectF()
    private val spineRect = RectF()
    private val clipPath = Path()

    // 3D 透视：书列按离中心的距离绕 Y 轴旋转，选中封面再绕书脊铰链旋出
    private val camera3d = Camera()
    private val matrix3d = Matrix()

    private fun coverReveal(): Float {
        val d = ((abs(offset - selected) - 0.12f) / 0.88f).coerceIn(0f, 1f)
        return 1f - d * d * (3f - 2f * d)
    }

    private fun withYRotation(
        canvas: Canvas,
        pivotX: Float,
        pivotY: Float,
        degrees: Float,
        translateZ: Float = 0f,
        draw: () -> Unit,
    ) {
        if (abs(degrees) < 0.1f && abs(translateZ) < 0.1f) {
            draw()
            return
        }
        camera3d.save()
        camera3d.rotateY(degrees)
        if (translateZ != 0f) camera3d.translate(0f, 0f, translateZ)
        camera3d.getMatrix(matrix3d)
        camera3d.restore()
        matrix3d.preTranslate(-pivotX, -pivotY)
        matrix3d.postTranslate(pivotX, pivotY)
        val save = canvas.save()
        canvas.concat(matrix3d)
        draw()
        canvas.restoreToCount(save)
    }

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
        refreshColors()
    }

    // ---------------------------------------------------------------- 数据

    fun submit(list: List<Book>) {
        val same = books.size == list.size &&
                books.indices.all { books[it].bookUrl == list[it].bookUrl }
        books.clear()
        books.addAll(list)
        rebuildLabels()
        if (selected >= books.size) selected = (books.size - 1).coerceAtLeast(0)
        offset = selected.toFloat()
        if (!same) coverCache.clear()
        prefetchCover(selected)
        invalidate()
    }

    /** 书单变化时把序号/书名/作者/编号一次算好，绘制时不再拼字符串 */
    private fun rebuildLabels() {
        labels.clear()
        books.forEachIndexed { index, book ->
            labels.add(
                SpineLabel(
                    ordinal = (index + 1).toString().padStart(2, '0'),
                    titleChars = "\u300a${book.name}\u300b".toCharArray(),
                    authorChars = (book.author ?: "").take(6).toCharArray(),
                    blankChars = book.name.take(9).toCharArray(),
                    code = ArchiveTokens.fileCode(book.bookUrl.hashCode().absoluteValue % 100000),
                )
            )
        }
        if (drawOrder.size < books.size) drawOrder = IntArray(books.size)
    }

    /** 由外部（下方列表滚动）同步选中项 */
    fun setSelected(index: Int, animate: Boolean = true, notifySettle: Boolean = true) {
        if (books.isEmpty()) return
        val target = index.coerceIn(0, books.size - 1)
        this.notifySettle = notifySettle
        if (target == selected && abs(offset - target) < 0.01f) return
        selected = target
        if (animate) {
            startScrollTo(target)
        } else {
            offset = target.toFloat()
            invalidate()
        }
    }

    val selectedIndex: Int get() = selected

    val bookCount: Int get() = books.size

    fun bookAt(index: Int): Book? = books.getOrNull(index)

    // ---------------------------------------------------------------- 几何

    private fun coverHeight(): Float = (coverW - dp(20f)) * 166f / 118f + dp(4f)

    private fun spineWidth(index: Int): Float =
        if (index == selected) spineW * 1.28f else spineW

    /**
     * 第 index 本书脊左缘的 x。
     * 选中书右侧的书要空出封面宽度，这里用 clamp 做连续过渡，避免拖动时跳变。
     */
    private fun spineLeft(index: Int, cx: Float): Float {
        val base = cx + (index - offset) * pitch
        val push = coverW * ((index - offset).coerceIn(0f, 1f))
        return base - spineWidth(index) / 2f + push
    }

    // ---------------------------------------------------------------- 绘制

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        coverH = coverHeight()
        bookHeight = min(h - dp(24f), coverH + dp(34f))
        baseY = h - dp(16f)
        spineTop = baseY - bookHeight
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (books.isEmpty()) return
        val cx = width / 2f - coverW / 2f + dp(12f)

        drawRail(canvas, cx)

        // 远的先画，近的后画（选中/相邻压在上面）。
        // 顺序每帧都在变，用预先分配的 IntArray 就地插入排序，避免 sortedByDescending 的装箱
        buildDrawOrder()
        for (k in 0 until orderCount) {
            val i = drawOrder[k]
            if (i == selected) continue
            val angle = ((i - offset) * 14f).coerceIn(-34f, 34f)
            val pivotX = spineLeft(i, cx) + spineWidth(i) / 2f
            withYRotation(canvas, pivotX, baseY, angle) {
                drawSpine(canvas, i, cx, focused = false)
            }
        }
        if (selected in books.indices) {
            val pivotX = spineLeft(selected, cx) + spineWidth(selected) / 2f
            withYRotation(canvas, pivotX, baseY, -8f, dp(6f)) {
                drawSpine(canvas, selected, cx, focused = true)
            }
            val reveal = coverReveal()
            if (reveal > 0.02f) {
                withYRotation(canvas, spineRect.right, baseY, -24f * reveal, dp(16f) * reveal) {
                    drawCover(canvas, selected, cx, reveal)
                }
            }
        }
        drawEdgeFade(canvas)
    }

    /** 按离当前滚动位置由远到近填好绘制顺序，数组直接复用，长度见 orderCount */
    private fun buildDrawOrder() {
        val size = books.size
        if (drawOrder.size < size) drawOrder = IntArray(size)
        orderCount = size
        for (i in 0 until size) {
            val d = abs(i - offset)
            var j = i
            while (j > 0 && abs(drawOrder[j - 1] - offset) < d) {
                drawOrder[j] = drawOrder[j - 1]
                j--
            }
            drawOrder[j] = i
        }
    }

    private fun drawRail(canvas: Canvas, cx: Float) {
        railPaint.color = withAlpha(ink, 0.10f)
        railPaint.strokeWidth = max(1f, dp(0.8f))
        canvas.drawLine(0f, baseY + dp(4f), width.toFloat(), baseY + dp(4f), railPaint)
    }

    private fun drawSpine(canvas: Canvas, index: Int, cx: Float, focused: Boolean) {
        val left = spineLeft(index, cx)
        val right = left + spineWidth(index)
        if (right < -dp(40f) || left > width + dp(40f)) return

        val top = spineTop + if (focused) 0f else dp(4f)
        val bottom = baseY

        spineRect.set(left, top, right, bottom)

        // 书脊纸面
        spineFill.color = if (focused) spinePaperSelected else spinePaper
        canvas.drawRect(spineRect, spineFill)

        // 书脊侧边投影（越靠里越深）：渐变只建一次，靠平移复用
        val shader = shadowShader ?: LinearGradient(
            0f, 0f, dp(3f), 0f,
            intArrayOf(0x14000000, 0x00000000), null, Shader.TileMode.CLAMP
        ).also { shadowShader = it }
        shadowPaint.shader = shader
        val save = canvas.save()
        canvas.translate(left, 0f)
        tmpRect.set(0f, top, dp(3f), bottom)
        canvas.drawRect(tmpRect, shadowPaint)
        canvas.restoreToCount(save)

        spineStroke.color = withAlpha(ink, if (focused) 0.42f else 0.26f)
        canvas.drawRect(spineRect, spineStroke)

        labels.getOrNull(index)?.let { drawSpineText(canvas, it, left, right, top, bottom, focused) }
    }

    private fun drawSpineText(
        canvas: Canvas,
        label: SpineLabel,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        focused: Boolean,
    ) {
        val centerX = (left + right) / 2f

        // 顶部横排序号
        codeText.textSize = dp(7f)
        codeText.color = withAlpha(ink, 0.55f)
        canvas.drawText(label.ordinal, centerX, top + dp(14f), codeText)

        // 竖排书名：逐字绘制复用预生成的 CharArray，不再 toString() 分配
        val titleSize = if (focused) dp(9.5f) else dp(9f)
        spineText.textSize = titleSize
        spineText.color = withAlpha(ink, if (focused) 0.94f else 0.82f)
        val titleChars = label.titleChars
        val maxChars = ((bottom - top - dp(46f)) / (titleSize + dp(2.4f))).toInt().coerceAtLeast(2)
        val showCount = if (titleChars.size > maxChars) maxChars - 1 else titleChars.size
        val step = titleSize + dp(2.4f)
        var y = top + dp(24f) + titleSize
        var drawn = 0
        while (drawn < showCount) {
            canvas.drawText(titleChars, drawn, 1, centerX, y, spineText)
            y += step
            drawn++
        }
        if (titleChars.size > maxChars) {
            canvas.drawText(ELLIPSIS, centerX, y, spineText)
        }

        // 底部作者（小字，靠下）
        val authorSize = dp(7f)
        spineText.textSize = authorSize
        spineText.color = withAlpha(ink, 0.50f)
        val authorChars = label.authorChars
        var ay = bottom - dp(10f) - (authorChars.size - 1) * (authorSize + dp(1.6f))
        var ai = 0
        while (ai < authorChars.size) {
            canvas.drawText(authorChars, ai, 1, centerX, ay, spineText)
            ay += authorSize + dp(1.6f)
            ai++
        }
    }

    private fun drawCover(canvas: Canvas, index: Int, cx: Float, reveal: Float = 1f) {
        val book = books[index]
        val spine = spineRect
        // 封面从选中书脊右缘沿铰链旋出；拖动时随 reveal 收拢
        val coverWidth = coverW * reveal
        val left = spine.right - dp(1f)
        val top = spineTop + dp(16f) + (1f - reveal) * dp(18f)
        val right = left + coverWidth
        val bottom = baseY - dp(2f)

        // 书页厚度：右缘三层细线
        pageEdge.color = pageEdgeColor
        for (i in 0 until 3) {
            val x = right + dp(1.4f) * (i + 1)
            canvas.drawRect(x, top + dp(3f) + i * dp(0.8f), x + dp(1.2f), bottom - i * dp(0.8f), pageEdge)
        }

        // 封面投影：同样是「建一次渐变 + 平移复用」
        val shader = coverShadowShader ?: LinearGradient(
            0f, 0f, dp(14f), 0f,
            intArrayOf(0x33000000, 0x00000000), null, Shader.TileMode.CLAMP
        ).also { coverShadowShader = it }
        shadowPaint.shader = shader
        val shadowSave = canvas.save()
        canvas.translate(right, 0f)
        tmpRect.set(0f, top + dp(2f), dp(14f), bottom + dp(4f))
        canvas.drawRect(tmpRect, shadowPaint)
        canvas.restoreToCount(shadowSave)

        coverRect.set(left, top, right, bottom)

        val bitmap = coverCache[book.bookUrl]
        if (bitmap != null) {
            clipPath.reset()
            clipPath.addRoundRect(coverRect, dp(2f), dp(2f), Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(bitmap, srcRectFor(bitmap, coverRect, srcRect), coverRect, coverPaint)
            canvas.restoreToCount(save)
        } else {
            drawBlankCover(canvas, index, coverRect)
            loadCover(book)
        }

        spineStroke.color = withAlpha(ink, 0.30f)
        canvas.drawRoundRect(coverRect, dp(2f), dp(2f), spineStroke)
    }

    /**
     * 没有封面图时画一张空白档案封皮：纸底 + 编号 + 竖排书名，
     * 比直接留白更像"档案盒里的一本书"。
     */
    private fun drawBlankCover(canvas: Canvas, index: Int, rect: RectF) {
        val label = labels.getOrNull(index) ?: return
        spineFill.color = coverPaper
        canvas.drawRoundRect(rect, dp(2f), dp(2f), spineFill)

        spineStroke.color = withAlpha(ink, 0.22f)
        canvas.drawRoundRect(rect, dp(2f), dp(2f), spineStroke)

        val inset = dp(7f)
        innerStrokePaint.color = withAlpha(ink, 0.14f)
        innerStrokePaint.strokeWidth = spineStroke.strokeWidth
        canvas.drawRect(
            rect.left + inset, rect.top + inset,
            rect.right - inset, rect.bottom - inset,
            innerStrokePaint
        )

        codeText.textSize = dp(7f)
        codeText.color = withAlpha(ink, 0.5f)
        canvas.drawText(label.code, rect.centerX(), rect.top + dp(22f), codeText)

        spineText.textSize = dp(13f)
        spineText.color = withAlpha(ink, 0.8f)
        val chars = label.blankChars
        val step = dp(17f)
        var ty = rect.top + (rect.height() - chars.size * step) / 2f + dp(16f)
        var i = 0
        while (i < chars.size) {
            canvas.drawText(chars, i, 1, rect.centerX(), ty, spineText)
            ty += step
            i++
        }

        railPaint.color = withAlpha(ink, 0.18f)
        railPaint.strokeWidth = max(1f, dp(0.7f))
        canvas.drawLine(
            rect.left + inset, rect.bottom - dp(22f),
            rect.right - inset, rect.bottom - dp(22f), railPaint
        )
    }

    private fun srcRectFor(bitmap: Bitmap, dst: RectF, out: Rect): Rect {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val scale = max(dst.width() / bw, dst.height() / bh)
        val sw = dst.width() / scale
        val sh = dst.height() / scale
        val sx = (bw - sw) / 2f
        val sy = (bh - sh) / 2f
        out.set(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt())
        return out
    }

    private fun drawEdgeFade(canvas: Canvas) {
        // 左右两端让书脊淡出，避免硬边；渐变按宽度缓存，尺寸不变就不再重建
        val fadeW = dp(26f)
        if (fadeBuiltWidth != width || fadeLeftShader == null || fadeRightShader == null) {
            fadeLeftShader = LinearGradient(
                0f, 0f, fadeW, 0f,
                intArrayOf(paper, withAlpha(paper, 0f)), null, Shader.TileMode.CLAMP
            )
            fadeRightShader = LinearGradient(
                width - fadeW, 0f, width.toFloat(), 0f,
                intArrayOf(withAlpha(paper, 0f), paper), null, Shader.TileMode.CLAMP
            )
            fadeBuiltWidth = width
        }
        fadePaint.shader = fadeLeftShader
        canvas.drawRect(0f, 0f, fadeW, height.toFloat(), fadePaint)
        fadePaint.shader = fadeRightShader
        canvas.drawRect(width - fadeW, 0f, width.toFloat(), height.toFloat(), fadePaint)
        fadePaint.shader = null
    }

    // ---------------------------------------------------------------- 封面

    private fun prefetchCover(index: Int) {
        books.getOrNull(index)?.let { loadCover(it) }
        books.getOrNull(index - 1)?.let { loadCover(it) }
        books.getOrNull(index + 1)?.let { loadCover(it) }
    }

    private fun loadCover(book: Book) {
        if (coverCache.containsKey(book.bookUrl)) return
        coverCache[book.bookUrl] = null
        val path = book.getDisplayCover() ?: book.coverUrl ?: return
        if (path.isBlank()) return
        Glide.with(this).asBitmap()
            .load(path)
            .into(object : CustomTarget<Bitmap>(
                coverW.toInt().coerceAtLeast(1),
                coverH.toInt().coerceAtLeast(1)
            ) {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    coverCache[book.bookUrl] = resource
                    if (isAttachedToWindow) invalidate()
                }

                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) = Unit
            })
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (books.isEmpty()) return false
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downOffset = offset
                dragging = false
                verticalGesture = false
                // 按下时不抢手势：竖向拖动要留给父级列表滚动。
                // 以前在 DOWN 里就 requestDisallowInterceptTouchEvent(true)，
                // 从书脊区起手的竖直滑动会被这里整段吃掉，列表一动不动。
                parent?.requestDisallowInterceptTouchEvent(false)
                scroller.forceFinished(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && !verticalGesture) {
                    if (abs(dy) > touchSlop && abs(dy) >= abs(dx)) {
                        // 判定为竖向滑动，本次手势不再处理，等父级接管
                        verticalGesture = true
                        (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                        scroller.forceFinished(true)
                    } else if (abs(dx) > touchSlop) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (verticalGesture) return true
                if (dragging) {
                    offset = (downOffset - dx / pitch).coerceIn(-0.35f, (books.size - 1 + 0.35f))
                    val nearest = offset.roundToInt().coerceIn(0, books.size - 1)
                    if (nearest != selected) {
                        selected = nearest
                        onSelect?.invoke(nearest)
                        prefetchCover(nearest)
                    }
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = velocityTracker?.xVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null

                if (verticalGesture) {
                    verticalGesture = false
                    dragging = false
                    return true
                }
                // 用户手势驱动的吸附：落定后要回调，让列表跟到同一本
                notifySettle = true
                if (!dragging) {
                    handleTap(event.x, event.y)
                } else if (scroller.isFinished) {
                    if (abs(vx) > dp(560f)) {
                        val step = if (vx < 0) 1 else -1
                        val target = (offset.roundToInt() + step).coerceIn(0, books.size - 1)
                        startScrollTo(target)
                    } else {
                        startScrollTo(offset.roundToInt().coerceIn(0, books.size - 1))
                    }
                }
            }
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        // 命中封面
        if (coverRect.contains(x, y)) {
            books.getOrNull(selected)?.let { onOpen?.invoke(it) }
            return
        }
        // 命中任意书脊
        val cx = width / 2f - coverW / 2f + dp(12f)
        for (i in books.indices) {
            val left = spineLeft(i, cx)
            if (x >= left && x <= left + spineWidth(i) && y >= spineTop && y <= baseY) {
                if (i == selected) {
                    onOpen?.invoke(books[i])
                } else {
                    selected = i
                    onSelect?.invoke(i)
                    startScrollTo(i)
                }
                return
            }
        }
    }

    private fun startScrollTo(target: Int) {
        scroller.forceFinished(true)
        selected = target
        val start = (offset * 1000).toInt()
        val end = target * 1000
        if (start == end) {
            offset = target.toFloat()
            invalidate()
            settling = true
            computeScroll()
            return
        }
        settling = true
        scroller.startScroll(start, 0, end - start, 0, 560)
        postInvalidateOnAnimation()
        // 兜底：若最后一次绘制没触发 computeScroll，也要把吸附结果交出去
        postDelayed({ finishSettle() }, 660L)
    }

    private fun finishSettle() {
        if (!settling) return
        settling = false
        offset = selected.toFloat()
        invalidate()
        if (notifySettle) onSettle?.invoke(selected)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offset = scroller.currX / 1000f
            invalidate()
        } else if (settling) {
            finishSettle()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 主题/夜间模式变化后重新取色，绘制期就不用反复查资源了
        refreshColors()
        shadowShader = null
        coverShadowShader = null
    }

    override fun onDetachedFromWindow() {
        scroller.abortAnimation()
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

}
