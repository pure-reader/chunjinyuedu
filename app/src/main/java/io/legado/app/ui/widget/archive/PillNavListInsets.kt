package io.legado.app.ui.widget.archive

import androidx.recyclerview.widget.RecyclerView

// 悬浮胶囊导航占据的底部空间：胶囊高 80dp + 距屏幕底 16dp，再留 8dp 呼吸位。
// ViewPager 已扣除系统导航条高度，故在 Fragment 坐标系里胶囊固定占底部 104dp。
private const val PILL_NAV_RESERVE_DP = 104

/**
 * 让主界面的滚动列表避开悬浮胶囊导航：
 * - clipToPadding=false：内容仍绘制进 padding 区（胶囊下方），毛玻璃可透出滚动内容；
 * - 底部预留 80dp：滚动到底时最后一项停在胶囊上方，不会被遮挡、可正常点击。
 *
 * 只增大 bottom padding，保留原有 left/top/right，且不会缩小已有的更大 bottom padding。
 */
fun RecyclerView.applyPillNavBottomInset() {
    clipToPadding = false
    val reservePx = (PILL_NAV_RESERVE_DP * resources.displayMetrics.density).toInt()
    if (paddingBottom < reservePx) {
        setPadding(paddingLeft, paddingTop, paddingRight, reservePx)
    }
}
