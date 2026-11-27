package io.legado.app.constant

import androidx.annotation.IntDef

@Suppress("ConstPropertyName")
object PageAnim {

    const val coverPageAnim = 0

    const val slidePageAnim = 1

    const val simulationPageAnim = 2

    const val scrollPageAnim = 3

    const val noAnim = 4

    /** 档案斜切：纸片沿斜边抽走，新页斜向落位（轻小说式转场） */
    const val slantPageAnim = 5

    /** 档案流动：整页平滑横移，两页位移互补，不再按段落切条 */
    const val relayPageAnim = 6

    @Target(AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.SOURCE)
    @IntDef(
        coverPageAnim,
        slidePageAnim,
        simulationPageAnim,
        scrollPageAnim,
        noAnim,
        slantPageAnim,
        relayPageAnim
    )
    annotation class Anim

}