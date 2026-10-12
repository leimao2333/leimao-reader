# 磊毛阅读

> 一款能导入小说、漫画、视频源的安卓阅读器

**磊毛阅读**是一款支持自定义导入书源的聚合类安卓阅读 App，统一支持 **小说 / 漫画 / 视频** 三大内容形态。用户可自由导入 JSON 源、JS 源、兼容「阅读 (legado)」标准书源，以及 **Venera 漫画源**（含 `index.json` 源集合一键批量导入），实现一站式的阅读、追漫、看片体验。

## 核心特性

| 能力 | 说明 |
|------|------|
| 源导入 | 支持 JSON 源 + JS 源 + 阅读(legado) 书源 + Venera 漫画源（含 `index.json` 批量导入），含 txt 网址书源，支持本地文件选择与链接在线下载（带进度） |
| 小说阅读 | 字体大小可调、背景色可选、白天/夜间双模式、章节缓存下载 |
| 漫画阅读 | 完美适配屏幕、翻页方式可切换（上下滚动/左右翻页）、滚到边界继续拖即切上一话/下一话、图片缓存下载 |
| 视频播放 | 支持视频目录（选集）、播放进度记录、长按快进、视频下载 |
| 搜索 | 三类内容均支持站内搜索，跨源并发搜索 + 实时进度展示 + 模糊搜索 + 搜索历史 |
| 源管理 | 源智能分类、类型筛选、排序、列表/表格双视图 |
| 书架 | 收藏管理、阅读进度记录、继续阅读 |

## 技术栈

- **语言**：Kotlin
- **UI**：Jetpack Compose
- **架构**：MVVM + 分层（Core / Source / Domain / Data / UI）
- **JS 引擎**：Rhino 1.7.15（兼容 legado/阅读 JS 源）；**隐藏 WebView (V8)**（承载 Venera 源，其依赖 class 字段 / async-await，Rhino 不支持）
- **网络**：OkHttp + Retrofit
- **数据库**：Room
- **图片**：Coil
- **视频**：Media3 (ExoPlayer)
- **偏好设置**：DataStore

## 项目结构

```
NovelReader/
├── docs/                    # 文档
│   ├── 立项文档/            # 项目立项书
│   ├── 需求分析/            # 需求分析（含源规范）
│   ├── 架构设计/            # 架构设计
│   ├── 开发计划/            # 开发计划
│   ├── 进度总结/            # 各阶段进度总结
│   └── 构建运行指南.md      # 构建与运行说明
├── app/                     # 应用模块
│   └── src/main/java/com/novelreader/
│       ├── core/            # 基础能力（js/network/preferences/download/version）
│       ├── source/          # 源引擎（loader/parser/manager/engine/legado/category/search）
│       ├── domain/          # 业务层（model/repository）
│       ├── data/            # 数据实现（model/repository/database）
│       └── ui/              # 界面（theme/screens/components/navigation）
├── samples/                 # 源样例（json/js 各三类）
└── ...
```

## 开发进度

| 里程碑 | 内容 | 状态 |
|--------|------|------|
| M0 | 立项 + 工程骨架 | ✅ 完成 |
| M1 | 数据模型与源规范 | ✅ 完成 |
| M2 | 源解析引擎 | ✅ 完成 |
| M3 | 网络与数据层 | ✅ 完成 |
| M4 | 小说模块 | ✅ 完成 |
| M5 | 漫画模块 | ✅ 完成 |
| M6 | 视频模块 | ✅ 完成 |
| M7 | 通用页面与集成收尾 | ✅ 完成 |
| M8 | 文件导入/进度恢复/防盗链/继续阅读 | ✅ 完成 |
| M9 | 真机部署 + 闪退排查修复 | ✅ 完成 |
| 追加 | legado 书源支持 / 并行搜索 / 模糊搜索 / 智能分类 / 下载 / 搜索历史 / 排序 / 表格视图 / 更新日志 | ✅ 完成 |

详细进度见 `docs/进度总结/`，应用版本更新日志见 `CHANGELOG.md`。

## 如何构建

1. 使用 Android Studio 打开本目录
2. 等待 Gradle 同步完成
3. 运行到设备或模拟器（minSdk 24，即 Android 7.0+）

> 注意：首次构建需联网下载依赖，Rhino 来自 jitpack 仓库。

## 核心设计

**适配器模式**：上层只依赖 `SourceAdapter` 接口，JSON 源、JS 源、legado 书源各自实现，新增源格式只需新增一个 Adapter，无需改动上层代码。

详见 `docs/架构设计/架构设计.md`。

## 免责声明

本项目仅用于学习与个人使用，请勿用于任何违法违规用途。请尊重内容版权，支持正版。
