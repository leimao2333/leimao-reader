"""本地苹果 CMS（maccms10）采集站，给模拟器提供离线可用的影视源。

模拟器没有外网，远端采集站全部不可达，影视首页 / 电影 / 动漫 / 短剧频道
会一直是空白，没法联调。本脚本在宿主起一个假采集站，通过 `10.0.2.2:<port>`
（Android 模拟器到宿主的固定桥接地址）暴露给 App。

覆盖库里已有的演示源地址（见 moontv_test.json 与 App 内置演示源）：
    /api.php/provide/vod            → 本地演示源
    /a/api.php/provide/vod          → 演示源A
    /b/api.php/provide/vod          → 演示源B
    /c/api.php/provide/vod          → 演示源C
    /api1.php  /api2.php  /api3.php → 测试源一 / 二 / 三（moontv_test.json）

用法：
    python server.py [port]        # 默认 8899

自检：
    curl --noproxy "*" "http://127.0.0.1:8899/api1.php?ac=list&pg=1"

调试要点：
- 请求日志直接打到 stdout，可据此判断「切 Tab / 返回时 App 有没有重新发请求」。
- 封面由本地生成纯色 PNG（`/pic/...`），每条数据颜色不同，截图能肉眼定位链路。
- 片名带 `·T1` / `·T2` / `·T3` 后缀，标出它来自哪个采集接口，便于验证跨源聚合。
"""

import json
import os
import struct
import sys
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.abspath(__file__))

# 分类：(type_id, type_name, parent_id)。parent_id == 0 表示一级类型。
#
# 刻意做成**两级** —— 苹果 CMS 的 `class[].type_pid` 本来就有父子层级，
# App 的影视首页据此渲染「类型 → 分类」二级下拉，本地站点要有二级分类才验得了。
# 一级名里的「电影 / 动漫 / 短剧」要能命中 App 的频道关键词；
# 二级分类（动作片 / 喜剧片 / 欧美剧）只在首页的「分类」那一级下拉里出现。
CATEGORIES = [
    (1, "电影", 0),
    (11, "动作片", 1),
    (12, "喜剧片", 1),
    (2, "连续剧", 0),
    (21, "欧美剧", 2),
    (3, "综艺", 0),
    (4, "动漫", 0),
    (5, "短剧", 0),
    # 「电影片」是「电影」的**别名**：归位到同一个类型，不该在「类型」下拉里另起一项
    (98, "电影片", 0),
    # 站内栏目 / 套图社这类非影视分类：归不进规范类型，**该进「其他」**
    # （v1.6.7.0 曾直接丢弃，v1.6.8.0 起收进兜底类型，一个都不少）
    (99, "爱蜜社", 0),
    # 未归类的一级分类 + 它的未归类子分类：父子都该拍平进「其他」
    (96, "卫视", 0),
    (97, "直播", 0),
    (95, "NBA直播", 97),
]

# 每个分类多少条（够首页一排 + 电影频道翻 2 页）
PER_CATEGORY = 24
PAGE_SIZE = 12

# 失效线路用的端口：这上面没有服务 —— 连接被立刻拒绝，
# 用来验收「记忆的源失效了 / 当前线路不通 → 自动优先有效源」（v1.6.9.0）。
DEAD_PORT = 9998

# 哪一部片子做成「线路二挂掉、线路一还能播」：
# 详情页会把**集数最多的那条**（这里是线路二，2 集）交给播放器，
# 于是进播放页必然先撞上死线路 —— 正好用来验收自动优先有效源。
# 只改一部片子，避免影响首页分区等其他验收。
BROKEN_SECOND_LINE = (1, 1)  # 电影·01

# 可播的 mp4 素材（H.264）。找不到就退回原来的空 m3u8 占位。
MEDIA_MP4 = next((
    p for p in (
        os.path.join(HERE, "assets", "demo.mp4"),
        os.path.normpath(os.path.join(HERE, "..", "..", "..", "testsite", "assets", "video", "test.mp4")),
    ) if os.path.exists(p)
), None)

# 各接口的「源标记」，写进片名便于肉眼区分聚合来源
SITE_TAGS = {
    "/api1.php": "T1",
    "/api2.php": "T2",
    "/api3.php": "T3",
    "/api.php/provide/vod": "本地",
    "/a/api.php/provide/vod": "A",
    "/b/api.php/provide/vod": "B",
    "/c/api.php/provide/vod": "C",
}


def _png(width: int, height: int, rgb) -> bytes:
    """极简 PNG 编码（纯色），避免依赖 Pillow。"""
    row = b"\x00" + bytes(rgb) * width
    raw = row * height

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 6))
        + chunk(b"IEND", b"")
    )


_PNG_CACHE = {}


def poster_png(seed: int) -> bytes:
    if seed not in _PNG_CACHE:
        r = 60 + (seed * 37) % 180
        g = 60 + (seed * 91) % 180
        b = 60 + (seed * 53) % 180
        _PNG_CACHE[seed] = _png(120, 180, (r, g, b))
    return _PNG_CACHE[seed]


def site_tag(path: str) -> str:
    for prefix, tag in SITE_TAGS.items():
        if path.endswith(prefix):
            return tag
    return "X"


def make_items(tag: str, type_id: int, type_name: str, with_play: bool):
    items = []
    for n in range(1, PER_CATEGORY + 1):
        vod_id = type_id * 1000 + n
        if type_name in ("连续剧", "动漫", "短剧"):
            remarks = f"更新至第{n}集"
            episodes = n % 12 + 3
        else:
            remarks = "HD国语"
            episodes = 1
        broken = (type_id, n) == BROKEN_SECOND_LINE
        # 常规：线路一 / 线路二都是空 m3u8 占位（够验链路，但播放器会报错）
        first_ext = "mp4" if broken else "m3u8"
        play_url = "#".join(
            f"第{i:02d}集$http://10.0.2.2:8899/media/{'real_' if broken else ''}{vod_id}_{i}.{first_ext}"
            for i in range(1, episodes + 1)
        )
        item = {
            "vod_id": vod_id,
            "vod_name": f"{type_name}·{n:02d}·{tag}",
            "vod_pic": f"http://10.0.2.2:8899/pic/{vod_id}.png",
            "vod_remarks": remarks,
            "vod_score": f"{6 + (n % 4)}.{n % 10}",
            "vod_year": str(2018 + (n % 7)),
            "vod_area": ["大陆", "美国", "日本", "韩国"][n % 4],
            "vod_lang": "国语",
            "vod_actor": f"演员{tag}{n}",
            "vod_director": f"导演{tag}{n}",
            "vod_total": str(episodes),
            "type_id": type_id,
            "type_name": type_name,
            "vod_blurb": f"[{tag}] {type_name} 第 {n} 号测试数据",
        }
        if with_play:
            item["vod_content"] = f"<p>[{tag}] {type_name} 第 {n} 号的完整简介。</p>"
            # 线路一给完整集数、线路二少两集，用于验证「默认选集数最多的线路 / 换源」
            # broken 的那部：线路二指向死端口（探不通、播放必错）
            second_host = f"10.0.2.2:{DEAD_PORT}/dead" if broken else "10.0.2.2:8899/media"
            short = "#".join(
                f"第{i:02d}集$http://{second_host}/{vod_id}_b{i}.m3u8"
                for i in range(1, max(2, episodes - 2) + 1)
            )
            item["vod_play_from"] = "线路一$$$线路二"
            item["vod_play_url"] = f"{play_url}$$${short}"
        items.append(item)
    return items


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # noqa: A003 - 覆盖父类日志格式
        sys.stdout.write("%s %s\n" % (self.address_string(), fmt % args))
        sys.stdout.flush()

    def _send(self, body: bytes, ctype: str):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)
        tag = site_tag(path)

        # 封面
        if path.startswith("/pic/"):
            seed = int(path.rsplit("/", 1)[-1].split(".")[0] or "0")
            return self._send(poster_png(seed), "image/png")

        # 播放地址：
        # - .mp4 = 真素材（能真播，用于验收「自动优先有效源」后确实放得起来）；
        # - 其余给空 m3u8 占位，让「测速 / 播放器报错」可控。
        if path.startswith("/media/"):
            if path.endswith(".mp4") and MEDIA_MP4:
                with open(MEDIA_MP4, "rb") as f:
                    return self._send(f.read(), "video/mp4")
            return self._send(b"", "application/vnd.apple.mpegurl")

        if path.endswith("/favicon.ico"):
            return self._send(b"", "image/x-icon")

        ac = (query.get("ac") or [""])[0]
        pg = int((query.get("pg") or ["1"])[0] or "1")
        t = (query.get("t") or [""])[0]
        ids = (query.get("ids") or [""])[0]
        wd = (query.get("wd") or [""])[0]

        with_play = ac == "detail"
        all_items = []
        for type_id, type_name, _parent_id in CATEGORIES:
            all_items.extend(make_items(tag, type_id, type_name, with_play))

        if ids:
            picked = [i for i in all_items if str(i["vod_id"]) == ids] or all_items[:1]
        elif wd:
            picked = [i for i in all_items if wd in i["vod_name"]]
        elif t:
            picked = [i for i in all_items if str(i["type_id"]) == t]
        else:
            picked = all_items

        start = (pg - 1) * PAGE_SIZE
        page_items = picked[start:start + PAGE_SIZE]

        payload = {
            "code": 1,
            "msg": "数据列表",
            "page": pg,
            "pagecount": (len(picked) + PAGE_SIZE - 1) // PAGE_SIZE,
            "limit": str(PAGE_SIZE),
            "total": len(picked),
            "class": [
                {"type_id": cid, "type_pid": pid, "type_name": cname}
                for cid, cname, pid in CATEGORIES
            ],
            "list": page_items,
        }
        return self._send(
            json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            "application/json; charset=utf-8",
        )


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8899
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    sys.stdout.write(f"mock maccms on http://0.0.0.0:{port}  (emulator: 10.0.2.2:{port})\n")
    sys.stdout.flush()
    server.serve_forever()


if __name__ == "__main__":
    main()
