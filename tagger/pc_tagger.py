#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""磊毛阅读 · PC 端封面打标工具（v1.8.1.5）。

流程：读取 App 导出的「收录清单」（tag_table.json）→ 下载封面 → WD14 ONNX 推理 →
Danbooru 标签映射进受控词表（vocab.json，由 tagger/gen_vocab.py 从 App 生成）→
以「同名标签取更高置信度」合并 → 输出完整 tag_table.json → 可选推送 GitHub。
App 端「云端导入」拉取同一份表，即完成 PC→手机 的打标补充。

用法示例：
  # 1) 只打标，输出到本地文件
  pc_tagger.py --list leimao-tag-table.json --out tag_table.json --limit 50

  # 2) 打标并推送 GitHub（App 端即可「云端导入」）
  pc_tagger.py --list leimao-tag-table.json --out tag_table.json --push \
      --token ghp_xxx --repo leimao2333/leimao-reader --path tagger/data/tag_table.json

首次运行会把模型（约 356MB）下载到 --model-dir（默认 ./models/wd14），仅下载一次。
"""

from __future__ import annotations

import argparse
import base64
import json
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urlparse

import requests

MODEL_URL = "https://hf-mirror.com/SmilingWolf/wd-v1-4-vit-tagger-v2/resolve/main/model.onnx"
TAGS_URL = "https://hf-mirror.com/SmilingWolf/wd-v1-4-vit-tagger-v2/resolve/main/selected_tags.csv"
INPUT_SIZE = 448
THRESHOLD = 0.30
CONF_MIN, CONF_MAX = 20, 95
ORIGIN_VISUAL = "视觉"
DEFAULT_CLOUD_PATH = "tagger/data/tag_table.json"
USER_AGENT = "leimao-pc-tagger/1.0"

# ---------------------------------------------------------------- 词表映射


def norm(name: str) -> str:
    return "".join(name.split()).lower()


class Vocab:
    """受控词表：danbooru/english 写法 → 中文规范名（来自 App 的 TagAlias.GROUPS）。"""

    def __init__(self, groups: list[list[str]]):
        self.alias_to_canonical: dict[str, str] = {}
        for group in groups:
            canonical = group[0]
            for name in group:
                self.alias_to_canonical[norm(name)] = canonical

    @classmethod
    def load(cls, path: Path) -> "Vocab":
        data = json.loads(path.read_text(encoding="utf-8"))
        return cls(data["groups"])

    def to_controlled(self, danbooru_tag: str) -> str | None:
        """`long_hair` → `长发`；未登记返回 None（不污染标签池）。"""
        spaced = danbooru_tag.replace("_", " ").strip()
        if not spaced:
            return None
        return self.alias_to_canonical.get(norm(spaced))


# ---------------------------------------------------------------- 模型


def ensure_model(model_dir: Path) -> tuple[Path, Path]:
    model_dir.mkdir(parents=True, exist_ok=True)
    model = model_dir / "model.onnx"
    tags_csv = model_dir / "selected_tags.csv"
    if not model.exists() or model.stat().st_size < 1024 * 1024:
        part = model_dir / "model.onnx.part"
        print(f"下载视觉模型（约 356MB）-> {model}")
        download(MODEL_URL, part)
        part.replace(model)
    if not tags_csv.exists() or tags_csv.stat().st_size < 1024:
        print("下载标签表…")
        download(TAGS_URL, tags_csv)
    return model, tags_csv


def download(url: str, target: Path) -> None:
    with requests.get(url, stream=True, timeout=60, headers={"User-Agent": USER_AGENT}) as resp:
        resp.raise_for_status()
        total = int(resp.headers.get("content-length") or 0)
        done = 0
        with open(target, "wb") as fh:
            for chunk in resp.iter_content(chunk_size=1024 * 512):
                if not chunk:
                    continue
                fh.write(chunk)
                done += len(chunk)
                if total:
                    pct = done * 100 // total
                    print(f"\r  {pct}% ({done // 1048576}MB / {total // 1048576}MB)", end="", flush=True)
        print()


class Wd14Model:
    """ONNX 推理封装：白底补方 + 448 输入 + BGR float32（与 App 端口径一致）。"""

    def __init__(self, model_path: Path, tags_csv: Path):
        import numpy as np
        import onnxruntime as ort

        self.np = np
        self.session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
        self.input_name = self.session.get_inputs()[0].name
        self.labels: list[tuple[str, int]] = []
        lines = tags_csv.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            line = line.strip()
            if not line or (i == 0 and line.startswith("tag_id")):
                continue
            parts = _split_csv_line(line)
            if len(parts) < 3:
                continue
            name, category = parts[1].strip(), int(parts[2])
            if category in (0, 3):  # general + character
                self.labels.append((name, category))

    def tag(self, image_bytes: bytes) -> list[tuple[str, float]]:
        from io import BytesIO

        from PIL import Image

        np = self.np
        image = Image.open(BytesIO(image_bytes)).convert("RGB")
        side = max(image.size)
        square = Image.new("RGB", (side, side), (255, 255, 255))
        square.paste(image, ((side - image.size[0]) // 2, (side - image.size[1]) // 2))
        scaled = square.resize((INPUT_SIZE, INPUT_SIZE))
        # RGB -> BGR，float32 0..255，NHWC
        arr = np.asarray(scaled, dtype=np.float32)[:, :, ::-1]
        outputs = self.session.run(None, {self.input_name: arr[None, ...]})
        probs = outputs[0][0]
        return [
            (self.labels[i][0], float(p))
            for i, p in enumerate(probs[: len(self.labels)])
            if p >= THRESHOLD
        ]


def _split_csv_line(line: str) -> list[str]:
    out, field, in_quotes = [], [], False
    for ch in line:
        if ch == '"':
            in_quotes = not in_quotes
        elif ch == "," and not in_quotes:
            out.append("".join(field))
            field = []
        else:
            field.append(ch)
    out.append("".join(field))
    return out


# ---------------------------------------------------------------- 打标主流程


def merge_entry(existing: dict | None, incoming: dict) -> dict:
    """同名标签取置信度更高的一方（与 App 端 TagTable.mergeEntry 同口径）。"""
    if existing is None or incoming["confidence"] > existing["confidence"]:
        return incoming
    return existing


def download_cover(url: str, timeout: int = 20) -> bytes | None:
    try:
        if url.startswith("file://"):
            return Path(url[7:]).read_bytes()
        resp = requests.get(url, timeout=timeout, headers={"User-Agent": USER_AGENT})
        if resp.status_code == 200 and resp.content:
            return resp.content
    except Exception as exc:  # noqa: BLE001
        print(f"  封面下载失败 {url[:80]}: {exc}")
    return None


def run(args: argparse.Namespace) -> int:
    from PIL import Image  # noqa: F401  提前失败，免得跑完下载才报缺依赖

    if not args.list and not args.cloud and not args.sources:
        print("需要 --list（本地清单）/ --cloud（网络清单）/ --sources（书源）至少一个")
        return 2
    items: list[dict] = []
    if args.cloud:
        cloud_url = args.cloud if args.cloud.startswith("http") else DEFAULT_CLOUD_TABLE_URL
        print(f"拉取网络清单：{cloud_url}")
        items = fetch_cloud_table(cloud_url)
        print(f"网络清单条目：{len(items)}")
    if args.list:
        local = json.loads(Path(args.list).read_text(encoding="utf-8")).get("items", [])
        print(f"本地清单条目：{len(local)}")
        items = merge_items(items, local)
        print(f"合并后条目：{len(items)}")

    # 网络源遍历（书源文件/网址 → 逐源抓 标题+封面）
    if args.sources:
        import source_engine
        for spec in [s.strip() for s in args.sources.split("|") if s.strip()]:
            try:
                sources = source_engine.load_sources_from_text_or_url(spec, args.src_type, print)
            except Exception as exc:  # noqa: BLE001
                print(f"书源装载失败 {spec[:60]}：{exc}")
                continue
            print(f"书源装载成功：{len(sources)} 个源")
            for source in sources:
                try:
                    found = source.explore(args.src_pages, print)
                except Exception as exc:  # noqa: BLE001
                    print(f"  ✗ 遍历失败 {source.name}：{exc}")
                    found = []
                if found:
                    items = merge_items(items, found)
                    print(f"  {source.name}：收录 {len(found)} 部")

    # 标题解析打标（MangaReader 式：作者 / 汉化组 / 作品 / 会场 等，origin=文本）
    title_tagged = apply_title_tags(items)
    print(f"标题解析命中：{title_tagged} 部")

    vocab = Vocab.load(Path(args.vocab))

    def has_visual(item: dict) -> bool:
        return any(t.get("origin") == ORIGIN_VISUAL for t in item.get("tags", []))

    pending = [it for it in items if it.get("cover") and not has_visual(it)]
    if args.limit > 0:
        pending = pending[: args.limit]
    print(f"待视觉打标：{len(pending)}（其余已打过或无封面）")
    if not pending:
        print("没有需要打标的条目。")
        _write_out(args, items)
        return 0

    model_path, tags_csv = ensure_model(Path(args.model_dir))
    print("加载 ONNX 模型…")
    model = Wd14Model(model_path, tags_csv)

    def process(item: dict) -> bool:
        title = (item.get("title") or "")[:40]
        try:
            image_bytes = download_cover(item["cover"])
            if not image_bytes:
                return False
            raw = model.tag(image_bytes)
        except Exception as exc:  # noqa: BLE001
            print(f"  推理失败 {title}: {exc}")
            return False
        merged: dict[str, dict] = {t["name"]: dict(t) for t in item.get("tags", [])}
        hit = 0
        for danbooru_name, prob in raw:
            controlled = vocab.to_controlled(danbooru_name)
            if not controlled:
                continue
            confidence = max(CONF_MIN, min(CONF_MAX, int(prob * 100)))
            merged[controlled] = merge_entry(
                merged.get(controlled),
                {"name": controlled, "category": "题材", "confidence": confidence, "origin": ORIGIN_VISUAL},
            )
            hit += 1
        if not hit:
            return False
        item["tags"] = sorted(merged.values(), key=lambda t: -t["confidence"])
        return True

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for index, item in enumerate(pending):
            done = pool.submit(process, item)
            # 顺序打印，避免输出交错；推理本身在主线程，仅下载并发
            if done.result():
                print(f"[{index + 1}/{len(pending)}] ✓ {title_of(item)}")

    written = _write_out(args, items)
    print(f"完成：本轮视觉打标 {written} 部，输出 {Path(args.out).resolve()}")
    if args.push:
        push_to_github(args, Path(args.out).read_text(encoding="utf-8"))
    return written


def title_of(item: dict) -> str:
    return (item.get("title") or item.get("itemId") or "")[:40]


def _write_out(args: argparse.Namespace, items: list[dict]) -> int:
    tagged = sum(
        1
        for it in items
        if any(t.get("origin") == ORIGIN_VISUAL for t in it.get("tags", []))
    )
    payload = {
        "version": 1,
        "generatedAt": int(time.time() * 1000),
        "source": "pc",
        "items": items,
    }
    Path(args.out).write_text(
        json.dumps(payload, ensure_ascii=False, indent=1),
        encoding="utf-8",
    )
    return tagged


# ---------------------------------------------------------------- 网络清单 + 标题解析（v1.8.1.6）


CLOUD_TABLE_URL = (
    "https://cdn.jsdelivr.net/gh/leimao2333/leimao-reader@main/tagger/data/tag_table.json"
)
ORIGIN_TEXT = "文本"
TEXT_CONFIDENCE = 60


def fetch_cloud_table(url: str, timeout: int = 30) -> list[dict]:
    """拉取云端打标表（App「导出收录清单」同 schema），返回 items。"""
    resp = requests.get(url, timeout=timeout, headers={"User-Agent": USER_AGENT})
    resp.raise_for_status()
    return resp.json().get("items", [])


def _clean_tag_value(value: str) -> str | None:
    """过滤明显不是标签的碎片：空、纯数字、过长。"""
    value = value.strip()
    if not value or value.isdigit() or len(value) > 30:
        return None
    return value


def parse_title_tags(title: str) -> list[tuple[str, str]]:
    """从书名解析元数据标签（移植自 kawayiYokami/MangaReader 的 metadata_parser.py）。

    识别 `[(C97)] [汉化组 (作者)] [(作品名)] 标题 [个人汉化] [无修正]` 这类
    漫画圈惯用命名，输出 (大类, 标签值)：平台 / 组 / 作者 / 会场 / 作品 / 汉化 / 其他。
    """
    tags: list[tuple[str, str]] = []
    text = (title or "").strip()
    if not text:
        return tags

    def add(category: str, value: str | None) -> None:
        value = _clean_tag_value(value or "")
        if value and (category, value) not in tags:
            tags.append((category, value))

    # 杂志/平台信息 (Fantia) —— 开头圆括号且不含数字
    platform_match = re.match(r"[\(（](.*?)[\)）](.*)", text)
    if platform_match and not re.search(r"\d", platform_match.group(1)):
        add("平台", platform_match.group(1))
        text = platform_match.group(2).strip()

    # [汉化组 (作者)]
    group_author = re.search(r"\[(.*?) \((.*?)\)\]", text)
    if group_author:
        add("组", group_author.group(1))
        add("作者", group_author.group(2))
        text = text.replace(group_author.group(0), "", 1).strip()
    else:
        # [作者]（单独方括号）
        author_match = re.search(r"\[(.*?)\]", text)
        if author_match and "汉化" not in author_match.group(1):
            add("作者", author_match.group(1))
            text = text.replace(author_match.group(0), "", 1).strip()

    # 会场 (C97)
    event_match = re.match(r"\(([Cc][0-9]+)\)(.*)", text)
    if event_match:
        add("会场", event_match.group(1))
        text = event_match.group(2).strip()

    # (作品名) —— 后面不再跟方括号的那个圆括号
    series_match = re.search(r"[\(（]([^()（）\d]*?)[\)）](?![^[]*\])", text)
    if series_match and series_match.group(1).strip():
        add("作品", series_match.group(1))
        text = text[: text.rfind(series_match.group(0))].strip()

    # 其余方括号：汉化 / 无修正 / 其他
    while True:
        bracket = re.search(r"\[(.*?)\]", text)
        if not bracket:
            break
        content = bracket.group(1).strip()
        if any(k in content for k in ("中国翻訳", "中国翻译", "中國翻譯", "中國翻訳")):
            add("汉化", "中国翻译")
        elif any(k in content for k in ("汉化", "漢化", "翻訳", "翻译", "翻譯")):
            add("汉化", content)
        elif any(k in content for k in ("無修正", "无修正", "無修")):
            add("其他", "无修正")
        else:
            add("其他", content)
        text = text.replace(f"[{bracket.group(1)}]", "", 1).strip()

    return tags


def apply_title_tags(items: list[dict]) -> int:
    """对全部条目跑标题解析，把成果以 origin=文本、confidence=60 入 tags。

    与 App 端文本猜测同口径；merge_entry 保证站方/行为标签不会被顶掉。
    返回新增了标签的条目数。
    """
    touched = 0
    for item in items:
        parsed = parse_title_tags(item.get("title") or "")
        if not parsed:
            continue
        merged = {t["name"]: dict(t) for t in item.get("tags", [])}
        before = len(merged)
        for category, value in parsed:
            merged[value] = merge_entry(
                merged.get(value),
                {"name": value, "category": category, "confidence": TEXT_CONFIDENCE,
                 "origin": ORIGIN_TEXT},
            )
        if len(merged) > before:
            item["tags"] = sorted(merged.values(), key=lambda t: -t["confidence"])
            touched += 1
    return touched


def item_key(item: dict) -> str:
    return "|".join((
        str(item.get("sourceId") or ""),
        str(item.get("itemId") or ""),
        str(item.get("itemType") or ""),
    ))


def merge_items(base: list[dict], extra: list[dict]) -> list[dict]:
    """按 (sourceId, itemId, itemType) 合并两份清单；同名标签取更高置信度。

    `base` 里的条目在先，`extra` 提供补充（标签级合并）；`extra` 独有的条目直接追加。
    """
    by_key: dict[str, dict] = {}
    for item in base + extra:
        key = item_key(item)
        existing = by_key.get(key)
        if existing is None:
            item = dict(item)
            item["tags"] = {t.get("name", ""): dict(t) for t in item.get("tags", [])}
            by_key[key] = item
            continue
        for name, tag in item.get("tags", []):
            existing["tags"][tag.get("name") or name] = merge_entry(
                existing["tags"].get(tag.get("name") or name), dict(tag),
            )
    out = []
    for item in by_key.values():
        item["tags"] = sorted(item["tags"].values(), key=lambda t: -t.get("confidence", 0))
        out.append(item)
    return out


def push_to_github(args, content: str) -> None:
    token = args.token or os_environ("GH_TOKEN")
    if not token:
        print("推送跳过：未提供 --token，且环境变量 GH_TOKEN 为空")
        return
    push_to_github_simple(
        token=token,
        repo=args.repo,
        branch=args.branch,
        path=args.path,
        content=content,
        log=print,
    )


def push_to_github_simple(
    token: str | None,
    repo: str,
    branch: str,
    path: str,
    content: str,
    log,
) -> None:
    """通过 GitHub contents API 上传/更新一个文本文件（GUI 与 CLI 共用）。"""
    if not token:
        log("推送跳过：未提供 token（--token 或环境变量 GH_TOKEN）")
        return
    api = f"https://api.github.com/repos/{repo}/contents/{path}"
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"}
    sha = None
    current = requests.get(api, headers=headers, params={"ref": branch}, timeout=30)
    if current.status_code == 200:
        sha = current.json().get("sha")
    body = {
        "message": f"tagger: 更新云端打标表（{len(json.loads(content).get('items', []))} 条）",
        "content": base64.b64encode(content.encode("utf-8")).decode("ascii"),
        "branch": branch,
    }
    if sha:
        body["sha"] = sha
    resp = requests.put(api, headers=headers, data=json.dumps(body), timeout=60)
    if resp.status_code in (200, 201):
        log(f"已推送 https://github.com/{repo}/blob/{branch}/{path}")
    else:
        log(f"推送失败：{resp.status_code} {resp.text[:200]}")


def os_environ(key: str) -> str | None:
    import os

    return os.environ.get(key) or None


def main() -> None:
    parser = argparse.ArgumentParser(description="磊毛阅读 · PC 端封面打标工具")
    parser.add_argument("--list", default=None, help="App 导出的收录清单 JSON（tag_table.json）；与 --cloud 至少给一个")
    parser.add_argument("--cloud", nargs="?", const=DEFAULT_CLOUD_TABLE_URL, default=None,
                        help=f"网络清单 URL（缺省 {DEFAULT_CLOUD_TABLE_URL}）：拉取云端表作为基底，与 --list 合并")
    parser.add_argument("--sources", default=None, help="书源文件/网址（legado shuyuan / App JSON 源 / MoonTV 配置，多个用 | 分隔）：PC 直接遍历抓 标题+封面")
    parser.add_argument("--src-pages", type=int, default=2, help="书源遍历每分类抓几页（默认 2）")
    parser.add_argument("--src-type", default="COMIC", choices=["COMIC", "NOVEL"], help="书源条目内容类型（MacCMS 固定 VIDEO）")
    parser.add_argument("--out", default="tag_table.json", help="输出路径（默认 ./tag_table.json）")
    parser.add_argument("--vocab", default=str(Path(__file__).resolve().parent / "vocab.json"), help="受控词表")
    parser.add_argument("--model-dir", default="models/wd14", help="模型缓存目录")
    parser.add_argument("--limit", type=int, default=0, help="本轮最多打标多少条（0 = 全部待打标）")
    parser.add_argument("--workers", type=int, default=6, help="封面下载并发数")
    parser.add_argument("--push", action="store_true", help="打标后推送到 GitHub（配合 --token）")
    parser.add_argument("--token", default=None, help="GitHub token（或环境变量 GH_TOKEN）")
    parser.add_argument("--repo", default="leimao2333/leimao-reader", help="目标仓库")
    parser.add_argument("--branch", default="main", help="目标分支")
    parser.add_argument("--path", default=DEFAULT_CLOUD_PATH, help="仓库内文件路径")
    args = parser.parse_args()
    sys.exit(run(args))


if __name__ == "__main__":
    main()
