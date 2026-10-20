# 待完成清单（Roadmap）

> 基线版本：**v1.8.3.0**
> 记录时间：2026-10-19（最后更新 2026-10-20）
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

- [x] **8. 视频预加载秒数可在设置里调（边看边缓冲）** ✅ v1.8.3.0
  - 设置项「预缓存时长」：关 / 15s / 30s / 60s / 120s，控制 ExoPlayer 向后缓冲多久。
  - 实现：新增 `core/media/VideoBufferControl.kt` —— `VideoBufferOptions` 负责档位换算
    （关档仍保留 15s 最小缓冲，否则采集站慢分片会「每几秒卡一次」），
    `VideoBufferControl` 是 `LoadControl` 的转发包装，`update()` 换掉内部 `DefaultLoadControl`
    即生效（ExoPlayer 创建后没有 `setLoadControl`，不这么写就只能等下次进播放页）。
    所有委托共用一个 `DefaultAllocator`，换档时体积阈值判断不失真。
  - 播放页 `remember` 时按 `prefs.bufferSeconds` 建，偏好从 DataStore 读回后再 `update` 一次。
  - 单测 `VideoBufferOptionsTest` 覆盖四个毫秒值的 `DefaultLoadControl` 约束（给错会崩在播放页）。
  - 涉及：`core/media/VideoBufferControl.kt`（新建）、`PreferencesManager.kt`、
    `VideoPlayerViewModel.kt`、`VideoPlayerScreen.kt`、`SettingsViewModel.kt`、`SettingsScreen.kt`。

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

- [x] **13. 视频清晰度选项（设置里可调 + 多线路择优）** ✅ v1.8.3.0
  - 设置项「默认清晰度」：自动 / 480P / 720P / 1080P / 4K（`KEY_VIDEO_QUALITY`，默认自动）。
  - **多码率源锁轨**：`collectQualityTracks()` 里按偏好取最接近的一档（等距取更高一档）；
    每集只自动锁一次（`qualityAppliedForMedia`，换集 / 换源复位），手动改过的不再被覆盖。
  - **记忆**：播放器里手动选清晰度写回同一设置当全局记忆（`setVideoQuality(height)`），
    「自动」则清零 —— 下次播放直接按这档起播。按源分别记忆暂未做（需要额外的按 key 表，
    和 `VideoLineMemory` 同构，等有人真觉得全局不够用再说）。
  - **多线路择优**：新增 `core/util/VideoQualityTag.kt` —— 从线路名认 `4K` / `2160P` / `UHD` /
    `1080P` / `FHD` / `BD` / `蓝光` / `超清` / `720P` / `高清` / `480P` / `标清` / `流畅`
    （高档优先匹配，拉丁词带词边界，避免 `bd` 撞 `abd`）；`sortIndices()` 只给**下标顺序**，
    不改原列表（线路下标牵着换源与记忆）。`loadLines()` 里按偏好稳定排序，
    换源列表用 `VideoQualityTag.labelOf()` 画角标。
  - 单测 `VideoQualityTagTest`：高档优先、词边界、自动档不排序、同档保序、等距取更高。
  - 涉及：`core/util/VideoQualityTag.kt`（新建）、`PreferencesManager.kt`、
    `VideoPlayerViewModel.kt`、`VideoControls.kt`、`SettingsViewModel.kt`、`SettingsScreen.kt`。

---

## 四、搜索

- [x] **14. 搜索结果长按加入书架（可在设置里开关）** ✅ v1.8.3.0
  - 小说 / 漫画 / 视频三个搜索页共用 `ui/components/GroupedSearchResults.kt`，在此统一加长按手势。
  - 长按 → 菜单「加入书架」；行为与点击默认进阅读页区分开。
  - 设置项开关，默认开启。
  - 实现：`MediaItemCard` / `GroupCard` 改用 `combinedClickable`（长按带震动反馈），长按弹 `SearchResultActionSheet`（标题 + 加入书架/移出书架 + 阅读/播放 + 查看详情 + 展开 N 个源换源），菜单只列当前页实际支持的动作；视频文案走「加入收藏」。开关 `PreferencesManager.KEY_SEARCH_LONG_PRESS_SHELF`（`search_long_press_shelf`，默认 true），三个 VM 各自暴露 `longPressShelfEnabled`，设置页「搜索」区块可关。

- [x] **15. 搜索结果在「阅读」按钮旁新增收藏按钮** ✅ v1.8.3.0
  - 每条结果行内在「阅读」旁加一个收藏（心形/星形）图标，已收藏时高亮。
  - 同样落在 `GroupedSearchResults.kt`，一处改动三页生效。
  - 实现：漫画 / 视频搜索 VM 补 `bookRepository` + `shelfKeys` + `toggleShelf`（`AppNavHost` 注入 `it.bookRepository`）；收藏图标除展开行外，**折叠的聚合卡片右侧也补了一个**（结果默认都是折叠态，只放展开行等于默认看不见）。

---

## 五、RSS

- [x] **16. RSS 源批量管理** ✅ v1.8.3.0
  - **分组**：`RssFeedEntity.groupName` 字段历史上已有（v1.3.1.0 建表时预留），本轮补齐「能用的 UI + 批量改分组」：
    顶部新增分组筛选行（全部分组 / 未分组 / 各分组名），长按分组 chip 可重命名（整组改名）；
    单源长按管理弹窗补「分组」输入框，并给出已有分组的一键 chip，省得手打变成新分组。
  - **批量删除**：顶栏「管理订阅」进入管理态 —— 源列表按分组分节、分节标题可折叠，
    每行复选框，顶栏全选 / 反选，底部浮条「分组 + 删除」。
    删除前查一次文章数，弹「删除 N 个订阅？会同时删除 M 篇文章」二次确认。
  - 新增 DAO：`updateGroupBatch` / `getGroupNames` / `deleteFeeds` / `deleteArticlesOfFeeds` / `countArticlesOfFeeds`；
    仓库层 `deleteFeeds` 先删文章再删源（`rss_articles` 无外键，顺序反了就留孤儿），500 条分块。
  - 单源删除也统一走二次确认（原来直接删，误触就丢整源文章）。
  - 涉及：`RssDao.kt`、`RssRepository.kt`、`RssRepositoryImpl.kt`、`RssScreen.kt`。

---

## 六、云端标签导入

- [x] **17. 云端导入增加进度条** ✅ v1.8.3.0
  - 三段加权进度：下载 55% → 解析 5% → 写库 40%，弹窗显示「阶段名 / 百分比 / 明细 / 不定态进度条」。
    明细文案：下载阶段 `3.2 MB / 12.4 MB`，写库阶段 `小说 16691 / 16691`。
  - **服务器不给 Content-Length 时**（jsDelivr 走分块传输，实测就是 -1）百分比无意义，
    改为显示 `--` + 无参 `LinearProgressIndicator`（流动条纹）+「已下载 X MB」，否则会出现
    「下载了 7.4 MB 却显示 0%」的假进度。
  - **取消**：下载/解析阶段可取消，写库阶段按钮禁用并提示「正在写入本地标签库，请勿退出应用」。
    下载是阻塞流式读，协程 `cancel()` 打不断 `read()`，所以在进度回调里查 `@Volatile` 取消标志抛 `CancellationException`；
    写库只在「类型边界」检查取消，保证每个类型要么整批写完、要么完全没写（不会出现 local_tags 写了、tag_instances 没写）。
  - 新增 `HttpClient.getTextWithProgress`（临时文件 + `downloadFile` 流式读取 → UTF-8 解码 → 删临时文件），
    `downloadFile` 补 `timeoutMs`（大文件 20s 读超时不够用；注意该参数必须放在最后一个 lambda 参数之前，否则尾随 lambda 会绑到它）。
  - 统一入口 `startTagImport(TagImportSource.Cloud/Url/Local)`，云端 / URL / 本地三个导入共用同一套进度与取消。
  - 涉及：`SettingsViewModel.kt`、`SettingsScreen.kt`、`TagIndexRepository.kt`、`HttpClient.kt`。

---

## 七、更新 / 导入 / 导出性能

- [x] **18. 检查更新下载时不能被打断** ✅ v1.8.3.0
  - 根因：下载协程挂在 `SettingsViewModel.viewModelScope` 上，**退出设置页（ViewModel 被清）= 下载被 cancel**；
    弹框「取消」也直接丢掉进度，重新点又从头拉十几 MB。
  - 实现：下载搬进进程级单例 `UpdateDownloadManager`（`SupervisorJob + Dispatchers.IO` 的独立作用域），
    与页面 / ViewModel / Service 生命周期全部解耦；进度是一份 `StateFlow`，进出设置页都在。
  - 弹框改成随时可关：下载中按钮文案是「后台下载」，关掉只是收起弹框，下载继续；
    设置页「检查更新」下方常驻一行「正在后台下载 vX… 42%」+ 进度条（重进页面时可见）。
  - **断点续传**：`HttpClient.downloadFile` 新增 `resumeFrom`，按 `.part` 已有长度发 `Range: bytes=N-`；
    服务端返回 206 就追加写，返回 200（不认 Range）自动截断重写，不会拼出坏包。
    中途失败退避 2s / 5s / 10s 重试 3 次，每次都从已下字节接着来；最后再用清单里的 `apkSize` 校验长度。
  - 安装包位置从 `cacheDir/update` 挪到 `filesDir/update`（cache 在磁盘吃紧时会被系统清掉），
    `file_paths.xml` 补 `<files-path>` 供 FileProvider 授权。
  - 涉及：`core/update/UpdateDownloadManager.kt`（新建）、`HttpClient.kt`、`UpdateChecker.kt`、
    `SettingsViewModel.kt`、`SettingsScreen.kt`、`res/xml/file_paths.xml`。

- [x] **19. 检查更新下载不能退到后台（保持前台）** ✅ v1.8.3.0
  - 实现：新增 `UpdateDownloadService`（前台 Service，`foregroundServiceType="dataSync"`，
    `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` 三个权限），
    通知渠道 `update_download`（IMPORTANCE_LOW，不响铃不振动）。
  - 通知内容：下载中显示「正在下载 vX · 42%（3.2 / 7.8 MB）」，续传时补「第 N 次续传」；
    完成变成「更新包下载完成 · 点击安装」（点通知直接拉起系统安装器）；失败变「更新下载失败 · 原因」。
    百分比没变就不重发通知（`setOnlyAlertOnce` + 本地去重），滑动通知栏不掉帧。
  - 进程被杀也不会「下载假死」：Service 只负责保活与展示，被系统掐掉后重新 start 一下
    就会接着订阅 Manager 的进度（`.part` 还在，续传继续）。
  - 下载完成时：应用在前台 → 直接拉起安装器并清掉通知；在后台 → 只留通知（后台起 Activity 会被系统拦）。
  - Android 13+ 在点「下载更新」前申请一次通知权限（不给也能下，只是看不到进度）。
  - 涉及：`core/update/UpdateDownloadService.kt`（新建）、`AndroidManifest.xml`、`SettingsScreen.kt`。

- [x] **20. 标签导出太慢** ✅ v1.8.3.0
  - 根因：老实现是「三类型 `buildTagTable` 全量读进内存 → 拼 Payload → Gson `setPrettyPrinting().toJson`
    成十几 MB 的 String → 再 `toByteArray()`」，同一份数据内存里存在 3~4 份；而且每条作品都要单独
    查一次 `tagsOf`（两万条 = 两万次查询），缩进格式还把文件撑大一倍。
  - 实现：新增 `TagIndexRepository.exportTagTable(types, out, onProgress)` ——
    直接用 `JsonWriter` 往调用方给的 `Writer` 里写；按 `EXPORT_PAGE = 500` 分页查 `local_tags`
    （新 DAO `pageOfType`，`ORDER BY sourceId, itemId` 保证分页不漏行），每个类型的 `tag_instances`
    只读一次建 `Map<作品, 标签>`，不再逐条查库；内存里最多只有一个分页。
    输出紧凑 JSON（消费方是程序，缩进只增加体积和 I/O）。
  - 设置页「标签导出」与标签搜索页「导出收录清单」都改走这条流式路径，进度条按已写条目数推进；
    写完用 `CountingOutputStream` 顺手统计字节数，提示「导出完成：XXXX KB」。
  - 涉及：`LocalTagDao.kt`、`TagIndexRepository.kt`、`SettingsViewModel.kt`、`TagSearchViewModel.kt`、
    `TagSearchScreen.kt`。

- [x] **21. 源导入时文件太大会卡死** ✅ v1.8.3.0
  - 三处卡点全修：
    1. **文件在主线程读**：源导入页原来在文件选择回调里直接 `readText()`，几十 MB 必 ANR。
       改为 `SourceManageViewModel.loadSourceFile()`：IO 线程按 64KB 分块读、回报「已读取 X MB」。
    2. **巨型文本塞进输入框**：几 MB 内容进 `OutlinedTextField` 会让每次重组都去测量整段文字。
       现在超过 20 万字符就**不回填输入框**，页面只显示文件卡片（名字 / 体积 / 字符数 /「过大，未预览」），
       点「导入」直接用 ViewModel 里的正文；粘贴判断也从 `lines()` 全量切分改成 `lineSequence()` 懒取首行。
    3. **解析没有进度**：`legado` 列表解析新增逐条回调（`parseListWithRawDetailed(raw, onProgress)`），
       沿 `SourceRepository.previewImport` → `SourceManager.previewImport` → `importLegado` 一路传下来，
       弹窗显示「解析源 · 1200 / 3000 个条目」；解析在 IO 线程，进度用百分比进度条。
  - 可取消：读取与解析阶段弹窗有「取消」，通过 `@Volatile importCancelled` 在回调里抛
    `CancellationException` 打断（协程 cancel 打不断 CPU 密集的解析循环）。
  - 新增单测 `SourceParseProgressTest`：逐条进度、单源报一次、回调抛异常可中断。
  - 涉及：`SourceManageViewModel.kt`、`SourceImportScreen.kt`、`SourceManager.kt`、`LegadoBookSource.kt`、
    `Repository.kt`、`SourceRepositoryImpl.kt`。

---

## 备注

- 改动涉及 `books` 表结构或 `RssFeedEntity` 时，必须写 Room 迁移（`AppDatabase.kt`）并自增数据库版本。
- 新增设置项统一走 `core/preferences/PreferencesManager.kt`，不要各自散落存 SharedPreferences。
- 涉及 jsDelivr 的改动注意 CDN 缓存：新文件路径变化才不会被 7 天缓存命中，必要时用 `leimao-tagger` 里的 `publish_tags.py --purge`。
