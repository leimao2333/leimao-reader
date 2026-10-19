# 磊毛阅读 · PC 端打标工具

在电脑上给 App 的「收录清单」补打封面标签（WD14 ONNX 端侧推理），产出的表单
推到本仓库 `tagger/data/tag_table.json`，App 端「设置 → 标签 → 标签导入」（或
「标签搜索 → 已收录 N 部 → 云端导入」）即可把打标成果合并进本地标签层
（同名标签取更高置信度，站方证据不会被覆盖）。

v1.8.1.6 起支持：

- **网络源导入（书源遍历）**：exe 里直接填**书源文件 / 网址**（多个用 `|` 分隔），
  PC 直接遍历源站的分类 / 采集接口，抓取条目的 标题 + 封面 汇入打标清单 ——
  不必先从手机导出。支持三类源：
  - **legado / shuyuan 书源**（`bookSourceUrl` + `exploreUrl` + `ruleExplore`，即 xiu2/yuedu 的 shuyuan）；
  - **App JSON 源规范**（`{name, baseUrl, search:{url, list, fields}}`，search 地址需要 {{keyword}} 的跳过）；
  - **MoonTV / MacCMS 采集站**（`{api_site: {...}}`，按视频收录）。

  JS 源 / Venera 源无法在 PC 端评估，遍历时跳过并记录日志。规则引擎为精简实现
  （JSONPath 子集 + `selector@attr` / `class.x` / `tag.x` 链式选择 + `{{page}}` 渲染），
  复杂规则（`@js:`、`{{js}}` 表达式）的字段会留空跳过。每源进度显示为「[遍历] 源名」。
- **网络清单**：直接拉取 GitHub 云端打标表作为基底清单，与本地清单合并 ——
  不必每次先从手机导出；`--cloud <URL>`（CLI）或页面「网络清单」输入框。
- **标题解析打标**：参考 [kawayiYokami/MangaReader](https://github.com/kawayiYokami/MangaReader)
  的 `metadata_parser`，从书名解析 `作者 / 汉化组 / 作品 / 会场 / 平台 / 其他`
  元数据标签（识别 `[汉化组 (作者)] (C97) [作品名] 标题 [个人汉化]` 惯用命名），
  以「文本」来源、置信度 60 入库，每次打标全量执行。

## 文件

| 文件 | 说明 |
|---|---|
| `pc_tagger.py` | 核心逻辑 + CLI（Python 3.10+） |
| `pc_tagger_gui.py` | **Web 界面版**：运行后自动打开浏览器页面，遍历所有源打标，实时显示总体进度 / 当前源 / 各源进度表 / 日志，可中途停止 |
| `source_engine.py` | 网络源遍历引擎：legado 书源 / App JSON 源 / MacCMS 采集站的精简规则引擎 |
| `gen_vocab.py` | 从 App 源码 `TagAlias.kt` 生成受控词表 `vocab.json`（词表改动后重跑） |
| `vocab.json` | 受控词表（danbooru/english → 中文规范名），PC 端映射口径 |
| `data/tag_table.json` | 云端打标表（App「云端导入」的默认地址） |
| `requirements.txt` | Python 依赖 |

## 使用

```bash
pip install -r requirements.txt

# 1) App：标签搜索 → 点「已收录 N 部」→ 导出收录清单（leimao-tag-table.json）
# 2) PC：打标（模型 356MB 首次自动从 hf-mirror 下载）
python pc_tagger.py --list leimao-tag-table.json --out tag_table.json --limit 50

# 2b) 不导清单也行：直接拉云端表作为基底（--list 可省略）
python pc_tagger.py --cloud --out tag_table.json

# 3) 推送到 GitHub（App 即可通过「设置 → 标签 → 标签导入」拉取）
python pc_tagger.py --list leimao-tag-table.json --out tag_table.json --push \
    --token ghp_xxx --repo leimao2333/leimao-reader --path tagger/data/tag_table.json
```

参数：`--cloud [URL]` 网络清单（缺省用 jsDelivr 云端地址）；`--limit N` 本轮最多打标条数；
`--workers N` 封面下载并发；`--model-dir` 模型缓存目录；`--vocab` 词表路径；
`--token` 也可用环境变量 `GH_TOKEN`。

## 打包 exe

```bash
pip install pyinstaller
pyinstaller --onefile --windowed --name leimao-pc-tagger pc_tagger_gui.py
# 产物 dist/leimao-pc-tagger.exe（42MB，界面版；模型缓存在 exe 所在目录的 models/）
```

exe 运行后自动打开浏览器 `http://127.0.0.1:8765/`：填入清单路径 → 开始遍历打标 →
页面实时显示「总体进度 / 当前源 / 各源进度表 / 日志」→ 勾选推送 GitHub 即完成云端更新。
exe 已把 `vocab.json` 内置打包 —— 单文件即用；外置同名文件优先（想换词表口径时放 exe 旁边即可覆盖）。
