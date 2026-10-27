package com.novelreader.core.network

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 单次请求的**预算**（v1.8.8.4）：由调用方通过协程上下文下发给适配器。
 *
 * 存在的理由：适配器（[com.novelreader.source.legado.LegadoSourceAdapter] /
 * [com.novelreader.source.venera.VeneraSourceAdapter]）是长生命周期的单例对象，
 * 没法为「这一次标签搜索」单独改超时；而给 `SourceAdapter` 的每个方法加参数会波及
 * 全部实现类。协程上下文元素既能按调用点定制，又随协程自动传播、自动回收。
 *
 * 两个字段解决的是两件事：
 *
 * - [callTimeoutMs] —— 交给 OkHttp 的 `callTimeout`。**这是关键**：`withTimeout`
 *   只能取消协程，取消不了已经阻塞在 `call.execute()` 里的 socket 读，
 *   于是「8 秒硬超时」实际要等满 connect 15s / read 20s，进度条就卡在那儿不动。
 *   `callTimeout` 到点会真正中断连接，`execute()` 立刻抛 `InterruptedIOException`。
 * - [queueWaitMs] —— Venera 引擎是**全应用一个 JS 线程**，所有源调用排在同一个
 *   `callMutex` 后面。标签搜索一次要打几十个源，用默认的 25 秒排队预算，排在后面的
 *   源光是等锁就超过外层 8 秒超时，结果全部丢弃还白占着锁。这里给标签搜索一个
 *   很短的排队预算：拿不到锁就快速失败，把机会让给下一个源。
 *
 * 未设置时所有行为与改动前一致（各适配器回落到自己的默认值）。
 */
class RequestBudget(
    /** OkHttp `callTimeout`（毫秒）；null = 用适配器自己的默认值 */
    val callTimeoutMs: Long? = null,
    /** Venera 引擎排队预算（毫秒）；null = 用 `ENGINE_QUEUE_WAIT_MS` */
    val queueWaitMs: Long? = null,
    /**
     * 这次调用是否属于**用户没在盯着**的后台批量工作（v1.8.9.2）。
     *
     * 为 true 时 Venera 引擎把它放进后台通道、并压短单次调用超时。动机：
     * 标签搜索一次要打几十个源，而 `search` 原本被划进交互通道 —— 交互许可
     * 全被召回占满后，用户点开详情页触发的 `info` 既抢不到许可，又要排在
     * 一堆长尾搜索后面，实测等到 30008ms 直接超时（表现为「点结果一直转圈」）。
     * 召回是后台行为，不该跟「用户正在等的那一次点击」抢通道。
     */
    val background: Boolean = false,
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<RequestBudget>
}
