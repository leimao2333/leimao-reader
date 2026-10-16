package com.novelreader.core.util

import kotlinx.coroutines.CancellationException

/**
 * 协程作用域里的「安全捕获」：与 stdlib [runCatching] 唯一区别是
 * **不吞取消异常**（[CancellationException] 原样抛出）。
 *
 * 背景（2026-10-16 真机定位）：首页推荐给每个源 10s 预算
 * （`withTimeoutOrNull(SOURCE_PROBE_TIMEOUT_MS)`），到点抛的是
 * `TimeoutCancellationException`（[CancellationException] 子类）。
 * 调用链里凡是用 stdlib `runCatching` 包住这段代码的地方，取消
 * 一律被当普通异常吞掉，于是「预算已到头却继续跑」——
 * 实测漫画源单源实烧 16~90s（`categoryComics` 超时后还要跑
 * `search` 回落，两次引擎调用叠加），把共享引擎的 JS 线程彻底排爆，
 * 整页推荐只剩零星几个源出数。
 *
 * 规则：**协程里（尤其是 withTimeout / withTimeoutOrNull 的作用域内）
 * 捕获异常一律用本函数**，不要用 stdlib 版。
 */
suspend fun <T> runCatchingCc(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        // 取消是协作式的：往上抛，让 withTimeout 正常收场
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
