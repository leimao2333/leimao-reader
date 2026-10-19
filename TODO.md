# 待完成清单（Roadmap）

> 基线版本：**v1.8.2.0**（commit `d76a7e2`）
> 记录时间：2026-10-19
> 说明：本文件只记录待开工的需求，做完后把对应条目的 `- [ ]` 改成 `- [x]` 并在该行末尾补上落地版本号，随后从清单里移除或折叠进 CHANGELOG。

图例：`- [ ]` 待做 / `- [x]` 已完成（末尾标注版本）

---

## 一、阅读与书架（小说 / 漫画）

- [ ] **1. 漫画增加「未加入书架」提示弹窗**
  - 小说已有实现，位置：`ui/screens/novel/NovelReaderScreen.kt`（`showShelfPrompt`，打开阅读后询问一次）。
  - 漫画按同样逻辑在 `ui/screens/comic/ComicReaderScreen.kt` 补一份：打开漫画 → 若未收藏 → 弹窗询问「加入书架 / 暂不」。
  - 必须等收藏状态加载完成再判断（参考小说的 `shelfStateReady` 标记），否则初始 `false` 会误弹。

- [ ] **2. 设置里给「未加入书架提示」加开关，小说与漫画分开**
  - `core/preferences/PreferencesManager.kt` 新增两个独立 key（如 `KEY_NOVEL_SHELF_PROMPT` / `KEY_COMIC_SHELF_PROMPT`），默认开启。
  - 设置页拆成两行分别控制，互不影响；关闭后对应模块不再弹窗。

- [ ] **3. 书架旁边的加号图标改为中文「加载本地资源」**
  - 小说、漫画书架上的 `+` 图标容易被理解成「添加源 / 新建分组」。
  - 换成文字按钮「加载本地资源」，功能不变。位置：`ui/screens/bookshelf/BookShelfScreen.kt`。

- [ ] **4. 恢复小说 / 漫画书籍的「长按删除」**
  - 长按菜单被后加的项顶掉了，删除入口丢失。
  - 恢复方式：把「删除」放回长按菜单首位，并加二次确认弹窗（删除书籍 = 同时清掉章节缓存与阅读进度）。

- [ ] **5. 小说 / 漫画书架新增批量管理**
  - 进入多选模式：全选 / 反选 / 按分组选。
  - 批量操作至少包含：批量删除、批量移动到分组、批量清除已缓存章节。
  - 底部浮条显示「已选 N 本」，计数与操作都要走批量 DAO（不要循环单条）。

- [ ] **6. 小说与漫画的分组完全分开**
  - 当前 `books` 表共用，`BookDao` 里的分组查询没有按 `type` 过滤，导致小说建的分组在漫画里也可见。
  - 改法：`observeGroupNames` / `setGroup` / `renameGroup` / `clearGroup` 全部带 `type` 参数（或改成分组独立表 + `type` 列）。
  - 涉及：`data/database/BookDao.kt`、`ui/screens/bookshelf/BookShelfViewModel.kt` 与 `BookShelfScreen.kt` 的分组 UI。

---

## 二、预加载（设置可调）

- [ ] **7. 小说预加载幅度可在设置里调**
  - 当前小说阅读会向后预加载章节，幅度写死。
  - 设置项：「小说预加载章节数」→ 关 / 1 / 3 / 5 / 10 章，存进 `PreferencesManager`。
  - 涉及：`ui/screens/novel/NovelReaderViewModel.kt` 的预加载逻辑。

- [ ] **8. 视频预加载秒数可在设置里调（边看边缓冲）**
  - 设置项：「视频预缓存时长」→ 关 / 15s / 30s / 60s / 120s，控制当前播放视频向后缓冲的秒数。
  - 涉及：`data/cache/VideoPrefetchCache.kt`、`ui/screens/video/VideoPlayerViewModel.kt`。

---

## 三、视频

- [ ] **9. 快退手势加开关（关闭后左右两边都是快进）**
  - 现状：左右滑动分别是快退 / 快进。
  - 设置项「双击/滑动快退」开关：关闭后左侧滑动也变成快进，两侧行为一致。
  - 长按手势快进 10 秒（`VideoControls.kt`）保持独立，不受此开关影响。

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

---

## 四、搜索

- [ ] **13. 搜索结果长按加入书架（可在设置里开关）**
  - 小说 / 漫画 / 视频三个搜索页共用 `ui/components/GroupedSearchResults.kt`，在此统一加长按手势。
  - 长按 → 菜单「加入书架」；行为与点击默认进阅读页区分开。
  - 设置项开关，默认开启。

- [ ] **14. 搜索结果在「阅读」按钮旁新增收藏按钮**
  - 每条结果行内在「阅读」旁加一个收藏（心形/星形）图标，已收藏时高亮。
  - 同样落在 `GroupedSearchResults.kt`，一处改动三页生效。

---

## 五、RSS

- [ ] **15. RSS 源批量管理**
  - **分组**：`RssFeedEntity` 加 `groupName` 字段（含 Room 迁移），列表支持按分组筛选 / 折叠。
  - **批量删除**：多选模式 + 全选，批量删除源（连同 `RssArticleEntity` 的文章一起清，避免留孤儿数据）。
  - 涉及：`data/database/RssDao.kt`、`ui/screens/rss/RssScreen.kt`、`RssReaderScreen.kt`。

---

## 六、云端标签导入

- [ ] **16. 云端导入增加进度条**
  - 分三段上报进度：下载（按字节数算百分比）→ 解析 → 批量入库（按分块数算百分比，当前 `IMPORT_CHUNK = 2000`）。
  - 弹窗内显示 `xx%` + 阶段文案，并支持取消（取消时要保证已写入的数据可回滚或至少不残留半截）。
  - 涉及：`ui/screens/settings/SettingsViewModel.kt`（`importCloudLatest` / `importPayload`）、`data/repository/TagIndexRepository.kt`（`importTagTable`）、`ui/screens/settings/SettingsScreen.kt` 弹窗。

---

## 备注

- 改动涉及 `books` 表结构或 `RssFeedEntity` 时，必须写 Room 迁移（`AppDatabase.kt`）并自增数据库版本。
- 新增设置项统一走 `core/preferences/PreferencesManager.kt`，不要各自散落存 SharedPreferences。
- 涉及 jsDelivr 的改动注意 CDN 缓存：新文件路径变化才不会被 7 天缓存命中，必要时用 `leimao-tagger` 里的 `publish_tags.py --purge`。
