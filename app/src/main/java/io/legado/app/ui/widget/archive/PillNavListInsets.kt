package io.legado.app.ui.widget.archive

import android.content.Context
import android.provider.Settings
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import java.util.WeakHashMap

/**
 * 悬浮胶囊导航的底部几何。
 *
 * 三键导航和手势导航（小白条）差别很大，之前直接用 `navHeight + 16dp`：
 * - 三键导航系统栏 ~48dp，内容区被顶上去，胶囊离底部很正常；
 * - 手势导航的小白条实际只占十几个 dp，但不少 ROM 仍按 48dp 上报，
 *   于是胶囊被顶到半空；也有 ROM（以及本机模拟器切手势后）直接上报 0，
 *   胶囊又贴着小白条。
 * 结果就是同一份布局在模拟器和真机上位置完全不同。
 *
 * 这里按导航方式分开处理：
 * - 三键：完整避让系统栏（10dp + inset）；
 * - 手势：只避让小白条的视觉高度（最多 24dp），并保证至少 10dp 间距。
 */
object PillNavInset {

    /** 胶囊高度，与 activity_main.xml 中的 64dp 保持一致。 */
    const val PILL_HEIGHT_DP = 64f

    private const val GAP_KEY_DP = 10f
    private const val GAP_GESTURE_DP = 4f
    private const val GESTURE_VISUAL_MAX_DP = 24f
    private const val LIST_BREATH_DP = 10f
    private const val FALLBACK_RESERVE_DP = 90f

    /**
     * 列表底部需要预留的高度（px），由 MainActivity 拿到真实 inset 后写入。
     * 0 表示还没算出来，此时退回默认值。
     */
    @Volatile
    var listReservePx: Int = 0
        private set

    private val basePadding = WeakHashMap<RecyclerView, Int>()

    fun isGestureNavigation(context: Context): Boolean {
        val mode = runCatching {
            Settings.Secure.getInt(context.contentResolver, "navigation_mode")
        }.getOrDefault(-1)
        return when (mode) {
            2 -> true
            0, 1 -> false
            else -> {
                // 读不到设置时用高度反推：手势区明显矮于三键导航条
                val density = context.resources.displayMetrics.density
                val navHeight = context.navigationBarHeight
                navHeight in 1 until (40 * density).toInt()
            }
        }
    }

    /**
     * 胶囊底边到屏幕底部的距离。
     *
     * @param navInsetPx 窗口实际拿到的 systemBars 底部 inset
     */
    fun bottomMarginPx(context: Context, navInsetPx: Int): Int {
        val density = context.resources.displayMetrics.density
        val inset = navInsetPx.coerceAtLeast(0)
        return if (isGestureNavigation(context)) {
            val visual = inset.coerceAtMost((GESTURE_VISUAL_MAX_DP * density).toInt())
            maxOf(
                (GAP_KEY_DP * density).toInt(),
                visual + (GAP_GESTURE_DP * density).toInt()
            )
        } else {
            (GAP_KEY_DP * density).toInt() + inset
        }
    }

    /**
     * 胶囊顶边到 ViewPager 底部的距离，即列表滚动到底时最后一项需要让出的空间。
     * ViewPager 自身已经按系统 inset 缩进过 [navInsetPx]，这里要扣掉，避免重复预留。
     */
    fun listReserveFor(context: Context, navInsetPx: Int): Int {
        val density = context.resources.displayMetrics.density
        val fromScreenBottom = bottomMarginPx(context, navInsetPx) +
                (PILL_HEIGHT_DP * density).toInt() +
                (LIST_BREATH_DP * density).toInt()
        return (fromScreenBottom - navInsetPx.coerceAtLeast(0))
            .coerceAtLeast((LIST_BREATH_DP * density).toInt())
    }

    fun updateListReserve(context: Context, navInsetPx: Int) {
        val reserve = listReserveFor(context, navInsetPx)
        if (reserve != listReservePx) {
            listReservePx = reserve
            // 已经接入过预留的列表立刻跟着改，避免首帧算出的值和真实 inset 不一致
            basePadding.keys.toList().forEach { applyTo(it) }
        }
    }

    internal fun applyTo(view: RecyclerView) {
        val base = basePadding[view] ?: view.paddingBottom
        basePadding[view] = base
        val reserve = listReservePx.takeIf { it > 0 }
            ?: fallbackReserve(view.context)
        val target = maxOf(base, reserve)
        if (view.paddingBottom != target) {
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, target)
        }
    }

    internal fun fallbackReserve(context: Context): Int =
        (FALLBACK_RESERVE_DP * context.resources.displayMetrics.density).toInt()
}

/**
 * 让主界面的滚动列表避开悬浮胶囊导航：
 * - clipToPadding=false：内容仍绘制进 padding 区（胶囊下方），毛玻璃可透出滚动内容；
 * - 底部预留「胶囊底距 + 胶囊高 + 呼吸位」：滚动到底时最后一项停在胶囊上方，
 *   不会被遮挡、可正常点击。
 *
 * 手势导航下胶囊被抬高了，预留高度也要跟着变，否则最后一项仍会被压住，
 * 所以这里每次都用 MainActivity 算好的实时值。
 */
fun RecyclerView.applyPillNavBottomInset() {
    clipToPadding = false
    PillNavInset.applyTo(this)
    // PreferenceFragment 之类的列表会自己挂一个「只避让系统栏」的监听，
    // 这里必须接管：否则 inset 晚一步派发时会把预留改回 navHeight，胶囊又压回最后一项。
    setOnApplyWindowInsetsListenerCompat { view, insets ->
        PillNavInset.updateListReserve(view.context, insets.navigationBarHeight)
        insets
    }
}
