# 待完成清单（Roadmap）

> 基线版本：**v1.8.2.0**（commit `d76a7e2`）
> 记录时间：2026-10-19
> 说明：本文件只记录待开工的需求，做完后把对应条目的 `- [ ]` 改成 `- [x]` 并在该行末尾补上落地版本号，随后从清单里移除或折叠进 CHANGELOG。

图例：`- [ ]` 待做 / `- [x]` 已完成（末尾标注版本）

---

## 一、阅读与书架（小说 / 漫画）

- [x] **1. 漫画增加「未加入书架」提示弹窗**（v1.8.3.0）
  - 小说已有实现，位置：`ui/screens/novel/NovelReaderScreen.kt`（`showShelfPrompt`，打开阅读后询问一次）。
  - 漫画按同样逻辑在 `ui/screens/comic/ComicReaderScreen.kt` 补一份：打开漫画 → 若未收藏 → 弹窗询问「加入书架 / 暂不」。
  - 必须等收藏状态加载完成再判断（参考小说的 `shelfStateReady` 标记），否则初始 `false` 会误弹。

- [x] **2. 设置里给「未加入书架提示」加开关，小说与漫画分开**（v1.8.3.0）
  - `core/preferences/PreferencesManager.kt` 新增两个独立 key（如 `KEY_NOVEL_SHELF_PROMPT` / `KEY_COMIC_SHELF_PROMPT`），默认开启。
  - 设置页拆成两行分别控制，互不影响；关闭后对应模块不再弹窗。

- [x] **3. 书架旁边的加号图标改为中文「加载本地资源」**（v1.8.3.0）
  - 小说、漫画书架上的 `+` 图标容易被理解成「添加源 / 新建分组」。
  - 换成文字按钮「加载本地资源」，功能不变。位置：`ui/screens/bookshelf/BookShelfScreen.kt`。

- [x] **4. 恢复小说 / 漫画书籍的「长按删除」**（v1.8.3.0）
  - 长按菜单被后加的项顶掉了，删除入口丢失。
  - 恢复方式：把「删除」放回长按菜单首位，并加二次确认弹窗（删除书籍 = 同时清掉章节缓存与阅读进度）。

- [x] **5. 小说 / 漫画书架新增批量管理**（v1.8.3.0）
  - 进入多选模式：全选 / 反选 / 按分组选。
  - 批量操作至少包含：批量删除、批量移动到分组、批量清除已缓存章节。
  - 底部浮条显示「已选 N 本」，计数与操作都要走批量 DAO（不要循环单条）。

- [x] **6. 小说与漫画的分组完全分开**（v1.8.3.0）
  - 当前 `books` 表共用，`BookDao` 里的分组查询没有按 `type` 过滤，导致小说建的分组在漫画里也可见。
  - 改法：`observeGroupNames` / `setGroup` / `renameGroup` / `clearGroup` 全部带 `type` 参数（或改成分组独立表 + `type` 列）。
  - 涉及：`data/database/BookDao.kt`、`ui/screens/bookshelf/BookShelfViewModel.kt` 与 `BookShelfScreen.kt` 的分组 UI。

---

## 二、预加载（设置可调）

- [x] **7. 小说预加载幅度可在设置里调**（v1.8.3.0）
  - 当前小说阅读会向后预加载章节，幅度写死。
  - 设置项：「小说预加载章节数」→ 关 / 1 / 3 / 5 / 10 章，存进 `PreferencesManager`。
  - 涉及：`ui/screens/novel/NovelReaderViewModel.kt` 的预加载逻辑。

- [ ] **8. 视频预加载秒数可在设置里调（边看边缓冲）**
  - 设置项：「视频预缓存时长」→ 关 / 15s / 30s / 60s / 120s，控制当前播放视频向后缓冲的秒数。
  - 涉及：`data/cache/VideoPrefetchCache.kt`、`ui/screens/video/VideoPlayerViewModel.kt`。

---

## 三、视频

- [x] **9. 长按快退加开关（关闭后左右长按都是快进）**（v1.8.3.0）
  - 现状：**长按**右半屏 = 3x 快进，**长按**左半屏 = 连续快退（`REWIND_STEP_MS = 1000ms` / 每 250ms 一跳）。
  - 需求是「长按快退」这一项加开关：关闭后长按左半屏也变成快进，即左右两侧长按行为一致，都是倍速快进。
  - 与双击 ±10 秒、横滑调进度无关，那些保持原样，不要一起改。
  - 实现位置：
    - 手势分发：`ui/screens/video/VideoPlayerScreen.kt:428-434` 的 `onLongPress`，当前是 `beginSpeedBoost(if (forward) 1 else -1)`；开关关闭时始终传 `1`。
    - 兜底也可放在 `ui/screens/video/VideoPlayerViewModel.kt:944` 的 `beginSpeedBoost(direction)`：`direction < 0` 时先读开关，关闭则直接走快进分支（这样即使别处传入 -1 也不会倒退）。
    - 提示文案：`VideoPlayerScreen.kt:646` 的「快进中 / 快退中」需跟着方向走。
  - 设置项放哪：与已有的「长按快进倍速」（`PreferencesManager.KEY_VIDEO_LONG_PRESS_SPEED`，设置页 `SettingsScreen.kt:295`）放同一段，命名如「长按左侧快退」，默认开启。

- [ ] **10. CC 字幕之外增加 AI 字幕**
  - 现有外挂字幕能力在 `ui/screens/video/VideoPlayerScreen.kt` / `VideoControls.kt` / `VideoPlayerViewModel.kt`。
  - 新增 AI 字幕：播放时自动生成/挂载字幕轨道，需提供 开关 + 语言选择 + 与 CC 字幕的优先级（两者都有时以谁为准）。
  - 需先确定实现方式（本地 ASR 还是云端接口），再决定要不要加网络权限提示与联网开关。

- [ ] **11. 视频标签整合（消除「输 A 搜 B」）**
  - 现象：视频里同义标签写法不统一，用某个标签搜索会搜出另一批内容。
  - 按漫画的做法做标签归一化：别名归并 + 统一键。
  - 涉及：`core/util/TagAlias.kt`、`source/search/VideoTitleKey.kt`、视频打标链路。

- [ ] **12. 视频标签识别要更多**
  - 扩充视频的打标词表与规则，覆盖更多维度（类型、地区、年代、演员/主创、系列等），提高标签命中率与覆盖率。
  - 与第 11 条一起做：先扩词表，再归一化。

- [ ] **13. 视频清晰度选项（设置里可调 + 多线路择优）**
  - 现状（v1.3.9.0）：清晰度切换只认 HLS 多码率源；`collectQualityTracks()` 在 `formats.size <= 1` 时直接返回空列表，播放页就不显示清晰度入口。而大多数采集站只给单码率 1080p，等于这个入口平时用不上。
  - 要加的是**在单码率源上也能起作用的清晰度选项**：
    - 设置项「默认清晰度」：自动 / 省流(480P) / 标准(720P) / 高清(1080P) / 超清(4K)，播放时按偏好锁定轨道；源给不出对应档位时降到最接近的一档，不要直接失效。
    - 记忆选择：至少全局记忆，最好按源分别记忆。
    - **多线路择优**：「选集 / 换源」的多条线路名字里常带 `高清` / `4K` / `BD` / `1080P` 等标识，按清晰度偏好把匹配的线路排到前面，并在线路上显示清晰度角标。
    - 可选项：移动网络下限制最高清晰度（省流，与第 8 条视频预缓存时长同段配置）。
  - 实现位置：`ui/screens/video/VideoPlayerViewModel.kt` 的 `collectQualityTracks()`（379 行起）、`qualityLabel()`（423 行）、`selectQuality()`（435 行起）；多线路排序在选集/换源逻辑里；设置项照例进 `PreferencesManager`（参照 `KEY_VIDEO_LONG_PRESS_SPEED`）。

---

## 四、搜索

- [ ] **14. 搜索结果长按加入书架（可在设置里开关）**
  - 小说 / 漫画 / 视频三个搜索页共用 `ui/components/GroupedSearchResults.kt`，在此统一加长按手势。
  - 长按 → 菜单「加入书架」；行为与点击默认进阅读页区分开。
  - 设置项开关，默认开启。

- [ ] **15. 搜索结果在「阅读」按钮旁新增收藏按钮**
  - 每条结果行内在「阅读」旁加一个收藏（心形/星形）图标，已收藏时高亮。
  - 同样落在 `GroupedSearchResults.kt`，一处改动三页生效。

---

## 五、RSS

- [ ] **16. RSS 源批量管理**
  - **分组**：`RssFeedEntity` 加 `groupName` 字段（含 Room 迁移），列表支持按分组筛选 / 折叠。
  - **批量删除**：多选模式 + 全选，批量删除源（连同 `RssArticleEntity` 的文章一起清，避免留孤儿数据）。
  - 涉及：`data/database/RssDao.kt`、`ui/screens/rss/RssScreen.kt`、`RssReaderScreen.kt`。

---

## 六、云端标签导入

- [ ] **17. 云端导入增加进度条**
  - 分三段上报进度：下载（按字节数算百分比）→ 解析 → 批量入库（按分块数算百分比，当前 `IMPORT_CHUNK = 2000`）。
  - 弹窗内显示 `xx%` + 阶段文案，并支持取消（取消时要保证已写入的数据可回滚或至少不残留半截）。
  - 涉及：`ui/screens/settings/SettingsViewModel.kt`（`importCloudLatest` / `importPayload`）、`data/repository/TagIndexRepository.kt`（`importTagTable`）、`ui/screens/settings/SettingsScreen.kt` 弹窗。

---

## 七、更新 / 导入 / 导出性能

- [ ] **18. 检查更新下载时不能被打断**
  - 现象：检查更新 / 下载安装包过程中，用户的返回、切页、锁屏等操作会中断下载或导致状态错乱。
  - 需求：下载阶段禁止中断——要么锁定 UI 不让退出/切走，要么做成可续传（断点重新拉取），中断回来能接着下而不是从头再来。
  - 涉及：更新下载相关逻辑（更新检查 / 下载入口），需先确认当前下载是否走后台线程、有哪些取消点。

- [ ] **19. 检查更新下载不能退到后台（保持前台）**
  - 现象：下载时切到后台 / 锁屏，进程被系统回收，下载停滞或失败。
  - 需求：下载中保持前台（前台 Service + 常驻通知），至少保证后台不被杀、进度可恢复；通知栏显示下载进度与完成提示。
  - 涉及：同上更新下载模块；Android 8+ 必须配套通知渠道与前台 Service。

- [ ] **20. 标签导出太慢**
  - 现象：导出标签表（云端标签 / 本地标签）耗时长，UI 卡住且没有任何进度反馈。
  - 需求：优化导出性能——改流式写出（边查边写，不一次性把全量攒进内存）、分块处理、后台线程 + 进度条；大表支持增量导出或分卷。
  - 涉及：标签导出相关逻辑（TagIndexRepository / 设置页导出入口），与第 17 条「云端导入进度条」对称。

- [ ] **21. 源导入时文件太大会卡死**
  - 现象：导入书源 / 备份文件（尤其是体积大的 JSON）时主线程解析，UI 无响应直至 ANR 卡死。
  - 需求：大文件导入改后台线程 + 流式 / 分块解析（不要一次性 read 整个文件进内存再 JSON.parse）；解析过程显示进度且可取消；文件超阈值时给出提示或限制。
  - 涉及：源导入 / 备份恢复相关逻辑（设置页导入入口、源解析 util），解析应移到 `Dispatchers.IO` 并加进度回调。

---

## 备注

- 改动涉及 `books` 表结构或 `RssFeedEntity` 时，必须写 Room 迁移（`AppDatabase.kt`）并自增数据库版本。
- 新增设置项统一走 `core/preferences/PreferencesManager.kt`，不要各自散落存 SharedPreferences。
- 涉及 jsDelivr 的改动注意 CDN 缓存：新文件路径变化才不会被 7 天缓存命中，必要时用 `leimao-tagger` 里的 `publish_tags.py --purge`。
