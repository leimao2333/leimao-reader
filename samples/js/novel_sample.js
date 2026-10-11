// ============================================================
// 小说源 JS 规范样例（兼容 legado/阅读 书写习惯）
// 说明：本文件通过 Rhino 引擎执行，暴露以下函数供 App 调用
// ============================================================

// 返回源元信息
function getMeta() {
    return {
        name: "示例小说源(JS)",
        type: "novel",
        version: "1.0.0",
        author: "demo",
        desc: "JS 小说源规范样例"
    };
}

// 搜索
// @param keyword 关键词
// @param page 页码
// @return [{ id, title, cover, author, desc }]
function search(keyword, page) {
    var url = "https://example.com/search?keyword=" + encodeURIComponent(keyword) + "&page=" + page;
    var html = fetch(url);
    var doc = parseHtml(html);

    var result = [];
    var items = doc.querySelectorAll("div.book-item");
    for (var i = 0; i < items.length; i++) {
        var item = items[i];
        var a = item.querySelector("a");
        result.push({
            id: a.getAttribute("href"),
            title: item.querySelector(".title").text(),
            cover: item.querySelector("img").getAttribute("src"),
            author: item.querySelector(".author").text(),
            desc: item.querySelector(".intro").text()
        });
    }
    return result;
}

// 详情
// @param itemUrl 详情页地址
// @return { cover, author, desc }
function detail(itemUrl) {
    var html = fetch(itemUrl);
    var doc = parseHtml(html);
    return {
        cover: doc.querySelector(".cover img").getAttribute("src"),
        author: doc.querySelector(".info .author").text(),
        desc: doc.querySelector(".desc").text()
    };
}

// 目录 / 章节列表
// @param detailUrl 详情页地址
// @return [{ id, title, url }]
function chapterList(detailUrl) {
    var html = fetch(detailUrl);
    var doc = parseHtml(html);
    var result = [];
    var items = doc.querySelectorAll("div.chapter-list a");
    for (var i = 0; i < items.length; i++) {
        result.push({
            id: items[i].getAttribute("href"),
            title: items[i].text(),
            url: items[i].getAttribute("href")
        });
    }
    return result;
}

// 正文
// @param chapterUrl 章节地址
// @return { text }
function content(chapterUrl) {
    var html = fetch(chapterUrl);
    var doc = parseHtml(html);
    var el = doc.querySelector("#content");
    return { text: el.html() };
}
