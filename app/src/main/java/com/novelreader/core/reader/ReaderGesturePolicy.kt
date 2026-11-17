package com.novelreader.core.reader

/**
 * 阅读页手势策略（A8）。
 *
 * 把「点哪里 → 做什么」的判定从 `NovelReaderScreen` 的硬编码里抽出来，做成可配置的纯逻辑：
 * - **点击分区**：左 / 中 / 右三区的宽度比例可调（默认各 1/3）；也可切换成
 *   「仅左右两区（中间也翻页）」或「仅中间呼菜单」等模式；
 * - **下拉加书签**：顶部下拉超过阈值即加书签（对应官方的 `PullBookmarkGesture`）；
 * - **手柄翻页**：把 KeyEvent 的按键码映射成翻页方向。
 *
 * 全部为纯函数，不依赖 Android / Compose，便于单测。
 */
object ReaderGesturePolicy {

    /** 点击区域划分模式 */
    enum class TapZoneMode {
        /** 左 1/3 上一页、中 1/3 呼菜单、右 1/3 下一页（默认） */
        THREE_ZONE,

        /** 左半屏上一页、右半屏下一页（没有呼菜单区域，菜单靠长按） */
        TWO_ZONE,

        /** 只保留左右翻页，中间区域也按「靠近哪边算哪边」 */
        EDGE_ONLY,
    }

    /** 点击结果的语义动作 */
    enum class TapAction { PREV_PAGE, NEXT_PAGE, TOGGLE_MENU, NONE }

    /**
     * 根据点击横坐标判定动作。
     *
     * @param x 点击点相对阅读区的 x（像素）
     * @param width 阅读区宽度（像素），<= 0 时返回 [TapAction.NONE]
     * @param mode 区域划分模式
     * @param leftRatio 左区宽度比例（仅 [TapZoneMode.THREE_ZONE] 生效），默认 1/3
     * @param rightRatio 右区宽度比例，默认 1/3
     */
    fun resolveTap(
        x: Float,
        width: Float,
        mode: TapZoneMode = TapZoneMode.THREE_ZONE,
        leftRatio: Float = 1f / 3f,
        rightRatio: Float = 1f / 3f,
    ): TapAction {
        if (width <= 0f || x.isNaN()) return TapAction.NONE
        val px = x.coerceIn(0f, width)
        return when (mode) {
            TapZoneMode.TWO_ZONE -> if (px < width / 2f) TapAction.PREV_PAGE else TapAction.NEXT_PAGE

            TapZoneMode.EDGE_ONLY -> {
                // 中间也算「靠近哪边」：以中线为界，不产生呼菜单区域
                if (px < width / 2f) TapAction.PREV_PAGE else TapAction.NEXT_PAGE
            }

            TapZoneMode.THREE_ZONE -> {
                val left = (width * leftRatio.coerceIn(0.1f, 0.6f))
                val right = (width * rightRatio.coerceIn(0.1f, 0.6f))
                when {
                    px < left -> TapAction.PREV_PAGE
                    px > width - right -> TapAction.NEXT_PAGE
                    else -> TapAction.TOGGLE_MENU
                }
            }
        }
    }

    /** 下拉加书签的触发阈值（dp） */
    const val PULL_BOOKMARK_THRESHOLD_DP: Float = 72f

    /** 下拉手势的最大有效距离（dp），超过视为普通滚动，不重复触发 */
    const val PULL_BOOKMARK_MAX_DP: Float = 160f

    /**
     * 判断一次竖直下拉是否应触发「加书签」。
     *
     * 条件：向下位移 ≥ 阈值、横向位移很小（避免斜滑误触）、且尚未触发过。
     *
     * @param dy 手指竖直位移（正数 = 向下）
     * @param dx 手指横向位移
     * @param alreadyTriggered 本次手势是否已经加过书签（防止一次下拉加多个）
     */
    fun shouldPullBookmark(
        dy: Float,
        dx: Float,
        thresholdDp: Float = PULL_BOOKMARK_THRESHOLD_DP,
        maxDp: Float = PULL_BOOKMARK_MAX_DP,
        alreadyTriggered: Boolean = false,
    ): Boolean {
        if (alreadyTriggered) return false
        if (dy < thresholdDp) return false
        if (dy > maxDp) return false
        // 横向位移超过竖直的一半，认为是斜滑（可能是翻页手势），不加书签
        if (kotlin.math.abs(dx) > dy / 2f) return false
        return true
    }

    /**
     * 手柄 / 键盘按键 → 翻页方向。
     *
     * @param keyCode Android `KeyEvent.KEYCODE_*` 数值
     * @return 1 = 下一页，-1 = 上一页，0 = 不处理
     *
     * 说明：这里只做**数值映射**，不 import android.view.KeyEvent，保持纯 Kotlin 可测。
     * 已知常量：DPAD_UP=19、DPAD_DOWN=20、DPAD_LEFT=21、DPAD_RIGHT=22、
     * PAGE_UP=92、PAGE_DOWN=93、BUTTON_A=96、BUTTON_B=97、VOLUME_UP=24、VOLUME_DOWN=25。
     */
    fun gamepadDirection(keyCode: Int): Int = when (keyCode) {
        19, 21, 92, 24 -> -1   // 上 / 左 / PageUp / 音量+
        20, 22, 93, 25 -> 1    // 下 / 右 / PageDown / 音量-
        96 -> 1                // BUTTON_A 下一页
        97 -> -1               // BUTTON_B 上一页
        else -> 0
    }

    /** 该按键是否被当作「翻页键」（供调用方决定要不要 consume 事件） */
    fun isPageTurnKey(keyCode: Int): Boolean = gamepadDirection(keyCode) != 0
}
