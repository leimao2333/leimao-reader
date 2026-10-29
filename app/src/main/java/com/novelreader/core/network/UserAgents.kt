package com.novelreader.core.network

import com.novelreader.core.version.AppVersion

/**
 * 全应用请求头 UA 的**唯一定义处**（v1.9.2.3）。
 *
 * 此前项目里散着三份一模一样的字符串（`VeneraEngine` / `CoverDefaultsInterceptor` /
 * `ComicReaderScreen`），都是 Android 移动版 Chrome UA。参照 venera 官方仓库核对后发现，
 * 官方其实用了**两套不同**的 UA，用途分明：
 *
 * 1. **源脚本的 API / HTML 请求** —— `lib/network/app_dio.dart`：
 *    `options.headers['User-Agent'] = "venera/v${App.version}"`，即 [APP]。
 *    这是一个**非移动端**的自定义串。
 * 2. **图片请求** —— `lib/foundation/consts.dart` 的 `webUA`（[WEB]），
 *    外加 `lib/network/images.dart` 里「源没声明 UA 时补 webUA」的逻辑。
 *    它是桌面版 Windows Chrome UA。
 *
 * 为什么这个区别对我们有实际影响：源站会按 UA 里的 `Mobile` 分词决定发**桌面版还是
 * 移动版 DOM**，而源脚本的选择器全是照桌面版写的。实测绅士漫画搜索页
 * （2026-10-29，`https://wnacg.com/search/?q=全彩`）：
 *
 * | UA | `<p class="result">` | `gallary_wrap` | `<ul class="cc">` |
 * |---|---|---|---|
 * | 移动版 Chrome（旧值） | 0 | 0 | 0 |
 * | 桌面版 Chrome | 2 | 1 | 1 |
 * | `venera/v1.9.2.3` | 2 | 1 | 1 |
 *
 * 也就是「搜索有结果但解析出 0 条」。换成官方口径后版式与桌面版完全一致；ehentai /
 * hitomi / goda / jcomic 的首页与搜索页实测无回归（同状态码、同量级字节数）。
 */
object UserAgents {

    /**
     * 网页 / 图片请求 UA，对齐 venera `lib/foundation/consts.dart` 的 `webUA`。
     *
     * 用于：阅读页正文图片、书架与搜索页封面（Coil）、以及任何「当作浏览器去取
     * 静态资源」的场合。桌面版标识能让图床与站点走和桌面浏览器一致的分支。
     */
    const val WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"

    /**
     * Venera 源脚本的 API / HTML 请求 UA，对齐 venera `lib/network/app_dio.dart`。
     *
     * 带上版本号是官方行为，用于站点侧区分客户端版本；这里跟随应用版本走。
     */
    val APP: String = "venera/v" + AppVersion.CURRENT_VERSION
}
