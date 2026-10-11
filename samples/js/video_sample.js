// ============================================================
// 视频源 JS 规范样例（兼容 legado/阅读 书写习惯）
// ============================================================

function getMeta() {
    return {
        name: "示例视频源(JS)",
        type: "video",
        version: "1.0.0",
        author: "demo",
        desc: "JS 视频源规范样例"
    };
}

// 搜索
function search(keyword, page) {
    var url = "https://example.com/search?keyword=" + encodeURIComponent(keyword) + "&page=" + page;
    var doc = parseHtml(fetch(url));
    var result = [];
    var items = doc.querySelectorAll("div.video-item");
    for (var i = 0; i < items.length; i++) {
        var item = items[i];
        result.push({
            id: item.querySelector("a").getAttribute("href"),
            title: item.querySelector(".title").text(),
            cover: item.querySelector("img").getAttribute("src"),
            author: item.querySelector(".up").text()
        });
    }
    return result;
}

// 详情
function detail(itemUrl) {
    var doc = parseHtml(fetch(itemUrl));
    return {
        cover: doc.querySelector(".cover img").getAttribute("src"),
        author: doc.querySelector(".up").text(),
        desc: doc.querySelector(".desc").text()
    };
}

// 视频目录（选集列表）
function chapterList(detailUrl) {
    var doc = parseHtml(fetch(detailUrl));
    var result = [];
    var items = doc.querySelectorAll("ul.episode-list a");
    for (var i = 0; i < items.length; i++) {
        result.push({
            id: items[i].getAttribute("href"),
            title: items[i].text(),
            url: items[i].getAttribute("href")
        });
    }
    return result;
}

// 播放地址（可附加防盗链请求头）
// @return { url, headers }
function content(chapterUrl) {
    var doc = parseHtml(fetch(chapterUrl));
    var url = doc.querySelector("video").getAttribute("src");
    return {
        url: url,
        headers: {
            "Referer": "https://example.com",
            "User-Agent": "Mozilla/5.0"
        }
    };
}
