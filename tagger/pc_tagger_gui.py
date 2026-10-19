#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""磊毛阅读 · PC 端封面打标工具（Web 界面版，v1.8.1.6）。

运行后在本机起一个 HTTP 服务并自动打开浏览器页面：遍历清单里**所有源**的作品
逐部打标，页面实时显示总体进度条、当前源、每源进度表与滚动日志，可中途停止。

v1.8.1.6：支持「网络源导入」—— 直接拉取 GitHub 云端打标表作为基底清单（可留空本地
清单）；导入书源文件 / 网址（legado shuyuan / App JSON 源 / MoonTV-MacCMS）由 PC
直接**遍历源站**抓取标题 + 封面（source_engine.py 精简规则引擎）；并按 MangaReader
的方式从书名解析 作者/汉化组/作品/会场 等元数据标签。

打包：pyinstaller --onefile --windowed --name leimao-pc-tagger pc_tagger_gui.py
"""

from __future__ import annotations

import json
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pc_tagger as core
import source_engine

# exe（PyInstaller onefile）里 __file__ 指向临时解包目录，取 exe 所在目录
if getattr(sys, "frozen", False):
    APP_DIR = Path(sys.executable).resolve().parent
else:
    APP_DIR = Path(__file__).resolve().parent
DEFAULT_OUT = str(APP_DIR / "tag_table.json")
DEFAULT_MODEL_DIR = str(APP_DIR / "wd14-model")


def resource_path(name: str) -> str:
    """内置资源定位：优先 exe 同目录（外置可替换），其次打包进 exe 的资源。"""
    external = APP_DIR / name
    if external.exists():
        return str(external)
    bundled = getattr(sys, "_MEIPASS", None)
    if bundled and (Path(bundled) / name).exists():
        return str(Path(bundled) / name)
    return str(external)

PAGE = """<!doctype html>
<html lang="zh"><head><meta charset="utf-8">
<title>磊毛阅读 · PC 端封面打标</title>
<style>
 body{font-family:"Microsoft YaHei",sans-serif;margin:24px;max-width:960px;color:#222}
 h1{font-size:20px} fieldset{border:1px solid #ccc;border-radius:8px;margin-bottom:14px}
 label{display:inline-block;margin:4px 8px 4px 0}
 input[type=text]{padding:4px 6px;border:1px solid #bbb;border-radius:4px}
 #list,#out{width:420px} #repo{width:220px} #path{width:280px} #token{width:160px}
 .row{margin:4px 0}
 button{padding:6px 14px;border:0;border-radius:6px;background:#4a6cf7;color:#fff;cursor:pointer}
 button:disabled{background:#999}
 button.stop{background:#d9534f}
 .bar{height:14px;background:#eee;border-radius:7px;overflow:hidden;margin:4px 0}
 .bar>div{height:100%;background:#4a6cf7;width:0%;transition:width .3s}
 table{border-collapse:collapse;width:100%;font-size:13px}
 td,th{border:1px solid #ddd;padding:4px 8px;text-align:left}
 #log{background:#111;color:#9f9;font:12px/1.5 Consolas,monospace;height:180px;
      overflow:auto;border-radius:6px;padding:8px;white-space:pre-wrap}
 .muted{color:#777;font-size:12px}
</style></head><body>
<h1>磊毛阅读 · PC 端封面打标</h1>

<fieldset><legend>配置</legend>
 <div class="row">收录清单：<input type="text" id="list"> <span class="muted">App「导出收录清单」得到的 leimao-tag-table.json 的完整路径</span></div>
 <div class="row">网络清单：<input type="text" id="cloud" style="width:480px" placeholder="https://cdn.jsdelivr.net/gh/leimao2333/leimao-reader@main/tagger/data/tag_table.json"> <span class="muted">拉云端表作为基底；两个都填则合并，留空本地则必填</span></div>
 <div class="row">书源导入：<input type="text" id="sources" style="width:440px" placeholder="shuyuan.json / MoonTV 配置 / https://…，多个用 | 分隔"> <span class="muted">PC 直接遍历源站抓标题+封面（legado 书源 / App JSON 源 / MacCMS）</span></div>
 <div class="row">
   遍历页数/分类：<input type="text" id="srcPages" value="2" style="width:40px">
   书源内容类型：<select id="srcType"><option value="COMIC">漫画</option><option value="NOVEL">小说</option></select>
   <span class="muted">MacCMS（视频站）固定按视频收录</span>
 </div>
 <div class="row">输出文件：<input type="text" id="out" value="__DEFAULT_OUT__"> <span class="muted">打标结果 JSON 的保存位置</span></div>
 <div class="row">
   模型目录：<input type="text" id="model" value="__DEFAULT_MODEL_DIR__" style="width:280px">
   <span class="muted">WD14 模型缓存（约 356MB，首次自动下载）</span>
   下载并发：<input type="text" id="workers" value="6" style="width:40px">
   本轮上限(0=全部)：<input type="text" id="limit" value="0" style="width:60px">
 </div>
 <div class="row">
   <label><input type="checkbox" id="push"> 打标后推送 GitHub</label>
   仓库：<input type="text" id="repo" value="leimao2333/leimao-reader">
   路径：<input type="text" id="path" value="tagger/data/tag_table.json">
   Token：<input type="text" id="token" placeholder="ghp_…（可选，也可用环境变量 GH_TOKEN）">
 </div>
 <div class="row">
   <button id="start" onclick="start()">开始遍历打标</button>
   <button id="stop" class="stop" onclick="stop()" disabled>停止</button>
   <span class="muted">视觉模型约 356MB 首次自动下载；标题解析（作者/汉化组/作品等）每次全量执行</span>
 </div>
</fieldset>

<fieldset><legend>总体进度</legend>
 <div class="bar"><div id="obar"></div></div>
 <div id="otext" class="row">0 / 0（命中 0）</div>
 <div id="stext" class="row"><b>当前源：—</b></div>
 <div class="bar"><div id="sbar" style="background:#38a169"></div></div>
</fieldset>

<fieldset><legend>各源进度</legend>
 <table id="sources"><tr><th>源</th><th>封面数</th><th>已打标</th><th>进度</th></tr></table>
</fieldset>

<fieldset><legend>日志</legend>
 <div id="log"></div>
</fieldset>

<script>
let timer=null;
function start(){
  fetch('/api/start',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({
      list:v('list'), cloud:v('cloud'), sources:v('sources'),
      srcPages:+v('srcPages')||2, srcType:v('srcType')||'COMIC',
      out:v('out'), model:v('model'), workers:+v('workers')||6,
      limit:+v('limit')||0, push:document.getElementById('push').checked,
      repo:v('repo'), path:v('path'), token:v('token')
    })}).then(r=>r.json()).then(j=>{ if(!j.ok) alert('启动失败：'+(j.error||'')); });
}
function stop(){ fetch('/api/stop',{method:'POST'}); }
function v(id){ return document.getElementById(id).value.trim(); }
function render(s){
  document.getElementById('obar').style.width=(s.total? s.done*100/s.total:100)+'%';
  document.getElementById('otext').textContent=s.done+' / '+s.total+'（命中 '+s.hit+'）';
  document.getElementById('stext').innerHTML='<b>当前源：'+(s.current||'—')+'</b>';
  const src=s.sources||{}; const names=Object.keys(src);
  const tb='<tr><th>源</th><th>封面数</th><th>已打标</th><th>进度</th></tr>'+
    names.map(n=>{const x=src[n];const p=x.total?Math.round(x.done*100/x.total):100;
      return '<tr><td>'+n+'</td><td>'+x.total+'</td><td>'+x.hit+'</td><td>'+x.done+'/'+x.total+'（'+p+'%）</td></tr>';}).join('');
  document.getElementById('sources').innerHTML=tb;
  document.getElementById('sbar').style.width=(s.total? s.done*100/s.total:100)+'%';
  const log=document.getElementById('log');
  if(s.logVersion!==log.dataset.v){ log.textContent=s.logs.join('\\n'); log.scrollTop=log.scrollHeight; log.dataset.v=s.logVersion; }
  document.getElementById('start').disabled=s.running;
  document.getElementById('stop').disabled=!s.running;
  if(s.finished && timer){ clearInterval(timer); timer=null; }
}
function poll(){ fetch('/api/status').then(r=>r.json()).then(render).catch(()=>{}); }
poll(); timer=setInterval(poll,800);
</script></body></html>"""

PAGE = PAGE.replace("__DEFAULT_OUT__", DEFAULT_OUT).replace("__DEFAULT_MODEL_DIR__", DEFAULT_MODEL_DIR)


class State:
    """打标任务的共享状态（worker 写，HTTP 线程读，lock 保护）。"""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.logs: list[str] = []
        self.log_version = 0
        self.running = False
        self.finished = False
        self.done = 0
        self.total = 0
        self.hit = 0
        self.current = ""
        self.sources: dict[str, dict] = {}
        self.stop_event = threading.Event()

    def log(self, message: str) -> None:
        with self.lock:
            self.logs.append(f"[{time.strftime('%H:%M:%S')}] {message}")
            self.log_version += 1

    def snapshot(self) -> dict:
        with self.lock:
            return {
                "running": self.running,
                "finished": self.finished,
                "done": self.done,
                "total": self.total,
                "hit": self.hit,
                "current": self.current,
                "sources": {k: dict(v) for k, v in self.sources.items()},
                "logs": list(self.logs[-400:]),
                "logVersion": self.log_version,
            }


STATE = State()


class TaggingJob(threading.Thread):
    def __init__(self, cfg: dict):
        super().__init__(daemon=True)
        self.cfg = cfg

    def run(self) -> None:
        def log(msg: str) -> None:
            STATE.log(msg)

        # v1.8.1.6：网络清单为基底，本地清单合并补充；三者（含书源）都留空才报错
        cloud_url = str(self.cfg.get("cloud") or "").strip()
        list_path = str(self.cfg.get("list") or "").strip()
        items: list[dict] = []
        try:
            if cloud_url:
                if not cloud_url.startswith("http"):
                    cloud_url = core.DEFAULT_CLOUD_TABLE_URL
                log(f"拉取网络清单：{cloud_url}")
                cloud_items = core.fetch_cloud_table(cloud_url)
                log(f"网络清单条目：{len(cloud_items)}")
                items = cloud_items
            if list_path:
                local_items = json.loads(
                    Path(list_path).read_text(encoding="utf-8"),
                ).get("items", [])
                if local_items:
                    log(f"本地清单条目：{len(local_items)}")
                else:
                    log("本地清单条目：0（文件里没有 items 字段——请确认是 App「导出收录清单」得到的 leimao-tag-table.json，而不是备份文件）")
                items = core.merge_items(items, local_items)
        except Exception as exc:  # noqa: BLE001
            STATE.log(f"清单加载失败：{exc}")
            STATE.running, STATE.finished = False, True
            return

        # 网络源遍历（v1.8.1.6）：书源文件 / 网址 → 逐源抓 标题+封面 汇入清单
        src_specs = [s.strip() for s in str(self.cfg.get("sources") or "").split("|") if s.strip()]
        if src_specs:
            src_pages = int(self.cfg.get("srcPages") or 2)
            src_type = str(self.cfg.get("srcType") or "COMIC")
            for spec in src_specs:
                if STATE.stop_event.is_set():
                    break
                try:
                    sources = source_engine.load_sources_from_text_or_url(spec, src_type, log)
                except Exception as exc:  # noqa: BLE001
                    log(f"书源装载失败 {spec[:60]}：{exc}")
                    continue
                log(f"书源装载成功：{len(sources)} 个源（每分类抓 {src_pages} 页）")
                for source in sources:
                    if STATE.stop_event.is_set():
                        break
                    log(f"遍历源：{source.name}（{type(source).__name__}）")
                    try:
                        found = source.explore(src_pages, log)
                    except Exception as exc:  # noqa: BLE001
                        log(f"  ✗ 遍历失败 {source.name}：{exc}")
                        found = []
                    with STATE.lock:
                        STATE.sources[f"[遍历] {source.name}"] = {
                            "total": len(found), "done": len(found), "hit": len(found),
                        }
                    if found:
                        items = core.merge_items(items, found)
                        log(f"  {source.name}：收录 {len(found)} 部，合并后共 {len(items)} 条")

        if not items:
            STATE.log("网络清单、本地清单与书源导入至少要提供一个")
            STATE.running, STATE.finished = False, True
            return
        log(f"合并后条目：{len(items)}")

        # 标题解析打标（MangaReader 式：作者/汉化组/作品/会场/平台/其他，origin=文本）
        try:
            title_hit = core.apply_title_tags(items)
            log(f"标题解析命中：{title_hit} 部（作者/汉化组/作品等，置信度 {core.TEXT_CONFIDENCE}）")
        except Exception as exc:  # noqa: BLE001
            log(f"标题解析失败（跳过）：{exc}")

        try:
            vocab = core.Vocab.load(Path(self.cfg["vocab"]))
        except Exception as exc:  # noqa: BLE001
            STATE.log(f"词表加载失败：{exc}")
            STATE.running, STATE.finished = False, True
            return
        log(f"受控词表：{len(vocab.alias_to_canonical)} 个写法")

        def has_visual(item: dict) -> bool:
            return any(t.get("origin") == core.ORIGIN_VISUAL for t in item.get("tags", []))

        pending = [it for it in items if it.get("cover") and not has_visual(it)]
        if self.cfg["limit"] > 0:
            pending = pending[: self.cfg["limit"]]
        by_source: dict[str, list[dict]] = {}
        for item in pending:
            by_source[item.get("sourceName") or item.get("sourceId") or "未知源"].append(item)
        with STATE.lock:
            STATE.total = len(pending)
            STATE.done = 0
            STATE.hit = 0
            STATE.sources = {
                name: {"total": len(items_), "done": 0, "hit": 0}
                for name, items_ in by_source.items()
            }
        log(f"待视觉打标：{len(pending)} 部，分布在 {len(by_source)} 个源")

        model = None
        if pending:
            try:
                log("准备模型（首次下载约 356MB，走 hf-mirror）…")
                model_path, tags_csv = core.ensure_model(Path(self.cfg["model"]))
                log("加载 ONNX 模型…")
                model = core.Wd14Model(model_path, tags_csv)
            except Exception as exc:  # noqa: BLE001
                log(f"模型准备失败：{exc}")
                STATE.running, STATE.finished = False, True
                return

        stopped = False
        for source_name, source_items in by_source.items():
            if STATE.stop_event.is_set():
                stopped = True
                break
            with STATE.lock:
                STATE.current = source_name
            for index, item in enumerate(source_items):
                if STATE.stop_event.is_set():
                    stopped = True
                    break
                title = (item.get("title") or "")[:36]
                try:
                    image_bytes = core.download_cover(item["cover"])
                    raw = model.tag(image_bytes) if image_bytes else []
                except Exception as exc:  # noqa: BLE001
                    log(f"  ✗ {title}：{exc}")
                    raw = []
                merged: dict[str, dict] = {t["name"]: dict(t) for t in item.get("tags", [])}
                hit = 0
                for danbooru_name, prob in raw:
                    controlled = vocab.to_controlled(danbooru_name)
                    if not controlled:
                        continue
                    confidence = max(core.CONF_MIN, min(core.CONF_MAX, int(prob * 100)))
                    merged[controlled] = core.merge_entry(
                        merged.get(controlled),
                        {"name": controlled, "category": "题材", "confidence": confidence, "origin": core.ORIGIN_VISUAL},
                    )
                    hit += 1
                with STATE.lock:
                    src = STATE.sources[source_name]
                    src["done"] = index + 1
                    STATE.done += 1
                    if hit:
                        src["hit"] += 1
                        STATE.hit += 1
                        item["tags"] = sorted(merged.values(), key=lambda t: -t["confidence"])
                    log(f"  {'✓ ' + title + f'（{hit} 个受控标签）' if hit else '– ' + title + '：无可映射标签'}")

        tagged = sum(
            1 for it in items
            if any(t.get("origin") == core.ORIGIN_VISUAL for t in it.get("tags", []))
        )
        try:
            Path(self.cfg["out"]).write_text(
                json.dumps({"version": 1, "generatedAt": int(time.time() * 1000), "source": "pc", "items": items},
                           ensure_ascii=False, indent=1),
                encoding="utf-8",
            )
            log(f"输出已写入：{self.cfg['out']}（累计视觉打标 {tagged} 部）")
        except Exception as exc:  # noqa: BLE001
            log(f"输出写入失败：{exc}")

        if self.cfg["push"] and not stopped:
            try:
                log("推送到 GitHub…")
                core.push_to_github_simple(
                    token=self.cfg["token"], repo=self.cfg["repo"], branch="main",
                    path=self.cfg["path"],
                    content=Path(self.cfg["out"]).read_text(encoding="utf-8"),
                    log=log,
                )
            except Exception as exc:  # noqa: BLE001
                log(f"推送失败：{exc}")
        elif self.cfg["push"] and stopped:
            log("已停止：跳过推送（跑完一轮再推）")

        log("== 已停止 ==" if stopped else "== 完成 ==")
        STATE.running, STATE.finished = False, True


class Handler(BaseHTTPRequestHandler):

    def _send(self, code: int, body: bytes, ctype: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if self.path in ("/", "/index.html"):
            self._send(200, PAGE.encode("utf-8"), "text/html; charset=utf-8")
        elif self.path == "/api/status":
            self._send(200, json.dumps(STATE.snapshot(), ensure_ascii=False).encode("utf-8"),
                       "application/json; charset=utf-8")
        else:
            self._send(404, b"not found", "text/plain")

    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("content-length") or 0)
        body = self.rfile.read(length)
        if self.path == "/api/start":
            if STATE.running:
                self._send(200, '{"ok":false,"error":"任务正在运行"}'.encode("utf-8"), "application/json; charset=utf-8")
                return
            try:
                cfg = json.loads(body or b"{}")
                has_list = bool(str(cfg.get("list", "")).strip())
                has_cloud = bool(str(cfg.get("cloud", "")).strip())
                has_sources = bool(str(cfg.get("sources", "")).strip())
                # out / model 缺省自动补默认值（exe 同目录），不再报错
                if not str(cfg.get("out", "")).strip():
                    cfg["out"] = DEFAULT_OUT
                if not str(cfg.get("model", "")).strip():
                    cfg["model"] = DEFAULT_MODEL_DIR
                if not has_list and not has_cloud and not has_sources:
                    raise ValueError("网络清单、本地清单与书源导入至少要提供一个")
                if has_list and not Path(str(cfg["list"])).exists():
                    raise ValueError(f"清单文件不存在：{cfg['list']}")
            except Exception as exc:  # noqa: BLE001
                self._send(200, json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False)
                           .encode("utf-8"), "application/json; charset=utf-8")
                return
            with STATE.lock:
                STATE.logs = []
                STATE.log_version += 1
                STATE.finished = False
                STATE.running = True
                STATE.stop_event.clear()
            cfg["vocab"] = resource_path("vocab.json")
            TaggingJob(cfg).start()
            self._send(200, b'{"ok":true}', "application/json; charset=utf-8")
        elif self.path == "/api/stop":
            STATE.stop_event.set()
            self._send(200, b'{"ok":true}', "application/json; charset=utf-8")
        else:
            self._send(404, b"not found", "text/plain")

    def log_message(self, *args) -> None:  # 静默默认访问日志
        pass


def main() -> None:
    port = 8765
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    url = f"http://127.0.0.1:{port}/"
    print(f"磊毛阅读 PC 打标工具已启动：{url} （关闭本窗口即退出）")
    webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
