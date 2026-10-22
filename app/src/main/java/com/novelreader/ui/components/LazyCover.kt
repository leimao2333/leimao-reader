package com.novelreader.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter

/**
 * 懒加载封面（v1.8.7.5）：**不用 subcomposition** 的封面组件。
 *
 * ## 为什么换掉 `SubcomposeAsyncImage`
 *
 * `SubcomposeAsyncImage` 会把内容放进一个 **SubcomposeLayout** 里，而 LazyList / LazyGrid
 * 本身也是 SubcomposeLayout —— 两层嵌套时，一旦**快速滚动**（尤其是拖侧边滚动条一步跳
 * 几百项），复用的槽位还在测量、旧节点已被回收，Compose 就会抛
 *
 * ```
 * IllegalArgumentException: measure is called on a deactivated node
 *   at LazyLayoutMeasureScopeImpl.measure
 *   → LazyList/LazyGridMeasuredItemProvider.getAndMeasure
 * ```
 *
 * 进程当场崩掉（本项目 v1.8.1.9 的崩溃日志就是这个栈，只是当时还没定位到触发条件）。
 *
 * ## 为什么图片层必须**一直**绘制
 *
 * Coil 的 [AsyncImagePainter] 是**懒启动**的：请求在它第一次 `onDraw` 时才发出去。
 * 初版这里写的是「成功才画 Image，否则只画占位」，于是 painter 从来没被绘制过 ——
 * 请求根本不会开始，状态永远停在 Empty，封面**全部退回首字占位**，
 * 表现就是「图片怎么不加载了」。
 *
 * 现在的结构：图片层始终绘制（没加载完时它画的是透明），占位层在「还没成功」时叠在上面。
 * 既保证请求一定会启动，也不改观感 —— 加载完之前看到的仍是首字占位。
 *
 * @param fallback 无封面或还没加载出来时画什么（通常是 [CoverPlaceholderLetter]）
 */
@Composable
fun LazyCover(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable () -> Unit,
) {
    val url = (model as? String)?.takeIf { it.isNotBlank() } ?: model?.takeIf { it !is String }
    if (url == null) {
        fallback()
        return
    }
    val painter = rememberAsyncImagePainter(model = url)
    // coil 2.x 的 `painter.state` 直接返回当前状态（内部由 mutableStateOf 支撑），
    // 在组合中读取即订阅，加载完成后会自动重组
    val state = painter.state

    Box(modifier = modifier) {
        // 这一层是「请求的触发器」，不能因为还没加载完就跳过
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.matchParentSize(),
        )
        if (state !is AsyncImagePainter.State.Success) {
            Box(Modifier.matchParentSize()) { fallback() }
        }
    }
}
