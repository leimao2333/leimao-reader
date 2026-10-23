# 待完成清单（Roadmap）

> 基线版本：**v1.8.7.8**
> 记录时间：2026-10-19（最后更新 2026-10-23）
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

- [x] **31. 设置里新增「小说翻页方式」** ✅ v1.8.7.9
  - 现状：小说翻页方式**只能**在阅读页菜单里改 —— `ui/screens/novel/NovelReaderMenu.kt:693-705` 六个 chip 调 `viewModel.setPageType(...)`。设置页只有「漫画阅读 → 翻页方式」（`SettingsScreen.kt:297`，仅「上下滚动 / 左右翻页」两项），小说这边没有入口。
  - 需求：设置页「小说阅读」区块补一项，六档与阅读菜单完全一致：覆盖 / 仿真 / 滑动 / 滚动 / 上下 / 无动画（常量 `PreferencesManager.PAGE_TYPE_COVER` ~ `PAGE_TYPE_NO_ANIM`，默认 `DEFAULT_PAGE_TYPE` = 滑动）。
  - **单一数据源**：读写都走 `PreferencesManager.novelPageTypeFlow` / `setNovelPageType()`（`PreferencesManager.kt:571` / `585`），设置页与阅读菜单双向实时同步，**不要**在设置页另存一份偏好。
  - 注意：`PAGE_TYPE_SCROLL`（滚动）与 `PAGE_TYPE_VERTICAL`（上下）走的是不同渲染分支（`NovelReaderScreen.kt:351-352` 的 `isScroll` / `isVertical`），改完设置回到阅读页要按新值重建，别只在菜单里生效。
  - 涉及：`ui/screens/settings/SettingsScreen.kt`、`SettingsViewModel.kt`（新增 `novelPageType` StateFlow + setter）、`core/preferences/PreferencesManager.kt`、`ui/screens/novel/NovelReaderViewModel.kt`。

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

- [x] **32. 视频预缓存时长增加更多、更长的档位** ✅ v1.8.7.9
  - 现状：档位写死在 `core/media/VideoBufferControl.kt` 的 `VideoBufferOptions.CHOICES = listOf(0, 15, 30, 60, 120)`，最长 120s；设置页 `SettingsScreen.kt:585` 直接遍历它渲染，加档位会自动出现。
  - 需求：增加更长选项（建议 180 / 300 / 600，具体档位待定），并保证「更长」真的落到 ExoPlayer 上。
  - **三处必须同时改，漏一处会静默失效**：
    ① `CHOICES`（渲染来源）；
    ② `durationsMs()` 的 `when` 分支 —— 现在是 `else -> 30_000 to 120_000`，120s 以上的档位会全部按 120s 处理，等于加了个假档位；
    ③ `normalize()` 用「最近档位」兜底，新档位要能被选中，别把存量值又吸回 120。
  - 单测 `VideoBufferOptionsTest` 补新档位的四个毫秒值（`DefaultLoadControl` 对 `bufferForPlaybackMs` / `minBufferMs` 有大小约束，给错会崩在播放页）。
  - 文案要写清代价：`maxBufferMs` 直接决定往后吃多少，600s 高清一路能吃掉几百 MB 内存与流量。
  - 涉及：`core/media/VideoBufferControl.kt`、`SettingsScreen.kt`、`core/media/NextPrefetchPolicy.kt`（`bufferSeconds` 参与预取判定，0 = 关闭）、`VideoPlayerViewModel.kt:361`。

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

- [x] **10. CC 字幕之外增加 AI 字幕** ✅ v1.8.7.3（HLS 线路与模型自选补齐于 v1.8.7.4）
  - 现有外挂字幕能力在 `ui/screens/video/VideoPlayerScreen.kt` / `VideoControls.kt` / `VideoPlayerViewModel.kt`。
  - **方案已定（2026-10-20）**：本地 ASR 模型，不走云端接口（免密钥、免计费、离线可用）。
  - **已完成（2026-10-21）**：
    - 设置项「字幕识别」开关：`PreferencesManager.KEY_SUBTITLE_ASR`，默认关闭。
    - 模型按需下载：`core/model/LocalModel.kt` + `LocalModelStore.kt`，hf-mirror 直链、
      逐文件精确字节数校验、`.part` 断点续传、非 Wi-Fi 流量确认、已下载可删除。
      模型为 `csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09`（78 MB int8）。
    - 取音：`core/asr/AudioPcmExtractor.kt`，MediaExtractor + MediaCodec 边解边吐，
      按 20 秒一块回调，内存不随片长增长；重采样在块级别做，避免逐 buffer 的相位跳变。
    - 识别：`core/asr/OfflineAsrEngine.kt`（sherpa-onnx Paraformer 非流式，native 句柄加锁串行）。
    - 切条：`core/asr/SubtitleBuilder.kt`，按标点切句 + 按字数比例分时间轴，末条对齐块尾。
    - 接回播放器：生成结果落 SRT（缓存目录），走**已有外挂字幕链路**
      （`VideoPlayerViewModel.loadSubtitle` → Media3 `SubtitleConfiguration`）加载，
      与手工加载的 srt 行为完全一致，也顺带让用户能把字幕存下来。
    - 入口可见性：设置开关 **且** 模型已下载才出现（`aiSubtitleAvailable`），条件不满足时菜单里没有这一项。
    - 生成中可取消，菜单项内嵌进度条与百分比。
  - **与 CC 字幕并存**：不并存 —— 生成后覆盖当前字幕轨。手工加载的外挂字幕仍可通过
    「加载字幕」覆盖 AI 字幕，反之亦然（同一时刻只有一条文本轨）。
  - **HLS 线路已支持（v1.8.7.4）**：采集站多数是 m3u8 分片清单，`MediaExtractor` 不认分片列表
    （`UnrecognizedInputFormatException`），此前只能提示「换 mp4 线路」—— 而站里大半内容没有
    mp4 直链，等于功能对多数视频不可用。现在的做法：直链能解就直接解；判定为 m3u8 就
    **挑 BANDWIDTH 最低的变体**下载分片拼成本地文件再解（字幕只要音轨，清晰度对识别没有意义，
    挑最低码率能把下载量压到最小）。分片支持 AES-128（IV 优先取 `#EXT-X-KEY:IV=0x…`，
    缺省按 `#EXT-MEDIA-SEQUENCE` 转 16 字节大端）；`SAMPLE-AES` 直接失败，解不对出来就是噪声。
    下载阶段占进度前 40%，之后才进识别。实现在 `core/media/HlsPlaylist.kt` + `HlsDownloader.kt`。
  - **模型可自选（v1.8.7.4）**：设置页每行都有「当前使用 / 更换模型」，可切内置模型、
    从手机文件导入（多选 onnx + tokens.txt + lexicon.txt，复制进私有目录 —— SAF 的临时授权
    重启就失效，只记 URI 的表现是「今天能用、明天加载失败」）、或填仓库地址从镜像拉。
    选择持久化到 `files/models/selection.json`，引擎侧改为按 `activeDir(kind)` 取目录、
    挑体积最大的 `.onnx` 当主模型（不再写死 `model.int8.onnx`），换目录时释放旧句柄重载。
  - **静音不再凭空编内容（v1.8.7.4）**：设备上抓到的真问题 —— 1 秒全零采样被识别成「我的知道」。
    非流式 ASR 对静音输入会输出高频幻觉词，现在按 RMS 门限短路（阈值取低，避免误杀小声对白）。
  - **未做**：字幕翻译（中→日 / 中→英），需再接一个翻译模型；本版不做。
  - **未验证**：从真实 HuggingFace 仓库完整下载一个模型（要几百兆流量），目前只验到
    「文件清单解析 + 非法地址快速失败」；导入与删除路径已在设备上跑通。

- [x] **11. 视频标签整合（消除「输 A 搜 B」）** ✅ v1.8.4.0
  - 根因：采集站的 `vod_type_name` 写「动作片 / 爱情片 / 科幻片」，受控词表的规范名是
    「动作 / 恋爱 / 科幻」，两边各自成键 —— 同一类内容被切成两批，搜哪个都只出一半。
  - 实现：`TagAlias` 新增 `genreBaseOf()`，在 `canonicalOf` / `aliasesOf` / `searchTermsFor`
    三处都先剥「片 / 剧 / 電影 / 电视剧」等影视后缀再查词表。
    **只改存储侧不够**：查询侧（`aliasesOf`）不剥，搜「动作片」依然匹配不到存成「动作」的条目，
    所以三处必须同一口径。基词不在词表里的词不受影响（「卡片」「照片」「唱片」原样保留）。
  - 新增 8 个等价组：剧情 / 犯罪 / 家庭 / 灾难 / 传记 / 纪录片 / 古装 + 地区互通组
    （香港 / 台湾 / 美国 / 日本 / 泰国 / 印度 / 英国 / 法国 / 日韩 / 港台）。
  - 已知取舍：「韩国」在漫画词表里属于「韩漫」，影视地区标签因此**不过归一**
    （`VideoTagLexicon` 里 `canonicalize = false`），否则一部韩剧会被标成「韩漫」；
    搜索侧仍通 —— `aliasesOf("韩国")` 里就含「韩国」。
  - 涉及：`core/util/TagAlias.kt`、`VideoTagLexicon.kt`（新建）、`TagTagger.kt`、`TagQuerySpec.kt`。

- [x] **12. 视频标签识别要更多** ✅ v1.8.4.0
  - 最大的一块不是「加词」而是**把已有字段用起来**：`vod_area` / `vod_lang` / `vod_year` /
    `vod_actor` / `vod_director` 一直在 `extras` 里（详情页元信息行也在展示），
    却从没进过标签索引 —— 「2025 年的港片」在标签搜索里根本无从下手。
  - 新增 `core/util/VideoTagLexicon.kt`：
    - 地区归一（内地 / 大陆 / 国产 → 国产；港片 → 香港；韩剧 → 韩国…）
    - 语言归一（普通话 → 国语；粵語 → 粤语…）
    - 年代（4 位年份，1900~2100）
    - 主创：主演最多 2 个 + 导演 1 个，`未知 / 佚名 / 暂无` 这类占位词丢弃
    - 标题题材窄口径补充（只认标题、只认歧义小的词，最多 3 个，置信度 60）
  - `TagTagger` 新增影视大类：`地区` / `语言` / `年代` / `主创`；`TagQuerySpec` 同步支持
    `地区:香港`、`年代:2025`、`主创:张三`（及 region / year / actor / lang 英文别名）。
  - **分类名拆标签**（同批补做）：采集站只给一个分类字符串，而它同时表达两件事 ——
    「港剧」= 电视剧 + 香港、「日本动漫」= 日本 + 动漫。只存整串时「动漫」这个维度
    整个丢失（搜「动漫」搜不到只标了复合名的源，搜复合名也命中不了按子维度检索的结果）。新增
    `VideoTagLexicon.splitCompoundTag()`，在 `ItemTags` 里对影视生效（浏览上下文与
    `type_name` 都走），和小说 / 漫画「一部作品多个标签」口径一致。
    附带收益：「电影片」统一成「电影」，「其他片」这类兜底分类只留一级类型。
    成人向栏目名同时进 `EXPLICIT_ADULT_CANONICALS`，隐藏成人内容时整类过滤得到。
  - 未做：**系列**维度 —— 采集站没有稳定的系列字段，靠标题里的「第 N 季」猜会误伤
    （`VideoTitleKey` 明确把这类标识当内容标识、不剥离），先不碰。

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

- [x] **28. 视频标签整合多个标签（对齐小说 / 漫画口径，消除同义词分裂）** ✅ v1.8.7.0
  - 现状：小说 / 漫画在 `core/util/ItemTags.kt` 的 `of()` 里走文本富化
    （`FanworkTags` + `ComicTagLexicon`，词表 = `ComicGenres`/`NovelGenres` +
    `TagAlias.GROUPS` 96 组同义译名），一部作品能拿到多个规范名标签；
    视频侧 `ItemTags.of()` **显式跳过**文本富化（v1.7.9.0 判断影视简介是剧情复述、噪声大），
    只靠 `VideoTagLexicon`（地区 / 语言 / 年代 / 主创 / 复合词拆分 / 标题窄口径补充）+
    `TagAlias.canonicalOf` —— 采集站 `vod_type_name` 里大量同义写法各写各的
    没有归并源，同一维度散成多个标签、单部作品的标签数量也明显少于小说 / 漫画。
  - 需求：把视频做成和小说 / 漫画一致的「多标签整合」：
    - 题材同义词归并：补一张影视题材同义词表（扩 `TagAlias.GROUPS` 或给
      `VideoTagLexicon` 加题材等价组），覆盖 `vod_type_name` 常见同义写法，
      统一归一到规范名；**查询侧 `aliasesOf` 必须同口径**（#11 教训：只改存储侧不改查询侧仍搜不到）。
    - 复合词拆分维持 `splitCompoundTag`（#12 已做，勿重复）；
      一部视频除 `type_name` 外评估是否从标题窄口径补题材（沿用现有
      `MAX_TITLE_GENRES` / 歧义小词口径）或纳入 `vod_class` 等额外字段。
  - 涉及：`core/util/ItemTags.kt`、`VideoTagLexicon.kt`、`TagAlias.kt`、`TagTagger.kt`、`TagQuerySpec.kt`。
  - 落地（v1.8.7.0）：① `TagAlias.GROUPS` 新增 / 扩充约 20 组影视题材同义词
    （综艺 / 真人秀 / 脱口秀 / 歌舞 / 青春 / 文艺 / 演唱会 / 选秀 等），
    查询侧 `aliasesOf` 天然同口径；② `VideoTagLexicon.splitCompoundTag` 扩成七级解析
    （一级类型 → 噪声过滤 → 登记别名 → 地区前缀 → 噪声后缀 → 全串覆盖分词 → 兜底归一），
    显式分隔符先切段且片号类标识不切，认不出的整串保留不硬拆；
    大洲分区名（如 `asia`）进 `REGION_PREFIXES`，归地区大类而非题材；
    ③ `ItemTags.kt` 增加 `EXTRAS_CLASS`，`MacCmsParser` 把 `vod_class` 写进 extras，
    第二分类名一并参与拆分；④ 新增 17 条 `VideoTagExpansionTest`（用例全部取真实
    `vod_type_name`），影视四类测试共 66 条、全量 925 条单测通过。
    已知遗留：VIDEO 的「作者」大类被片号 / 厂牌名污染、「作品」大类
    被标题污染，需单独提待办。

- [x] **33. 修：关闭「横屏上下滑切集」后，横屏竖滑调不了亮度 / 音量** ✅ v1.8.7.9
  - 现象：设置里关掉「横屏上下滑切集」，横屏全屏时竖滑既没切集、也没调亮度 / 音量，整条竖向手势像废了。
  - **根因已定位**：`ui/screens/video/VideoPlayerScreen.kt:489` 的判断是
    `if (currentIsLandscapeFullscreen && currentVerticalSwipeSwitch)`。开关一关就走 `else`（535-556 行），
    而那个 `else` 是 v1.6.0.0「竖屏也上下滑切集」的逻辑，**无条件把竖滑吃掉去切上下集，完全不处理亮度 / 音量**。
    所以手势没丢，是被另一套切集逻辑顶掉了。
  - 反证：设置项自己的副标题写着「关闭后恢复亮度 / 音量手势」（`SettingsScreen.kt:554`），实际代码没有这条路径。
  - 期望：横屏全屏 + 开关关闭时，竖滑按起始位置三分区 —— 左 1/3 亮度、右 1/3 音量，中间 1/3 不切集（保持不动作）。
  - 改法：把第 489 行的复合条件拆成两层 —— ① 是否横屏全屏（决定走三分区）；② 开关是否开启（只决定中间 1/3 是切集还是什么都不做）。分区内亮度 / 音量那段（492-512 行）与开关无关，应无条件生效。
  - **别顺手改竖屏**：竖屏 / 窗口态上下滑切集是 v1.6.0.0 的既定行为（竖屏短剧靠它），本次只动横屏全屏。
  - 涉及：`VideoPlayerScreen.kt`（`detectDragGestures` 的 `onDrag`）、`PreferencesManager.videoVerticalSwipeSwitchFlow`（855 行）、`SettingsScreen.kt` 该项文案。

- [x] **39. 修：预缓存调到较长档位后「经常性加载失败」** ✅ v1.8.8.2
  - 现象：把视频预缓存时长改到 10 分钟档后，播放频繁报「播放出错」并反复重试，同一集有时重进能放、有时一直不行；短档位（关闭 / 15s / 30s）几乎不出现。
  - **根因已定位（两次真机栈指向同一处）**：`VideoBufferControl.update()` 会在播放器**已经 prepared 之后**换上一个全新的 `DefaultLoadControl` —— 预缓存时长是异步从 DataStore 读回来的，`LaunchedEffect(prefs.bufferSeconds)` 必然晚于建播放器。而 `DefaultLoadControl` 是**有状态**的：没收到 `onPrepared` / `onTracksSelected` 之前内部 `trackGroups` 仍是 null，`shouldContinueLoading` 会撞 media3 的非空断言 → `NullPointerException` 抛到 `ExoPlayerImplInternal` → 用户侧只有 `ExoPlaybackException: Unexpected runtime error` + 反复重试。栈：`Assertions.checkNotNull` → `DefaultLoadControl.shouldContinueLoading:383` → `VideoBufferControl.shouldContinueLoading`（另一条栈落在 `onTracksSelected:346`，同一根因）。
    长档位更容易撞上：`minBufferMs` 抬到 90s 后加载器长时间停在「继续加载」分支，判定被调用次数多得多。
  - 期望：缓冲策略这一层**绝不能把异常抛回播放内核**；换委托时必须把播放器已经发生的生命周期事件补到新委托上。
  - 改法（三层）：① `onPrepared` / `onTracksSelected` 各存一个 `replay` lambda，`update()` 里先在新委托上重放（`onPrepared` 在前）再让 `delegate = fresh` 生效；② `shouldContinueLoading` / `shouldStartPlayback` 包 `runCatching`，失败时按本类自己的档位算兜底答案（缓冲量没到上限就继续加载 / 攒够起播量就开播），只记 W 日志；③ `onTracksSelected` 入参空值退化 `TrackGroupArray.EMPTY` 并吞异常。
  - 涉及：`core/media/VideoBufferControl.kt`。
  - 验证：`logcat` 里应只出现 `VideoBuffer … 兜底` / `… 忽略` 的 W 日志，不再有 `Playback error` / `Unexpected runtime error`；10min 档连续播放 / 切集不再报错。

- [x] **41. 修：顶栏「纯白」把状态栏整条盖住看不清（全局）** ✅ v1.8.8.2
  - 现象：视频播放页（窗口态）顶部一条纯白，状态栏的时钟 / 电量完全看不见；其他页面在不同主题组合下也有类似情况。
  - **根因已定位**：`MainActivity` 的 `enableEdgeToEdge()` 是按**系统**深色模式决定状态栏图标黑白的（`ui_night_mode=2` 时给白色图标），而本应用的主题是用户设置项（默认浅色）。「系统深色 + 应用浅色」→ 白色图标压在白色页面上 = 整条状态栏消失。播放页更明显：全屏时 `hide(systemBars)`、退出只 `show()` 不还原图标明暗，白色图标会被带回窗口态。
  - 判据改成**按背景色的感知明度**，而不是按系统设置或主题：`Color.luminance() > 0.5` → 深色图标。
  - 落地：新增 `ui/theme/SystemBars.kt`（`applySystemBarIcons` / `Color.prefersDarkSystemBarIcons` / `SystemBarIconsOn`），并在四处接入 ——
    ① `MainActivity`：`LaunchedEffect(darkTheme)` 里按应用主题设一次，覆盖 `enableEdgeToEdge()` 的默认；
    ② `VideoPlayerScreen`：全屏 → 白色图标，窗口态 → 按 `LocalAppDarkTheme`；`onDispose` 还原，避免带出到上一页；
    ③ `NovelReaderScreen`：跟随纸张色（白天模式也能选「藏蓝」这类深底配色，只按主题判断会错）；
    ④ `ComicReaderScreen`：整屏纯黑 → 白色图标。
  - `Theme.kt` 新增 `LocalAppDarkTheme`：主题是用户设置项，任何「判断当前是不是深色」的代码都该读它，不要读 `isSystemInDarkTheme()`。
  - 涉及：`ui/theme/SystemBars.kt`（新增）、`ui/theme/Theme.kt`、`MainActivity.kt`、`ui/screens/video/VideoPlayerScreen.kt`、`ui/screens/novel/NovelReaderScreen.kt`、`ui/screens/comic/ComicReaderScreen.kt`。

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

- [x] **26. 搜索结果展示方式：宫格 / 列表可切换（视频 / 漫画 / 小说 / 订阅）** ✅ v1.8.6.0
  - 设置里给四个模块（视频、漫画、小说、订阅）各加一个「搜索结果展示」选项：宫格模式 / 列表模式，
    偏好存 `PreferencesManager.kt`（每模块独立 key，如 `KEY_SEARCH_LAYOUT_NOVEL` 等），默认列表（保持现状）。
  - 搜索页加切换小图标：`NovelSearchScreen.kt` / `ComicSearchScreen.kt` / `VideoSearchScreen.kt`
    顶栏（返回按钮旁）放宫格/列表切换图标（如 `Icons.Filled.GridView` / `ViewList`），点击即时切换并写回设置。
  - 订阅（`RssScreen.kt`）的期刊检索结果面板同样支持两种展示，切换图标放期刊面板顶栏。
  - 列表模式沿用现有 `ui/components/GroupedSearchResults.kt` 的折叠聚合逻辑；
    宫格模式需实现对应的网格布局（注意聚合卡片「N 个源」展开/收起始在列表态，宫格态要有等价交互）。
  - 四个 VM 各自暴露 `searchLayout`（StateFlow），与设置页改动实时同步。
  - 实现（v1.8.6.0）：偏好 `KEY_SEARCH_LAYOUT_NOVEL/COMIC/VIDEO/RSS`（默认 false = 列表），
    `PreferencesManager` 暴露 `searchGrid*Flow` + `setSearchGrid*`；三个搜索页顶栏加宫格/列表切换图标，
    设置页「搜索」区加四个开关行（各模块独立记忆）。
    `GroupedSearchResults.kt` 新增宫格态：`GridResults`（两列 `LazyVerticalGrid`）+ `GridResultCell`
    （封面 0.72 比例、多源徽标、收藏角标）+ `GridSourceSheet`（宫格撑不开就地展开，多源组点击弹换源面板，
    语义与列表态展开一致）。订阅侧 `JournalSubscribeDialog` 加同一套切换（宫格卡片 `JournalGridCell`）。

- [x] **27. 标签搜索输入提示词（本地已有标签自动提示）** ✅ v1.8.6.0
  - 现状：`ui/screens/tag/TagSearchScreen.kt` 只有一个标签输入框（`query`，回车 `addTag`）+ 热门推荐 chip；输入时无本地标签提示，只能凭记忆盲打。
  - 需求：输入框聚焦并输入时，基于**本地已有标签**弹出提示词列表（前缀优先 / 包含匹配，同口径按覆盖源数排序）。
  - 点击提示词直接 `addTag`；多标签空格组合场景下只提示当前正在输的那一个词。
  - 提示数据全部本地：复用 VM 已有的 `allTags`（`TagIndexRepository` 标签池按 Type 区分），不引网络请求。
  - 小说 / 漫画 / 视频三个类型各自生效（页面按 `type` 打开天然隔离，无需额外偏好 key）。
  - 涉及：`TagSearchScreen.kt`、`TagSearchViewModel.kt`（新增 `suggestions` StateFlow）。
  - 实现（v1.8.6.0）：`core/util/TagSuggestions.kt` 纯函数（`currentWord` / `of` / `applyToQuery` / `matchScore`）
    + `TagSuggestionsTest` 15 个用例。`TagSearchViewModel` 在 `setQuery` 时用按覆盖源数倒序的标签池计算提示，
    `TagSearchScreen` 用 `DropdownMenu`（最高 264dp）浮层展示，点选走 `applyToQuery` 原地替换最后一段
    （`-` 排除与 `题材:` 前缀原样保留），不追加成新条件。
    排序：匹配类型（前缀 < 包含）→ 补全量更短 → 池内顺序（覆盖源数）。
    过滤：空格分段里已写进查询的条件整族屏蔽；正在输入的词本身是完整标签时也屏蔽同根词
    （避免打完「热血」还满屏「热血漫画」）。

- [x] **34. 漫画标签搜索加载巨慢（宽标签），改成真正的流式上屏** ✅ v1.8.8.0
  - 现象：漫画里搜 `WIDE_COMIC_SEARCH_TAGS` 那两个宽标签（`TagSearchViewModel.kt:1540`）要等几乎全部源返回才出东西，用户只能干等。
  - 现状链路：这两个标签命中后走 `searchCompound()`（1266 行起）—— 它必须先把两个标签**各自所有源、所有入口的并集**取完，再按标题求交，交集本质上是「等最慢的那个源」。`strictIntersection()` 虽然接了 `onPartial`，但求交前的并集没攒齐就没有可上屏的内容。
  - 拖慢的量化点：单请求硬超时 `SEARCH_REQUEST_TIMEOUT_MS = 8_000`（1573 行）、并发 `WIDE_COMPOUND_CONCURRENCY = 32`（1553 行）、每源入口上限 `MAX_URLS_PER_SOURCE = 2`（1585 行）。源一多，整轮就是「一批 8 秒」叠加。
  - 需求：像单标签搜索那样**一边出东西一边加载**。可选做法（按性价比排序）：
    ① 并集阶段就流式 —— 某个源返回就立刻把它与另一标签**当前已有集合**求交，有交集马上上屏，后续源回来再增量补（结果单调增加）；
    ② 首屏优先 —— 先只打「真分类入口」的源（`isSearchEntry` 为 false 的那批），拿到一定量先显示，纯关键词入口后补；
    ③ 首轮超时收紧（如首轮 4s 先上屏，慢源放后台补），别让首屏等满 8s。
  - 验收：首屏结果 2~3 秒内出现，最终条数不少于现在的全量结果。
  - 涉及：`TagSearchViewModel.kt`（`searchCompound` / `strictIntersection` / `searchOneTag` 的 `onPartial`）、`TagSearchScreen.kt` 的 loading 态（「正在搜索」不要盖住已经出来的结果）。

- [x] **35. 修：视频标签搜索翻到第二页就没了** ✅ v1.8.8.0
  - 现象：第一页有结果，触底加载第二页后 `hasMore` 直接变 false，再也翻不动。
  - 现状链路：`loadMore()`（`TagSearchViewModel.kt:654`）在「整页新增 0 条」时把 `_hasMore` 置 false（686-695 行）；`preloadNextPages()`（718 行）同样（742-748 行）。也就是**只要第二页返回的全是已有条目，就判定为到底**。
  - 为什么视频特别容易踩：视频源站分类页翻页常有两种毛病 —— ① 页码参数名不对（有的吃 `pg` / `pageNo`），传 2 等于又要了一次第 1 页；② 部分源忽略页码直接回全站列表，第二页与第一页高度重合。两种都会「整页 0 新增」→ 立即停。
  - 排查顺序：
    ① 打开 `TagSearch` 日志（`第 $page 页取回 N 条，新增 M 条`，696 行），先分清第二页是「取回 0 条」还是「取回 N 条但新增 0」；
    ② 若「取回 0 条」→ 源侧翻页参数没生效，查 `ContentRepositoryImpl.explore(sourceId, url, page)`（593 行）→ `SourceAdapter.explore(url, page)`（`domain/model/SourceAdapter.kt:104`）→ 各视频源实现；
    ③ 若「新增 0」→ 源在重复返回，别直接当成「到底」：同一源连续空页计数到 N 次才停，或者干脆允许继续硬翻（配合 v1.8.7.8 已加的「继续往下翻」）。
  - 期望：第二、第三页能正常出内容；确实到底时页脚仍按 v1.8.7.8 的方式说明停在第几页。
  - 附注：`SINGLE_TAG_PAGES` / `COMBINED_SEARCH_PAGES`（1559 / 1562 行）在改成按需翻页后已成**死代码**，顺手清掉或复用。
  - 涉及：`TagSearchViewModel.kt`、`ContentRepositoryImpl.explore()`、视频源 adapter 的 `explore` 实现。

- [x] **36. 修：视频标签搜索出一堆完全不相关的视频** ✅ v1.8.8.0
  - 现象：搜一个成人向题材标签（`TagAlias.isExplicitAdultIntent` 判定为成人意图的那类），回来一堆儿童动画和无关电影。
  - 根因方向：视频源站对不认识的分类**不报错，直接回全站或近义分类**，所以「该源有这个分类页」本身不可信；而当前只有「纯关键词入口」才过相关性过滤（`search()` 的 `searchOnlyResult` 判断 + `filterByGroupKeywords`，1010 行附近 / 1419 行），**真分类入口的结果一律直接上屏、不做任何校验**。
  - 另一种可能：`TagAlias.searchTermsFor(tag)` 的别名组里混进了过宽的词，或 `TagQuery.normalizeTitle` 归一化后按标题求交把同名不同作并了进来。
  - 排查顺序：
    ① 打日志记录每条结果的来源入口 URL + 该源分类名，先确认是「分类页本身回全站」还是「别名组太宽」；
    ② 对分类入口结果也加一层**弱相关性校验**：分类名 / `vod_class` / 标题 至少命中一个别名写法，或该源分类名与查询标签归一化后一致；不命中就丢弃并计数（日志里给出「源 X 的分类页 Y 与标签不相关」）；
    ③ 给视频标签搜索引入「分类页置信度」—— 分类名与标签完全一致才算强命中，模糊匹配降权或单列，别混在正结果里。
  - **不要一刀切回退成标题过滤**：这类题材标签本来就不写进标题，纯标题过滤会把正确结果也删光（1.8.1.2 之前的坑）。
  - 涉及：`TagSearchViewModel.kt`（`searchOnlyResult` / `filterByGroupKeywords`）、`core/util/TagAlias.kt`（`searchTermsFor` / `isExplicitAdultIntent`）、`VideoTagLexicon.kt`、`source/moontv/MacCmsParser.kt`（`vod_class` 写入）。

- [x] **37. 重点排查：视频搜索结果太少（1.8.1.0 时能搜很多）** ✅ v1.8.8.0
  - 现象：同一关键词，现在视频搜索出来的条数明显比 1.8.1.0 少。
  - **第一步先分清对象**：是「关键词搜索」变少，还是「标签搜索」变少（本次提的两条都涉及，需实测 + 日志确认后再动代码）。
  - 已知的硬性收缩点（按嫌疑排序）：
    ① **关键词搜索只发一页**：`VideoSearchViewModel.search()` → `contentRepository.searchWithProgress(type, keyword, page = 1, ...)`，全链路没有「加载更多」也没有翻页入口，每个源只贡献第 1 页那 20~40 条。对比标签搜索有 `loadMore` + 预取 3 页。
    ② **成人源默认根本不搜**：`ContentRepositoryImpl.searchWithProgress()` 在 `hideAdult = true` 时**连请求都不发**给成人源（`effectiveAdapters`，117-125 行），而视频站里成人源占比很高。若 1.8.1.0 时该开关只是「沉底」不「跳过」，条数就会断崖式下跌。
    ③ **按源筛选残留**：`sourceIds = _selectedSourceIds.value.takeIf { it.isNotEmpty() }`，筛选器里若残留上次选中的少数源，搜索范围会一直很小且界面看不出原因。
    ④ **聚合折叠**：`GroupedSearchResults.kt` 会把同名作品折叠成一条（「N 个源」），看起来条数变少 —— 要区分「折叠」与「真的没搜到」。
    ⑤ 标签侧保守参数：`MAX_URLS_PER_SOURCE = 2`、`PRELOAD_PAGES = 3`、单请求 8s 超时，任一项都比 1.8.1.0 更保守，慢源会被直接判空。
  - 排查方法：同一关键词在同一台机器上对比 v1.8.1.0 与当前版本的日志 —— 每源请求数、返回条数、超时数、被成人开关跳过的源数、被去重/相关性过滤删掉的条数，先定位是哪一层在丢量，再改。
  - 期望：视频关键词搜索支持翻页 / 加载更多；隐藏成人时至少给一句「因设置跳过 N 个源」，别让用户以为是搜不到。
  - 涉及：`ui/screens/video/VideoSearchViewModel.kt`、`VideoSearchScreen.kt`、`data/repository/ContentRepositoryImpl.kt`（`search` / `searchWithProgress`）、`PreferencesManager.hideAdultFlow`；若确认是标签侧，则回到 #35 / #36 的链路。

- [x] **40. 扩充成人向动画题材标签的别名写法** ✅ v1.8.8.2
  - 需求：这类题材在各站的写法极不统一（简繁差异、带不带「动漫 / 动画 / 漫画」后缀、站方简称），用户按其中一种写法搜索时，只能打到恰好收录了那一种写法的源，其余源明明有同类内容却整个不参与。
  - 改法：把用户反馈的一批站方常用写法（简繁双语 + 常见简称）补进 `TagAlias.GROUPS` 的对应分组，任选一种写法搜索都能覆盖到全部相关源。分组本身已由 `searchTermsFor` 按语系挑代表（`MAX_SEARCH_TERMS_PER_SOURCE = 3`），不会因为词条变多而把请求数打上去。
  - 注意：**词表是功能所需，不受对外文案红线约束**（红线只约束 CHANGELOG / 应用内更新日志 / Release 说明这类给人看的文字）。
  - 涉及：`core/util/TagAlias.kt`（`GROUPS` 中该题材分组）。
  - 后续：若实测仍覆盖不全，按「用户反馈一批 → 补一批」迭代，不必一次穷举。

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

- [x] **22. RSS 期刊改造：顶刊题录 / 引用导出 / PDF / 全文** ✅ v1.8.5.0
  - **期刊订阅**：`JournalCatalog` 内置 Science / Nature / Cell / Chemical Science / PNAS / JACS
    六本预设（ISSN 全部经 Crossref 验证），订阅页顶栏「书」图标打开期刊面板；
    搜索框走 `CrossrefClient.searchJournals`，可按刊名 / ISSN / 出版社订阅 Crossref 收录的任意期刊。
    主路径选 Crossref 而非官方 RSS：ACS 与 RSC 的 RSS 实测 403（Cloudflare），
    且 Crossref 的 `/journals/{issn}/works` 免 key、返回的结构化题录比各家 RSS 字段还齐。
  - **虚拟订阅源**：`JournalFeed` 把 `crossref:issn:xxxx-xxxx` 伪装成标准 `ParsedFeed`
    （guid 用 DOI、link 用 doi.org、contentHtml 用摘要、paperMeta 全量），
    `RssRepositoryImpl.fetch()` 开头按 ISSN 分流 —— 订阅 / 增量 / 未读 / 分组 / 收藏 / 阅读页零新增链路。
  - **题录解析**：`PaperMetaParser` 吃下 Prism（`prism:doi` / `prism:volume` / `prism:number` /
    `prism:startingPage` / `dc:creator` / `dc:date`）与「正文里的一句期刊引用」两条路径；
    普通博客不会误判成论文（`looksLikeCitation` 守卫 + `hasContent` 只认书目字段）。
  - **引用导出**：`PaperCitation` 生成 BibTeX / RIS；`PaperPdf` 按 DOI 前缀拼 PDF 直链
    （Cell / RSC 需从原文链接抠 PII / 期刊代码），拼不出就不显示按钮。
  - **阅读页增强**：`ArticleBodyExtractor` 抓原文页抽正文（Highwire `citation_*` meta →
    摘要容器 → 文本量最大容器），付费墙下也能读到摘要；可再按 DOI 向 Crossref 补题录。
    补全逐字段「补空」（`PaperMeta.mergeWith`），不整体替换 —— Crossref 与页面 meta 各有所长。
  - **先问 Crossref 再抓原文页**：实测 Science / PNAS / Cell / ACS 原文页全是 Cloudflare
    挑战页（403），Nature 对无 cookie 客户端返回 404 —— 直接抓原文页基本白跑。
    Crossref 免 key 无反爬，且 RSC（Chemical Science）有 abstract，够长时不再抓原文页。
  - `ArticleBodyExtractor.blockedReason` 认 Cloudflare 挑战 / 404 / 登录墙并给出人话原因；
    动作结果在题录卡里**常驻**（不用 Toast 一闪而过）。
  - 期刊源自动归入「期刊」分组，分组筛选下期刊与博客 / 资讯站可分开看。
  - `rss_articles` 新增 `doi` / `paperMetaJson` 两列（DB version 16，`MIGRATION_15_16`）。
  - 涉及：`core/rss/{PaperMeta,PaperCitation,PaperMetaJson,PaperPdf,CrossrefJson,CrossrefClient,
    JournalCatalog,JournalFeed,ArticleBodyExtractor}.kt`、`RssParser.kt`、`RssRepositoryImpl.kt`、
    `JournalDialogs.kt`、`RssReaderScreen.kt`。

- [x] **29. PDF 导入功能：本地 PDF 直接添加到订阅的书架** ✅ v1.8.6.0
  - 现状：应用内 PDF 阅读（`ui/screens/pdf/`，v1.8.5.1）只吃 PDF 直链 ——
    `PdfViewerViewModel` 收 `url`，经 `PdfFileCache` 下载后 `PdfRenderer` 打开，没有本地文件入口；
    本地导入（书架 / 首页「加载本地资源」）只支持 TXT（picker 限 `text/plain`），
    落到 `LocalBookStore` 由小说 / 漫画书架消费，订阅侧没有本地文档位。
  - 需求：订阅模块提供「导入 PDF」入口 —— 系统文件选择器选本地 PDF → 复制进应用私有目录 →
    **直接出现在订阅的书架 / 列表**，点击即读（复用 `Routes.PDF_VIEWER`）。
  - 实现要点：
    - 入口放 `RssScreen.kt` 顶栏（与期刊面板「书」图标同段），单选 / 多选按需定。
    - 存储：PDF 不进 `books` 表（那是小说 / 漫画书架）；用订阅侧独立存储，
      若需新实体 / 新表，按备注规矩写 Room 迁移并自增 DB 版本。
    - 阅读：`PdfViewerViewModel` 目前只认 http url，需支持本地路径
      （如把导入文件纳入 `PdfFileCache` 键空间，或给 VM 加 local 分支 ——
      注意分享用的 FileProvider 分支别受影响）。
    - 管理：订阅列表内长按可删除（同步清理文件）；阅读进度可参考 `WatchProgress` 的思路。
  - 涉及：`RssScreen.kt`、`Routes.kt`、`ui/screens/pdf/PdfViewerViewModel.kt`、`PdfFileCache.kt`、
    `AppDatabase.kt`（若新表）。
  - 实现（v1.8.6.0）：新建 `rss_pdfs` 表（`RssPdfEntity` + `RssPdfDao`，DB version 17 / `MIGRATION_16_17`），
    `core/rss/RssPdfStore.kt` 负责导入（`contentResolver.openInputStream` → `filesDir/rss_pdf/<uuid>.pdf`，
    先验 `%PDF` 魔数、128MB 上限）与删除（库记录 + 文件同清）。
    订阅页顶栏加 `PictureAsPdf` 图标（picker 限 `application/pdf`），主列表顶部「本地 PDF」分区与文章混排，
    点击走 `Routes.PDF_VIEWER` 的 `file://` 地址；`PdfViewerViewModel.load()` 加本地分支（同样验魔数后直接 open），
    `res/xml/file_paths.xml` 补 `files-path rss_pdf`（分享用）。长按删除带二次确认。

- [x] **23. PDF 阅读增强：夜间反色 / 跳页 / 文字层** ✅ v1.8.7.2
  - 现状（v1.8.5.1）：应用内只做「整页适配 + 缩放 + 翻页」，够读完一篇，但有三处明显的欠账。
  - **夜间反色**：页面是白底位图，深色主题下全屏白得刺眼。可加一个渲染开关，
    用 `ColorMatrix` 反色后再把亮度压回去（别直接 invert —— 图表和照片会变成负片），
    偏好存 `PreferencesManager`，默认关。
  - **跳页**：底部页码目前只显示不可点。论文按页引用（"见第 7 页"）时得划七下，
    改成点页码弹输入框 + `scrollToPage`。
  - **文字选择 / 全文搜索**：`PdfRenderer` 只出位图，拿不到文字层，这两件事做不到。
    要做得换 PDFium 类库（每个 ABI +8~15MB，项目已经有 ONNX 的 18MB/ABI），
    先看有没有真实需求再决定。
  - 实现（v1.8.7.2）：
    - **夜间反色**：新增 `core/pdf/PdfNightFilter.kt` —— 合成矩阵 `dim ∘ invert`（`DIM = 0.82f`，
      白纸 255→0、黑字 0→209），走 `ColorMatrixColorFilter` + `Canvas` 重绘。滤镜放在**渲染管线最后一环**
      （缓存里存的就是套完滤镜的位图），翻转开关时 `pageCache.evictAll()` 强制重渲；
      阅读页顶栏 `InvertColors` 图标与设置页同一个偏好 `KEY_PDF_NIGHT_INVERT`（默认关），
      页面底色在夜间模式下由白改黑（否则 `ContentScale.Fit` 的留白仍是一片亮）。
    - **跳页**：新增 `core/pdf/PdfPageJump.kt`（纯函数解析 1~pageCount，非法返回 null），
      底部页码改 `TextButton` → `AlertDialog` + `OutlinedTextField`（只收数字、边输边校验、
      自动聚焦弹数字键盘、回车即跳）。
    - **文字层**：确认不做。`PdfRenderer` 只输出位图，取文字层必须换 PDFium / MuPDF 类库
      （每 ABI +8~15MB）；当前「复制一段正文」的真实需求接近零，先记账不动。
    - 单测：`PdfNightFilterTest`（白底变黑 / 黑字压暗不到纯白 / 灰度单调 / alpha 透传 / 逐通道反色）、
      `PdfPageJumpTest`（越界、单位后缀、小数、未打开文档等 7 组）。

- [x] **30. PDF 接管外部「用其他软件打开」** ✅ v1.8.7.2
  - 现状：应用只是 PDF 消费者（订阅里拿链接自己下），系统选择器里看不到本应用 ——
    微信/QQ 收到、文件管理器点开、浏览器下完点通知，都得先另存再进来找。
  - 实现：`MainActivity` 加两条 `intent-filter`（`VIEW` + `SEND`，`mimeType=application/pdf`，
    带 `android:label` 让选择器显示应用名）；新增 `core/pdf/PdfIntentImport.kt` 负责
    「临时授权的 `content://` → `filesDir/pdf_inbox/<清洗后的文件名>.pdf`」的复制（同名同大小复用、
    先验 `%PDF` 魔数），复制完推给 `AppNavHost` 直接跳 `Routes.pdfViewer(file://…)`。
  - 未改 `launchMode="singleTop"`：改了会让 `novelreader://search` 深链在应用已前台时走进 `onNewIntent`
    而 `setContent` 里那段深链解析不会重跑，等于把深链弄坏；多开一个实例的代价只是返回键多退一层。
  - 只声明 `content://`：Android 7.0 起 `file://` 共享会被 `FileUriExposedException` 拦掉，
    声明了也匹配不到。

- [x] **24. RSS 订阅入口增加设置开关（默认关）+ 入口下移 + 阅读体验完善** ✅ v1.8.6.0
  - 设置项「RSS 订阅入口」：`SettingsScreen.kt` 加开关，`PreferencesManager.kt` 新增 key，**默认关闭**。
  - 现状：RSS 入口是首页顶栏的 RssFeed 图标按钮（`ui/screens/common/HomeScreen.kt` 的 `onOpenRss`）。
  - 开关开启后，把 RSS 订阅入口从顶栏搬到「订阅，小说，漫画，视频」分类栏下方；关闭时保持原样。
  - 阅读体验完善：`ui/screens/rss/RssReaderScreen.kt` / `RssScreen.kt` 的阅读排版、下拉刷新、已读状态、
    正文抽取（`core/rss/ArticleBodyExtractor.kt`）等细节。
  - 涉及：`SettingsScreen.kt`、`SettingsViewModel.kt`、`PreferencesManager.kt`、`HomeScreen.kt`、`ui/screens/rss/`。
  - 实现（v1.8.6.0）：`KEY_RSS_ENTRY_BELOW`（`rss_entry_below`，默认 false）→ 设置页「订阅（RSS）」区块开关；
    开启后首页顶栏 RssFeed 图标条件隐藏（`if (!rssEntryBelow)`），分类导航下方首行渲染 `RssEntryRow` 常驻入口。
    阅读体验：`RssReaderScreen` 顶栏加 A-/A+ 调正文字号（`KEY_RSS_FONT_SIZE`，12~24 默认 16，行高按 1.6 倍联动）。

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

- [ ] **38. 修：标签导入大型文件直接报错（导入不了）**
  - 现象：设置页「标签导入」选本地大型 tag_table.json（十几 MB 起）后立即失败，只弹「标签导入失败：…」；云端 / URL 导入是同一条链路，小表能过、大表必挂。
  - 根因方向（待复现实锤）：整条链路把全表在内存里复制了 3~4 份 ——
    ① `SettingsViewModel.loadTableText` 本地分支 `stream.readBytes().toString(UTF_8)`（`SettingsViewModel.kt:332-337`），byte[] 与 String 各一份；
    ② `TagTable.parse` 先 `JsonParser.parseString(text)` 建完整 JsonObject 树（`core/util/TagTable.kt:86`），再 `gson.fromJson` 建模型（`Payload.items`），峰值 ≈ 3 倍文件体积以上，低内存机型直接 OOM。
  - 报错误导性：`TagTable.parse` 用 `runCatching { … }.getOrNull()` 把 OOM / JsonParseException 一并吞掉返回 null，用户看到的是「打标表解析失败（不是有效的 tag_table.json）」—— 文件明明是好的，只是太大。
  - 可照搬前案：#21「源导入文件太大」的三处修法（IO 线程分块读、不进输入框、流式解析带进度）；导出侧 #20 已用 `JsonWriter` 流式写，导入侧应对称地流式读。
  - 可选方向（按性价比）：① 本地分支改流式读 + `JsonReader` 逐条解析 `items`，边解析边按类型合并写库，别等全表 parse 完；② parse 失败时带出真实异常（至少打日志 + 文案区分「格式不对」与「文件过大 / 内存不足」）；③ 导入前给体积提示 / 上限（参考 `RssPdfStore` 的 128MB 上限与魔数校验先例）。
  - 涉及：`ui/screens/settings/SettingsViewModel.kt`（`loadTableText` / `startTagImport`）、`core/util/TagTable.kt`（`parse`）、`data/repository/TagIndexRepository.kt`（`importTagTable` / `downloadCloudTable`）、`core/network/HttpClient.kt`（`getTextWithProgress`）、`ui/screens/tag/TagSearchViewModel.kt:237`（另一个导入入口，同样要扛大文件）。

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

## 八、PC 打标工具（leimao-tagger）

- [x] **25. PC 自动打标增加「标签搜索遍历」管道**
  - 现状：`leimao-tagger/pc_tagger.py` / `pc_tagger_gui.py` 只走「收录清单 → 下封面 →
    WD14 ONNX 推理 → 词表映射」的视觉管道（v1.8.1.5）。
  - 需求：补上 App 已有的「用标签搜索遍历，将搜索结果自动打标」逻辑，参考：
    - `ui/screens/tag/TagSearchViewModel.kt` / `TagSearchScreen.kt`（标签搜索与结果遍历）
    - `data/repository/TagIndexRepository.kt` / `TagRepository.kt`（打标落库）
    - `core/util/TagTagger.kt` / `ComicTagLexicon.kt`（标签层与文本管道口径：
      `ORIGIN_STATION`=站方 100 分 / `ORIGIN_TEXT`=文本 60 分）
  - 实现要点：按标签逐个发起搜索 → 遍历搜索结果页 → 对搜索结果自动打标 →
    输出合并进 tag_table.json（与视觉管道结果合并时同名标签取更高置信度）。
  - GUI（`pc_tagger_gui.py`）暴露该模式的开关与进度展示；输出仍供 App 端「云端导入」消费
    （`publish_tags.py` 推送 GitHub 的链路不变）。
  - **已完成（2026-10-21，leimao-tagger v1.1.2）**：`source_engine` 各源补 `search()` / `can_search()`
    （legado `searchUrl`+`ruleSearch`、App JSON 源 `search.url` 含 `{{keyword}}`、MacCMS `ac=detail&wd=`）；
    新增 `tag_search.py`（词表 groups → 同义写法挑选与 App `searchTermsForQuery` 同口径 →
    全源轮转分配 → 逐标签搜索）；CLI 加 `--tag-search/--tags/--tag-limit/--tag-sources/--tag-terms/--tag-pages`；
    GUI 加「标签搜索遍历」开关与进度。搜索结果**只用于发现条目**，不把搜索词写成标签（打标证据化）。

---

## 备注

- 改动涉及 `books` 表结构或 `RssFeedEntity` 时，必须写 Room 迁移（`AppDatabase.kt`）并自增数据库版本。
- 新增设置项统一走 `core/preferences/PreferencesManager.kt`，不要各自散落存 SharedPreferences。
- 涉及 jsDelivr 的改动注意 CDN 缓存：新文件路径变化才不会被 7 天缓存命中，必要时用 `leimao-tagger` 里的 `publish_tags.py --purge`。
