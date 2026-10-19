# -*- coding: utf-8 -*-
"""从 app 源码 TagAlias.kt 的 GROUPS 生成 vocab.json（受控词表）。

用法：python tagger/gen_vocab.py [TagAlias.kt 路径] [输出路径]
默认：app/src/main/java/com/novelreader/core/util/TagAlias.kt -> tagger/vocab.json
词表改动后重跑一次并提交，PC 工具即可同步映射口径。
"""
import json
import re
import sys
from pathlib import Path

def main() -> None:
    root = Path(__file__).resolve().parent.parent
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "app/src/main/java/com/novelreader/core/util/TagAlias.kt"
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).resolve().parent / "vocab.json"
    text = src.read_text(encoding="utf-8")
    body = text.split("GROUPS: List<Set<String>> = listOf(", 1)[1].split("\n    )", 1)[0]
    groups = []
    for block in re.findall(r"setOf\(([^)]*)\)", body, re.S):
        names = re.findall(r'"([^"]+)"', block)
        names = [n.strip() for n in names if n.strip()]
        if names:
            groups.append(names)
    payload = {"version": 1, "groups": groups}
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
    total = sum(len(g) for g in groups)
    print(f"vocab.json: {len(groups)} 组 / {total} 个写法 -> {out}")

if __name__ == "__main__":
    main()
