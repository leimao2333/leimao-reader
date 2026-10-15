// 本地 Venera 漫画源（端到端验收用，v1.7.0.0）
// ------------------------------------------------------------------
// 打本地测试站（模拟器里是 http://10.0.2.2:8899/comic/api/*），用来离线验收：
//   1. `category.parts` → 分类标签池（题材：恋爱 / 校园 / 奇幻 / 热血）
//   2. `categoryComics.load` → **真分类页浏览**（点标签不再退化成按标题搜索）
//   3. 源自带 `tags` → 详情页标签栏
//   4. 复合搜索：同源交集（恋爱 ∩ 校园 在第 1 页）+ 翻页探测
//      （热血 ∩ 奇幻 的唯一交集「双面英雄」只在第 2 页）
//
// 测试站的数据刻意做成「一部作品同时属于多个题材」，与真实漫画站一致。
class LocalComic extends ComicSource {
  name = "本地漫画源"
  key = "local_comic"
  version = "1.0.0"
  baseUrl = "http://10.0.2.2:8899/comic/api"

  category = {
    title: "本地漫画源",
    parts: [
      {
        name: "题材",
        type: "fixed",
        itemType: "category",
        categories: ["全部", "恋爱", "校园", "奇幻", "热血"],
        categoryParams: ["all", "lianai", "xiaoyuan", "qihuan", "rexie"],
      },
    ],
    enableRankingPage: false,
  }

  categoryComics = {
    load: async (category, param, options, page) => {
      let res = await Network.get(`${this.baseUrl}/list?type=${param}&page=${page}`);
      if (res.status !== 200) throw "LocalComic: status " + res.status;
      let json = JSON.parse(res.body);
      return {
        comics: json.items.map((e) => this.parseComic(e)),
        maxPage: json.maxPage,
      };
    },
  }

  search = {
    load: async (keyword, options, page) => {
      let res = await Network.get(`${this.baseUrl}/search?q=${encodeURIComponent(keyword)}&page=${page}`);
      if (res.status !== 200) throw "LocalComic: status " + res.status;
      let json = JSON.parse(res.body);
      return {
        comics: json.items.map((e) => this.parseComic(e)),
        maxPage: json.maxPage,
      };
    },
  }

  parseComic = (e) => {
    return new Comic({
      id: String(e.id),
      title: e.title,
      cover: e.cover,
      tags: e.tags,
    });
  };

  comic = {
    loadInfo: async (id) => {
      let res = await Network.get(`${this.baseUrl}/info?id=${id}`);
      if (res.status !== 200) throw "LocalComic: status " + res.status;
      let j = JSON.parse(res.body);
      // 目录刻意做成**两层 Map**（分组名 → Map<epId, 标题>），与漫画人
      // （manhuaren）一致：验收「分组要摊平，不能显示成 [object Map]」。
      let chapters = new Map();
      for (let g of (j.chapterGroups || [])) {
        let inner = new Map();
        for (let epId of Object.keys(g.eps || {})) inner.set(epId, g.eps[epId]);
        if (inner.size > 0) chapters.set(g.group, inner);
      }
      return new ComicDetails({
        title: j.title,
        cover: j.cover,
        // tags 是**对象**形态 {作者, 类型, 标签}，验收详情页标签栏
        tags: j.tags,
        description: j.description,
        chapters: chapters,
      });
    },

    // 章节图片直接用测试站的纯色 PNG，能真渲染出来
    loadEp: async (id, epId) => {
      let n = parseInt(id, 10);
      return [
        `http://10.0.2.2:8899/pic/${9000 + n}.png`,
        `http://10.0.2.2:8899/pic/${9000 + n + 100}.png`,
      ];
    },
  }
}
