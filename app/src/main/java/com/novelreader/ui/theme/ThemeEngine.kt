package com.novelreader.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 主题引擎：从一个「种子色」生成一整套 Material3 配色。
 *
 * 与 `Theme.kt` 里写死的 [lightColorScheme] / [darkColorScheme] 不同，这里的配色是**算出来的**：
 * 只要给一个种子色（用户选的色、或从封面图里抽出来的主色），就能得到与之同色相、
 * 但明暗层次齐全的 40 多个颜色角色。
 *
 * 为什么需要它：应用的主题是用户设置项，而「按封面取色」（对标 legado 的动态取色）要求
 * 不新增任何依赖、不引入 MaterialKolor。这里的做法是 MaterialKolor 的极简版 ——
 * 把种子色拆成 HSV，按色相旋转 + 明度分层直接产出各角色，不跑 HCT 色域映射。
 * 代价是某些极端饱和度下与原版有细微色差，收益是零依赖、纯函数、可单测（不碰 android.*）。
 *
 * 用法（`Theme.kt` 目前仍是写死的两套配色，接入时把 `NovelReaderTheme` 里选 scheme 的那一行
 * 换成「有种子色就用引擎生成」即可）：
 * ```
 * // 用户自定义主题色
 * val scheme = ThemeEngine.fromSeedColor(0xFF7B1FA2)
 * // 从封面取色（先解码 Bitmap，见 [ImageSeedColorExtractor]）
 * val seed = ImageSeedColorExtractor.extract(bitmap)
 * ```
 *
 * 约定：所有 `onXxx` 都不靠「拍脑袋选白/黑」，而是用 [readableOn] 按实际生成的背景明度反推，
 * 保证「深浅色怎么算都不会出现深底黑字」。
 */
object ThemeEngine {

    // ===================== 对外 API =====================

    /**
     * 用种子色生成**浅色**配色。
     *
     * @param seed 种子色，通常来自用户选择或封面主色；透明度会被忽略。
     */
    fun dynamicLightScheme(seed: Color): ColorScheme = buildScheme(seed.toHsv(), dark = false)

    /**
     * 用种子色生成**深色**配色。
     *
     * @param seed 种子色，通常来自用户选择或封面主色；透明度会被忽略。
     */
    fun dynamicDarkScheme(seed: Color): ColorScheme = buildScheme(seed.toHsv(), dark = true)

    /** [dynamicLightScheme] 的 argb Int 版本，方便直接吃 `Bitmap.getPixel()` / `Resources.getColor()` 的结果。 */
    fun fromSeedColor(argb: Int): ColorScheme = dynamicLightScheme(Color(argb))

    /** [dynamicDarkScheme] 的 argb Int 版本。 */
    fun fromSeedColorDark(argb: Int): ColorScheme = dynamicDarkScheme(Color(argb))

    /** 按明暗二选一，省掉调用方写 if。 */
    fun fromSeedColor(argb: Int, dark: Boolean): ColorScheme =
        if (dark) fromSeedColorDark(argb) else fromSeedColor(argb)

    /**
     * 这个颜色在视觉上算深色吗。
     *
     * 用 [luminance]（已做 sRGB 伽马校正的感知明度）而不是 `(r+g+b)/3`：数值上平均很亮的
     * 深蓝、深绿，肉眼其实是深底，必须配白字（与 `SystemBars.kt` 里的判断口径保持一致）。
     */
    fun isDark(color: Color): Boolean = color.luminance() < 0.5f

    /**
     * 在 [background] 之上保证可读的前景色（纯黑或纯白）。
     *
     * 阅读页/封面上叠文字时用这个，别写死 `Color.White` —— 「藏蓝」这类纸张色上白字才清楚，
     * 而「米黄」上必须换黑字，否则对比度不足。
     */
    fun readableOn(background: Color): Color = if (isDark(background)) Color.White else Color.Black

    // ===================== 配色构造 =====================

    /**
     * 由种子色的 HSV 生成整套角色。
     *
     * 层次规则（简化版 MaterialKolor）：
     * - 主色系沿用种子色相；**次级**色相 +30°、**三级**色相 -30°，形成同族但可区分的三段。
     * - container 类：降低饱和度、抬高（浅色）或压低（深色）明度，作为「浅底深字」的容器。
     * - surface / background：种子色相 + 极低饱和度，让整页带一点同族色调而不是死灰。
     * - 所有 onXxx 由 [readableOn] 按容器实际明度决定，杜绝深底黑字。
     *
     * @param seed 种子色的 HSV（只取色相与饱和度，明度由各角色自己定）
     */
    private fun buildScheme(seed: Hsv, dark: Boolean): ColorScheme {
        val hue = seed.hue
        // 饱和度处理分两段：
        // - 几乎无彩（灰阶封面、黑白纯色种子）：保持中性，生成一套灰阶主题，而不是硬塞一个色相 ——
        //   否则任何黑白封面都会被染成「红色主题」（hue 在灰阶时被定义为 0）。
        // - 有一点颜色：抬到 0.35 下限，避免封面上一块很淡的色被稀释成灰色；上限 0.85 防荧光刺眼。
        val sat = if (seed.saturation < 0.08f) {
            seed.saturation
        } else {
            seed.saturation.coerceIn(0.35f, 0.85f)
        }

        val secondaryHue = hue + 30f
        val tertiaryHue = hue - 30f

        return if (dark) {
            val primary = hsv(hue, sat * 0.65f, 0.82f)
            val secondary = hsv(secondaryHue, sat * 0.45f, 0.78f)
            val tertiary = hsv(tertiaryHue, sat * 0.50f, 0.80f)
            val primaryContainer = hsv(hue, sat * 0.55f, 0.30f)
            val secondaryContainer = hsv(secondaryHue, sat * 0.40f, 0.28f)
            val tertiaryContainer = hsv(tertiaryHue, sat * 0.40f, 0.28f)
            val surface = hsv(hue, 0.09f, 0.10f)
            val background = hsv(hue, 0.10f, 0.07f)
            val surfaceVariant = hsv(hue, 0.08f, 0.28f)

            darkColorScheme(
                primary = primary,
                onPrimary = readableOn(primary),
                primaryContainer = primaryContainer,
                onPrimaryContainer = readableOn(primaryContainer),
                inversePrimary = hsv(hue, sat * 0.55f, 0.42f),
                secondary = secondary,
                onSecondary = readableOn(secondary),
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = readableOn(secondaryContainer),
                tertiary = tertiary,
                onTertiary = readableOn(tertiary),
                tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = readableOn(tertiaryContainer),
                background = background,
                onBackground = hsv(hue, 0.04f, 0.90f),
                surface = surface,
                onSurface = hsv(hue, 0.04f, 0.90f),
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = hsv(hue, 0.05f, 0.78f),
                surfaceTint = primary,
                surfaceDim = hsv(hue, 0.09f, 0.08f),
                surfaceBright = hsv(hue, 0.08f, 0.24f),
                surfaceContainerLowest = hsv(hue, 0.10f, 0.05f),
                surfaceContainerLow = hsv(hue, 0.09f, 0.09f),
                surfaceContainer = hsv(hue, 0.09f, 0.12f),
                surfaceContainerHigh = hsv(hue, 0.09f, 0.16f),
                surfaceContainerHighest = hsv(hue, 0.08f, 0.21f),
                inverseSurface = hsv(hue, 0.05f, 0.90f),
                inverseOnSurface = hsv(hue, 0.08f, 0.12f),
                outline = hsv(hue, 0.06f, 0.60f),
                outlineVariant = hsv(hue, 0.07f, 0.32f),
                error = Color(0xFFF2B8B5),
                onError = Color(0xFF601410),
                errorContainer = Color(0xFF8C1D18),
                onErrorContainer = Color(0xFFF9DEDC),
                scrim = Color.Black,
            )
        } else {
            val primary = hsv(hue, sat, 0.40f)
            val secondary = hsv(secondaryHue, sat * 0.55f, 0.48f)
            val tertiary = hsv(tertiaryHue, sat * 0.60f, 0.44f)
            val primaryContainer = hsv(hue, sat * 0.32f, 0.92f)
            val secondaryContainer = hsv(secondaryHue, sat * 0.28f, 0.93f)
            val tertiaryContainer = hsv(tertiaryHue, sat * 0.28f, 0.93f)
            val surface = hsv(hue, 0.03f, 0.99f)
            val background = hsv(hue, 0.04f, 0.985f)
            val surfaceVariant = hsv(hue, 0.07f, 0.92f)

            lightColorScheme(
                primary = primary,
                onPrimary = readableOn(primary),
                primaryContainer = primaryContainer,
                onPrimaryContainer = hsv(hue, sat * 0.60f, 0.18f),
                inversePrimary = hsv(hue, sat * 0.60f, 0.80f),
                secondary = secondary,
                onSecondary = readableOn(secondary),
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = hsv(secondaryHue, sat * 0.55f, 0.20f),
                tertiary = tertiary,
                onTertiary = readableOn(tertiary),
                tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = hsv(tertiaryHue, sat * 0.55f, 0.20f),
                background = background,
                onBackground = hsv(hue, 0.10f, 0.12f),
                surface = surface,
                onSurface = hsv(hue, 0.10f, 0.12f),
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = hsv(hue, 0.10f, 0.34f),
                surfaceTint = primary,
                surfaceDim = hsv(hue, 0.06f, 0.86f),
                surfaceBright = hsv(hue, 0.03f, 0.99f),
                surfaceContainerLowest = Color.White,
                surfaceContainerLow = hsv(hue, 0.03f, 0.97f),
                surfaceContainer = hsv(hue, 0.04f, 0.95f),
                surfaceContainerHigh = hsv(hue, 0.05f, 0.92f),
                surfaceContainerHighest = hsv(hue, 0.06f, 0.89f),
                inverseSurface = hsv(hue, 0.10f, 0.18f),
                inverseOnSurface = hsv(hue, 0.04f, 0.94f),
                outline = hsv(hue, 0.08f, 0.55f),
                outlineVariant = hsv(hue, 0.08f, 0.82f),
                error = Color(0xFFB3261E),
                onError = Color.White,
                errorContainer = Color(0xFFF9DEDC),
                onErrorContainer = Color(0xFF410E0B),
                scrim = Color.Black,
            )
        }
    }

    // ===================== HSV 工具 =====================
    // 手写而不是用 android.graphics.Color.colorToHSV：这里保持纯 Kotlin（不碰 android.*），
    // 主题计算就能在 JVM 单元测试里直接跑，不受 Robolectric / 桩实现限制。

    /** 色相 0..360，饱和度 / 明度 0..1 */
    internal data class Hsv(val hue: Float, val saturation: Float, val value: Float)

    /** RGB -> HSV。灰阶（max == min）色相定义为 0。 */
    internal fun Color.toHsv(): Hsv {
        val r = red
        val g = green
        val b = blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        val normalizedHue = if (hue < 0f) hue + 360f else hue
        val saturation = if (max == 0f) 0f else delta / max
        return Hsv(normalizedHue, saturation, max)
    }

    /** HSV -> [Color]（sRGB）。色相会自动归一到 0..360。 */
    internal fun hsv(hue: Float, saturation: Float, value: Float): Color {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = v - c
        val r: Float
        val g: Float
        val b: Float
        when {
            h < 60f -> { r = c; g = x; b = 0f }
            h < 120f -> { r = x; g = c; b = 0f }
            h < 180f -> { r = 0f; g = c; b = x }
            h < 240f -> { r = 0f; g = x; b = c }
            h < 300f -> { r = x; g = 0f; b = c }
            else -> { r = c; g = 0f; b = x }
        }
        return Color(r + m, g + m, b + m, 1f)
    }
}
