// ============================================================
// 漫画源 JS 规范样例（兼容 legado/阅读 书写习惯）
// ============================================================

function getMeta() {
    return {
        name: "示例漫画源(JS)",
        type: "comic",
        version: "1.0.0",
        author: "demo",
        desc: "JS 漫画源规范样例"
    };
}

// 搜索
function search(keyword, page) {
    var url = "https://example.com/search?keyword=" + encodeURIComponent(keyword) + "&page=" + page;
    var doc = parseHtml(fetch(url));
    var result = [];
    var items = doc.querySelectorAll("div.comic-item");
    for (var i = 0; i < items.length; i++) {
        var item = items[i];
        result.push({
            id: item.querySelector("a").getAttribute("href"),
            title: item.querySelector(".title").text(),
            cover: item.querySelector("img").getAttribute("src"),
            author: item.querySelector(".author").text()
        });
    }
    return result;
}

// 详情
function detail(itemUrl) {
    var doc = parseHtml(fetch(itemUrl));
    return {
        cover: doc.querySelector(".cover img").getAttribute("src"),
        author: doc.querySelector(".author").text(),
        desc: doc.querySelector(".desc").text()
    };
}

// 章节列表
function chapterList(detailUrl) {
    var doc = parseHtml(fetch(detailUrl));
    var result = [];
    var items = doc.querySelectorAll("ul.chapter-list li a");
    for (var i = 0; i < items.length; i++) {
        result.push({
            id: items[i].getAttribute("href"),
            title: items[i].text(),
            url: items[i].getAttribute("href")
        });
    }
    return result;
}

// 图片内容（返回图片地址数组）
// @return { images: [...] }
function content(chapterUrl) {
    var doc = parseHtml(fetch(chapterUrl));
    var imgs = doc.querySelectorAll("#chapter-images img");
    var images = [];
    for (var i = 0; i < imgs.length; i++) {
        images.push(imgs[i].getAttribute("src"));
    }
    return { images: images };
}
