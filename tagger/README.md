# 磊毛阅读 · PC 端打标工具

在电脑上给 App 的「收录清单」补打封面标签（WD14 ONNX 端侧推理），产出的表单
推到本仓库 `tagger/data/tag_table.json`，App 端「标签搜索 → 已收录 N 部 → 云端导入」
即可把打标成果合并进本地标签层（同名标签取更高置信度，站方证据不会被覆盖）。

## 文件

| 文件 | 说明 |
|---|---|
| `pc_tagger.py` | 主工具（Python 3.10+） |
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

# 3) 推送到 GitHub（App 即可通过「云端导入」拉取）
python pc_tagger.py --list leimao-tag-table.json --out tag_table.json --push \
    --token ghp_xxx --repo leimao2333/leimao-reader --path tagger/data/tag_table.json
```

参数：`--limit N` 本轮最多打标条数；`--workers N` 封面下载并发；`--model-dir` 模型缓存目录；
`--vocab` 词表路径；`--token` 也可用环境变量 `GH_TOKEN`。

## 打包 exe

```bash
pip install pyinstaller
pyinstaller --onefile --name leimao-pc-tagger pc_tagger.py
# 产物 dist/leimao-pc-tagger.exe（首次运行会随 exe 同目录建 models/ 缓存）
```

注意：exe 需与 `vocab.json` 同目录放置（工具按脚本所在目录找词表）。
