#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""磊毛阅读 · PC 端封面打标工具（Web 界面版，v1.8.1.6）。

运行后在本机起一个 HTTP 服务并自动打开浏览器页面：遍历清单里**所有源**的作品
逐部打标，页面实时显示总体进度条、当前源、每源进度表与滚动日志，可中途停止。

打包：pyinstaller --onefile --windowed --name leimao-pc-tagger pc_tagger_gui.py
"""

from __future__ import annotations

import json
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pc_tagger as core

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
 <div class="row">输出文件：<input type="text" id="out"></div>
 <div class="row">
   模型目录：<input type="text" id="model" style="width:200px">
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
   <span class="muted">模型约 356MB，首次自动从 hf-mirror 下载（仅一次）</span>
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
      list:v('list'), out:v('out'), model:v('model'), workers:+v('workers')||6,
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

        try:
            items = json.loads(Path(self.cfg["list"]).read_text(encoding="utf-8")).get("items", [])
        except Exception as exc:  # noqa: BLE001
            STATE.log(f"清单读取失败：{exc}")
            STATE.running, STATE.finished = False, True
            return
        log(f"清单条目：{len(items)}")
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
                required = ["list", "out", "model"]
                missing = [k for k in required if not str(cfg.get(k, "")).strip()]
                if missing:
                    raise ValueError(f"缺少配置：{', '.join(missing)}")
                if not Path(str(cfg["list"])).exists():
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
            cfg["vocab"] = str(Path(__file__).resolve().parent / "vocab.json")
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
