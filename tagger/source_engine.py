#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""磊毛阅读 · PC 端网络源遍历引擎（v1.8.1.6）。

在 PC 上直接导入「网络源」并遍历，抓取条目的 标题 + 封面 汇入打标清单 ——
不必先从手机导出收录清单。支持三类源：

1. **legado / shuyuan 书源**（JSON 数组，含 `bookSourceUrl` + `ruleExplore`）：
   解析 `exploreUrl`（逐行 `分类名::地址`）与 `ruleExplore` 规则。
2. **App JSON 源规范**（`{name, baseUrl, search:{url, list, fields}}`）：
   用 search 规则逐页抓取（URL 需要 {{keyword}} 的源跳过）。
3. **MoonTV / MacCMS 采集站**（`{api_site: {key: {name, api}}}`）：
   `ac=videolist` 接口翻页抓取。

规则引擎为精简实现：JSONPath 子集（`$.a.b[0]` / `$[*]`）+ CSS 选择器子集
（`selector@attr`、`class.x` / `id.x` / `tag.x`，尾部 `.N` 取第 N 个），
`{{page}}` 与纯算术 `{{(page-1)*20}}` 占位符可渲染；`@js:` 等复杂规则跳过。
JS 源 / Venera 源无法在 PC 端评估，遍历时记录日志后跳过。
"""

from __future__ import annotations

import json
import re
from urllib.parse import urljoin, urlparse, parse_qs

import requests
from bs4 import BeautifulSoup

USER_AGENT = "leimao-pc-tagger/1.6"
TIMEOUT = 25

_TRUTHY_PAGE_EXPR = re.compile(r"^[\d\s()+\-*%]*$")


def fetch_text(url: str, timeout: int = TIMEOUT, headers: dict | None = None) -> str:
    merged = {"User-Agent": USER_AGENT}
    if headers:
        merged.update({k: v for k, v in headers.items() if isinstance(v, str)})
    resp = requests.get(url, timeout=timeout, headers=merged)
    resp.raise_for_status()
    if not resp.encoding or resp.encoding.lower() == "iso-8859-1":
        resp.encoding = resp.apparent_encoding or "utf-8"
    return resp.text


def fetch_json(url: str, timeout: int = TIMEOUT, headers: dict | None = None):
    return json.loads(fetch_text(url, timeout, headers))


# ---------------------------------------------------------------- URL 渲染


def render_url(template: str, page: int, keyword: str = "") -> str | None:
    """渲染 URL 模板：{{page}} / {{searchPage}} / 纯算术 {{(page-1)*20}}。

    其余 {{表达式}}（需要 JS 求值）渲染失败时按空串丢弃；含 {{keyword}} 的
    模板在遍历场景没有意义，返回 None 让调用方跳过。
    """
    if "{{keyword}}" in template or "{{key}}" in template:
        return None
    out = template.replace("{{searchPage}}", str(page)).replace("{{page}}", str(page))

    def _eval(m: re.Match) -> str:
        expr = m.group(1).replace("page", str(page)).replace("searchPage", str(page))
        if not _TRUTHY_PAGE_EXPR.match(expr):
            return ""
        try:
            return str(int(eval(expr, {"__builtins__": {}}, {})))  # noqa: S307 表达式已白名单
        except Exception:  # noqa: BLE001
            return ""

    return re.sub(r"\{\{([^{}]+)\}\}", _eval, out)


# ---------------------------------------------------------------- 规则求值


def jsonpath_get(obj, expr: str):
    """JSONPath 子集：`$.a.b[0]`、`$[*].x`、`$.a[*]`。返回 list（可能为空）。"""
    if not expr.startswith("$"):
        return None
    path = expr[1:]
    tokens: list = []
    for m in re.finditer(r"\.([A-Za-z0-9_\-]+)|\[(\*|\d+)\]", path):
        if m.group(1) is not None:
            tokens.append(("k", m.group(1)))
        else:
            tokens.append(("idx", m.group(2)))
    cur: list = [obj]
    for kind, val in tokens:
        nxt: list = []
        for node in cur:
            if kind == "k":
                if isinstance(node, dict) and val in node:
                    nxt.append(node[val])
            else:
                if val == "*" and isinstance(node, list):
                    nxt.extend(node)
                elif isinstance(node, list) and val != "*":
                    i = int(val)
                    if -len(node) <= i < len(node):
                        nxt.append(node[i])
        cur = nxt
    return cur


def _css_selector(token: str) -> str:
    """legado 简写 → CSS：`class.x` → `.x`、`id.x` → `#x`、`tag.div` → `div`。"""
    if token.startswith("class."):
        return "." + token[6:]
    if token.startswith("id."):
        return "#" + token[3:]
    if token.startswith("tag."):
        return token[4:]
    if token.startswith("@css:"):
        return token[5:]
    return token


def _split_index(token: str) -> tuple[str, int | None]:
    """拆尾部索引：`tag.h3.0` → (`tag.h3`, 0)；`class.x.y` → (`class.x.y`, None)。"""
    m = re.match(r"^(.+)\.(-?\d+)$", token)
    if m:
        return m.group(1), int(m.group(2))
    return token, None


def css_select_chain(node, expr: str) -> list:
    """链式选择：`id.x.0@tag.li` → 先 #x 取第 0 个，再在其中选全部 li。

    返回全部匹配节点（列表规则用）；字段规则取第一个。
    """
    parts = [p.strip() for p in expr.split("@") if p.strip()]
    if not parts:
        return []
    cur: list = [node]
    for token in parts:
        base, index = _split_index(token)
        sel = _css_selector(base)
        nxt: list = []
        for c in cur:
            if c is None:
                continue
            try:
                found = c.select(sel) if sel else [c]
            except Exception:  # noqa: BLE001 选择器语法超出 soupsieve 支持范围
                found = []
            if not found:
                continue
            if index is not None and -len(found) <= index < len(found):
                nxt.append(found[index])
            else:
                nxt.extend(found)
        cur = nxt
        if not cur:
            break
    return cur


_KNOWN_ATTRS = ("text", "html", "all", "href", "src", "alt", "title", "content",
                "value", "data-src", "data-original", "data-lazy-src", "url")


def css_extract(node, expr: str) -> str:
    """提取表达式：`selector@attr`（attr ∈ text/html/href/src/… 或属性名）。

    `@` 可链式：`class.item@tag.a@href`；尾部 `.N` 表示取第 N 个（0 起）。
    """
    parts = [p.strip() for p in expr.split("@") if p.strip()]
    if not parts:
        return ""
    last = parts[-1]
    if last.lower() in _KNOWN_ATTRS or (not last.startswith(("class.", "id.", "tag.", "@css", ".", "#"))
                                        and not re.match(r"^[A-Za-z][\w\-]*(\[|\.)", last)):
        attr = parts.pop()
    else:
        attr = "text"
    nodes = css_select_chain(node, "@".join(parts))
    if not nodes:
        return ""
    cur = nodes[0]
    a = attr.lower()
    if a == "text":
        return cur.get_text(strip=True)
    if a in ("html", "all"):
        return cur.decode_contents()
    if hasattr(cur, "get"):
        return cur.get(attr) or cur.get(a) or ""
    return ""


def looks_jsonpath(rule: str) -> bool:
    return rule.strip().startswith("$")


def looks_js_rule(rule: str) -> bool:
    return rule.strip().startswith(("@js:", "<js>", "@JavaScript"))


def extract_list(body: str, list_rule: str, field_rules: dict, base_url: str) -> list[dict]:
    """从响应体提取列表：JSONPath 或 CSS 两条路，字段规则逐个求值。"""
    items: list[dict] = []
    if list_rule and looks_jsonpath(list_rule):
        try:
            data = json.loads(body)
        except Exception:  # noqa: BLE001
            return items
        nodes = jsonpath_get(data, list_rule) or []
        for node in nodes:
            fields: dict[str, str] = {}
            for name, rule in field_rules.items():
                if looks_jsonpath(rule):
                    vals = jsonpath_get(node, rule)
                    v = vals[0] if vals else ""
                elif looks_js_rule(rule):
                    v = ""
                else:
                    v = css_extract(node if not isinstance(node, str) else BeautifulSoup(node, "html.parser"), rule)
                fields[name] = v if isinstance(v, str) else json.dumps(v, ensure_ascii=False)
            items.append(fields)
        return items

    if not list_rule:
        return items
    soup = BeautifulSoup(body, "html.parser")
    if looks_js_rule(list_rule):
        return items
    nodes = css_select_chain(soup, list_rule)
    for node in nodes:
        fields = {}
        for name, rule in field_rules.items():
            fields[name] = "" if looks_js_rule(rule) else css_extract(node, rule)
        items.append(fields)
    return items


def abs_url(base: str, value: str) -> str:
    v = (value or "").strip()
    if not v or v.startswith(("http://", "https://", "data:")):
        return v
    return urljoin(base, v)


# ---------------------------------------------------------------- 源模型


class TraversedItem(dict):
    """遍历所得条目：sourceId / itemId / itemType / title / cover / sourceName。"""


class BaseSource:
    name = "未知源"
    source_id = ""
    item_type = "COMIC"

    def explore(self, pages: int, log) -> list[TraversedItem]:
        raise NotImplementedError


class LegadoSource(BaseSource):
    """legado / shuyuan 书源：exploreUrl + ruleExplore。"""

    def __init__(self, obj: dict, item_type: str = "COMIC"):
        self.obj = obj
        self.source_id = (obj.get("bookSourceUrl") or obj.get("sourceUrl") or "").strip()
        self.name = (obj.get("bookSourceName") or obj.get("sourceName")
                     or urlparse(self.source_id).netloc or "legado源").strip()
        self.item_type = item_type
        self.base = self.source_id
        explore_raw = (obj.get("exploreUrl") or "").strip()
        self.categories = _parse_legado_explore_url(explore_raw)
        self.rule_explore = obj.get("ruleExplore") or {}
        # legado 的 header 可能是 JSON 字符串，也可能是对象
        h = obj.get("header")
        if isinstance(h, str):
            try:
                h = json.loads(h)
            except Exception:  # noqa: BLE001
                h = {}
        self.search_headers = h if isinstance(h, dict) else {}

    def explore(self, pages: int, log) -> list[TraversedItem]:
        if not self.categories:
            log(f"  跳过：exploreUrl 为空或含 @js（{self.name}）")
            return []
        if not self.rule_explore.get("bookList"):
            log(f"  跳过：ruleExplore.bookList 缺失（{self.name}）")
            return []
        field_map = {
            "title": self.rule_explore.get("name") or "",
            "cover": self.rule_explore.get("coverUrl") or "",
            "author": self.rule_explore.get("author") or "",
            "detailUrl": self.rule_explore.get("bookUrl") or "",
        }
        found: dict[str, TraversedItem] = {}
        for title, cat_tpl in self.categories:
            has_page = "{{" in cat_tpl
            for page in range(1, (pages if has_page else 1) + 1):
                url = render_url(cat_tpl, page)
                if not url:
                    break
                url = abs_url(self.base, url)
                try:
                    body = fetch_text(url, headers=self.search_headers)
                except Exception as exc:  # noqa: BLE001
                    log(f"  ✗ {self.name}「{title}」第 {page} 页：{exc}")
                    break
                rows = extract_list(body, self.rule_explore.get("bookList", ""), field_map, url)
                fresh = 0
                for row in rows:
                    t = (row.get("title") or "").strip()
                    cover = abs_url(url, row.get("cover") or "")
                    detail = abs_url(url, row.get("detailUrl") or "")
                    if not t or not cover:
                        continue
                    key = detail or t
                    if key in found:
                        continue
                    found[key] = TraversedItem(
                        sourceId=self.source_id, itemId=detail or t, itemType=self.item_type,
                        title=t, cover=cover, sourceName=self.name,
                    )
                    fresh += 1
                log(f"  {self.name}「{title}」第 {page} 页：+{fresh}")
                if page > 1 and not rows:
                    break
        return list(found.values())


def _parse_legado_explore_url(raw: str) -> list[tuple[str, str]]:
    """解析 exploreUrl：逐行 `分类名::地址`；含 @js / <js> 的返回空（PC 端不支持）。"""
    if not raw or raw.startswith(("@js:", "<js>", "@json:", "[")):
        return []
    cats: list[tuple[str, str]] = []
    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue
        if "::" in line:
            t, u = line.split("::", 1)
        elif "\t" in line:
            t, u = line.split("\t", 1)
        else:
            t, u = line, line
        t, u = t.strip(), u.strip()
        if u and not u.startswith(("@js", "<js")):
            cats.append((t or u, u))
    return cats


class AppJsonSource(BaseSource):
    """App JSON 源规范：search.url 逐页抓取（不含 {{keyword}} 时才可用）。"""

    def __init__(self, obj: dict, item_type: str = "COMIC"):
        self.obj = obj
        self.source_id = (obj.get("baseUrl") or obj.get("name") or "").strip()
        self.name = (obj.get("name") or "JSON源").strip()
        self.item_type = item_type
        self.base = obj.get("baseUrl") or ""
        search = obj.get("search") or {}
        self.url_tpl = search.get("url") or ""
        self.list_rule = search.get("list") or ""
        self.fields = search.get("fields") or {}
        self.headers = search.get("headers") or {}

    def explore(self, pages: int, log) -> list[TraversedItem]:
        if not self.url_tpl:
            log(f"  跳过：search.url 缺失（{self.name}）")
            return []
        if "{{keyword}}" in self.url_tpl or "{{key}}" in self.url_tpl:
            log(f"  跳过：search.url 需要 {{keyword}}，PC 端遍历不支持（{self.name}）")
            return []
        if self.list_rule.startswith("@js"):
            log(f"  跳过：search.list 为 @js 规则（{self.name}）")
            return []
        found: dict[str, TraversedItem] = {}
        has_page = "{{" in self.url_tpl
        for page in range(1, (pages if has_page else 1) + 1):
            url = render_url(self.url_tpl, page)
            if not url:
                break
            url = abs_url(self.base, url)
            try:
                body = fetch_text(url, headers=self.headers)
            except Exception as exc:  # noqa: BLE001
                log(f"  ✗ {self.name} 第 {page} 页：{exc}")
                break
            rows = extract_list(body, self.list_rule, self.fields, url)
            fresh = 0
            for row in rows:
                t = (row.get("title") or row.get("name") or "").strip()
                cover = abs_url(url, row.get("cover") or row.get("image") or "")
                detail = abs_url(self.base, row.get("id") or row.get("url") or "")
                if not t or not cover:
                    continue
                key = detail or t
                if key in found:
                    continue
                found[key] = TraversedItem(
                    sourceId=self.source_id, itemId=detail or t, itemType=self.item_type,
                    title=t, cover=cover, sourceName=self.name,
                )
                fresh += 1
            log(f"  {self.name} 第 {page} 页：+{fresh}")
            if not rows:
                break
        return list(found.values())


class MacCmsSource(BaseSource):
    """MoonTV / MacCMS 采集站：api.php?ac=videolist 翻页。"""

    def __init__(self, name: str, api: str, detail: str = ""):
        self.name = name
        self.source_id = api
        self.api = api.rstrip("?")
        self.detail = detail.rstrip("/")
        self.item_type = "VIDEO"

    def explore(self, pages: int, log) -> list[TraversedItem]:
        found: dict[str, TraversedItem] = {}
        for page in range(1, pages + 1):
            url = f"{self.api}?ac=videolist&pg={page}"
            try:
                data = fetch_json(url)
            except Exception as exc:  # noqa: BLE001
                log(f"  ✗ {self.name} 第 {page} 页：{exc}")
                break
            rows = data.get("list") or []
            fresh = 0
            for row in rows:
                t = (row.get("vod_name") or "").strip()
                pic = (row.get("vod_pic") or "").strip()
                if not t or not pic:
                    continue
                vid = str(row.get("vod_id") or t)
                detail = f"{self.detail}?p={vid}" if self.detail else vid
                key = detail or t
                if key in found:
                    continue
                found[key] = TraversedItem(
                    sourceId=self.source_id, itemId=vid, itemType="VIDEO",
                    title=t, cover=pic, sourceName=self.name,
                )
                fresh += 1
            log(f"  {self.name} 第 {page} 页：+{fresh}")
            if page >= int(data.get("page", {}).get("pagecount", 1) if isinstance(data.get("page"), dict) else 1):
                break
            if not rows:
                break
        return list(found.values())


# ---------------------------------------------------------------- 装载


def load_sources(text: str, item_type: str = "COMIC") -> list[BaseSource]:
    """解析源文本（文件内容 / 网址响应）为源对象列表。"""
    raw = text.strip()
    root = json.loads(raw)
    sources: list[BaseSource] = []
    if isinstance(root, dict) and isinstance(root.get("api_site"), dict):
        for key, val in root["api_site"].items():
            if isinstance(val, dict) and val.get("api"):
                sources.append(MacCmsSource(
                    name=(val.get("name") or key).strip(), api=val["api"].strip(),
                    detail=(val.get("detail") or "").strip(),
                ))
            elif isinstance(val, str) and val.startswith("http"):
                sources.append(MacCmsSource(name=key.strip(), api=val.strip()))
        return sources
    if isinstance(root, dict):
        root = [root]
    if not isinstance(root, list):
        return sources
    for obj in root:
        if not isinstance(obj, dict):
            continue
        if "bookSourceUrl" in obj or "ruleExplore" in obj or "ruleSearch" in obj:
            sources.append(LegadoSource(obj, item_type))
        elif "baseUrl" in obj and "search" in obj:
            sources.append(AppJsonSource(obj, item_type))
    return sources


def load_sources_from_text_or_url(spec: str, item_type: str, log) -> list[BaseSource]:
    """装载一个书源入口：URL（http 开头）或本地文件路径。"""
    spec = spec.strip()
    if spec.startswith("http"):
        log(f"下载书源：{spec}")
        text = fetch_text(spec)
    else:
        text = open(spec, encoding="utf-8").read()
    return load_sources(text, item_type)
