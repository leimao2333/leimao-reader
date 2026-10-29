package com.novelreader.source.venera

import android.util.Log

/**
 * 已知失效源配置 / 源实现的本地修复表（v1.7.5.0 起，v1.9.2.3 增加源码级补丁）。
 *
 * 背景：Venera 源的「默认配置」与「解析逻辑」都写在源 JS 里，上游
 * （venera-app/venera-configs）更新不及时时，源站一改版就整块坏掉。这里提供两条
 * 互不干扰的修复通道：
 *
 * 1. **[FIXES]（设置项）**：经 [VeneraEngine.ensureSetting] **只在用户没有显式设置过时**
 *    写入引擎的存储（`venera:setting:<key>:<name>`）。因此不会覆盖用户自己的选择，
 *    源后续更新 / 重新导入后依然有效（设置按 key 存，不随源码重置）。
 * 2. **[CODE_FIXES]（源码片段替换）**：在 [VeneraEngine.putSource] 入库时对源码做
 *    字面量替换。用于「上游逻辑本身已失效」的情况 —— 这类问题改设置项救不了。
 *
 * 源码补丁的取舍：它依赖上游原文片段，**上游一改版就可能匹配不上**。所以：
 * - 匹配不上时**只记日志、原样返回**，绝不抛错（宁可退回上游行为，也不能让源加载不了）；
 * - 每个补丁都写明「为什么」与「什么时候能删」，上游修好后按名字删条目即可。
 */
object SourceRepairs {

    private const val LOG_TAG = "SourceRepairs"

    /**
     * 一条源码补丁。
     *
     * @param name 补丁名（日志 / 排查用，也是「上游修好后该删哪条」的标识）
     * @param from 上游原文片段（字面量匹配，含缩进，尽量取短且唯一的一行）
     * @param to 替换后的片段
     */
    data class CodeFix(val name: String, val from: String, val to: String)

    /** 源 key →（设置名 → 修复值） */
    private val FIXES: Map<String, Map<String, String>> = mapOf(
        // 包子漫画：默认域名 bzmgcn.com 已 403（2026-10-16 实测，服务器明确拒绝，
        // 非网络不通）；webmota.com 的首页 / 分类 API / 搜索均 200 且正常出数
        // （Node 复现台验证 categoryComics 36 条）。
        "baozi" to mapOf("domains" to "webmota.com"),
    )

    /** 源 key → 源码补丁列表 */
    private val CODE_FIXES: Map<String, List<CodeFix>> = mapOf(
        // jcomic.net 1.0.0：章节页把真实图片地址放进 data-locked，src 留空。
        // 上游 loadEp 只读 img.attributes["src"]，于是 50 张图全是空串、阅读页一片白。
        // 站点自己的解码逻辑就在页面内联脚本里（2026-10-29 抓取 /page/<id>/<ep>）：
        //   let cleanStr = lockedStr.replace("JCOMIC_TRAPS_", "");
        //   let unreversed = cleanStr.split('').reverse().join('');
        //   img.src = atob(unreversed);
        // 这里照抄同一套，按「src 为空才解码」处理，src 正常时行为与上游完全一致。
        // 可删条件：上游 jcomic.js 自己处理 data-locked（版本号越过 1.0.0 时优先复查）。
        "jcomic" to listOf(
            CodeFix(
                name = "jcomic-data-locked",
                from = """const images = Array.from(imgs).map((img) => img.attributes["src"]);""",
                to = """const images = Array.from(imgs).map((img) => {
        var s = img.attributes["src"];
        var locked = img.attributes["data-locked"];
        if ((!s || !String(s).trim()) && locked) {
          try {
            s = atob(String(locked).replace("JCOMIC_TRAPS_", "").split("").reverse().join(""));
          } catch (e) {
            s = "";
          }
        }
        return s || "";
      });""",
            ),
        ),
    )

    /** 应用全部修复（幂等，可重复调用） */
    fun applyAll(engine: VeneraEngine) {
        FIXES.forEach { (key, settings) ->
            settings.forEach { (name, value) -> engine.ensureSetting(key, name, value) }
        }
    }

    /**
     * 对源码应用本地补丁（幂等）。返回打过补丁的源码；没有补丁或都匹配不上时原样返回。
     *
     * 只做**字面量替换**，不做语法分析 —— 补丁本身就是「把上游那一小段换成正确实现」，
     * 正则化只会让它更难维护。
     */
    fun patchCode(key: String, code: String): String {
        val fixes = CODE_FIXES[key] ?: return code
        var out = code
        for (fix in fixes) {
            if (out.contains(fix.to)) continue // 已经打过了（同一份源码被反复 putSource）
            if (!out.contains(fix.from)) {
                // 上游改版导致片段对不上：只记日志，保持上游行为
                Log.w(LOG_TAG, "源码补丁未命中（上游可能已改版）：$key/${fix.name}")
                continue
            }
            out = out.replace(fix.from, fix.to)
            Log.i(LOG_TAG, "已应用源码补丁：$key/${fix.name}")
        }
        return out
    }
}
