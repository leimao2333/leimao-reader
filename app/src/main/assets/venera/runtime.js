/*
 * Venera 源运行时（宿主：隐藏 WebView）
 * ------------------------------------------------------------------
 * Venera 的源是「可执行 JS」：`class Xxx extends ComicSource { ... }`，
 * 依赖 ComicSource / Network / HtmlDocument / Convert / ComicDetails 等运行时对象，
 * 并且大量使用 class 字段、async/await、Map、可选链 —— Rhino（本项目既有 JS 引擎）
 * 连 class 都不支持，所以这里改用系统 WebView（V8）来跑，语法层面零妥协。
 *
 * 与原生（Kotlin）的边界只有一个同步桥 NativeBridge：
 *   - 所有 HTTP 走原生（OkHttp），因此不受 WebView 同源策略 / CORS 限制，
 *     也能复用应用自己的 Cookie 与字符集处理；
 *   - addJavascriptInterface 的调用在 JS 侧是同步的，所以 Network.get() 这类
 *     「同步返回结果」的写法可以原样保留（await 一个非 Promise 值 = 直接取值）。
 *
 * 返回结果通过 NativeBridge.postResult / postError 异步回传。
 */
(function () {
    'use strict';

    var B = window.NativeBridge;
    if (!B) {
        throw new Error('NativeBridge 未注入，Venera 运行时无法工作');
    }

    // ==================== 字节 / Base64 工具 ====================

    /**
     * 判断「类 ArrayBuffer」。
     *
     * 注意不能只用 `v instanceof ArrayBuffer`：跨 realm（例如 WebView 里注入的
     * 宿主对象、或测试环境里 Node 与 jsdom 混用）时 instanceof 会为 false，
     * 而对方其实是等价的二进制缓冲区。这里做鸭子类型判断。
     */
    function isArrayBufferLike(v) {
        return v !== null && typeof v === 'object'
            && typeof v.byteLength === 'number'
            && typeof v.length !== 'number'
            && !ArrayBuffer.isView(v);
    }

    function toU8(v) {
        if (v === null || v === undefined) return new Uint8Array(0);
        if (ArrayBuffer.isView(v)) return new Uint8Array(v.buffer, v.byteOffset, v.byteLength);
        if (v instanceof ArrayBuffer || isArrayBufferLike(v)) return new Uint8Array(v);
        if (Array.isArray(v)) return new Uint8Array(v);
        if (typeof v === 'string') return new TextEncoder().encode(v);
        if (typeof v === 'number') {
            var one = new Uint8Array(1);
            one[0] = v & 0xff;
            return one;
        }
        return new Uint8Array(0);
    }

    function toBuffer(v) {
        var u = toU8(v);
        // 复制一份，避免返回的 ArrayBuffer 与源数据共享底层内存
        var out = new Uint8Array(u.length);
        out.set(u);
        return out.buffer;
    }

    function bytesToB64(v) {
        var u = toU8(v);
        var s = '';
        var chunk = 0x8000;
        for (var i = 0; i < u.length; i += chunk) {
            s += String.fromCharCode.apply(null, u.subarray(i, i + chunk));
        }
        return btoa(s);
    }

    function b64ToBytes(b64) {
        var bin = atob(String(b64).replace(/\s+/g, ''));
        var out = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
        return out;
    }

    function hexDecode(hex) {
        var s = String(hex).replace(/[^0-9a-fA-F]/g, '');
        var out = new Uint8Array(s.length >> 1);
        for (var i = 0; i < out.length; i++) out[i] = parseInt(s.substr(i * 2, 2), 16);
        return out;
    }

    function decodeBytes(v, encoding) {
        var u = toU8(v);
        try {
            return new TextDecoder(encoding || 'utf-8').decode(u);
        } catch (e) {
            return new TextDecoder('utf-8').decode(u);
        }
    }

    // ==================== Convert（编码 / 摘要 / 加解密）====================
    // 摘要与对称加解密交给原生（MessageDigest / Mac / Cipher），避免在 JS 里再实现一遍。

    var Convert = {
        encodeUtf8: function (str) {
            return toBuffer(new TextEncoder().encode(String(str)));
        },
        decodeUtf8: function (v) {
            return decodeBytes(v, 'utf-8');
        },
        encodeGbk: function (str) {
            // WebView 无法编码 GBK；回落 UTF-8，绝大多数源只用于请求体
            return toBuffer(new TextEncoder().encode(String(str)));
        },
        decodeGbk: function (v) {
            return decodeBytes(v, 'gbk');
        },
        decodeBig5: function (v) {
            return decodeBytes(v, 'big5');
        },
        encodeBase64: function (v) {
            return bytesToB64(v);
        },
        decodeBase64: function (s) {
            return toBuffer(b64ToBytes(s));
        },
        md5: function (v) {
            return toBuffer(hexDecode(B.digest('md5', bytesToB64(v))));
        },
        sha1: function (v) {
            return toBuffer(hexDecode(B.digest('sha1', bytesToB64(v))));
        },
        sha256: function (v) {
            return toBuffer(hexDecode(B.digest('sha256', bytesToB64(v))));
        },
        sha512: function (v) {
            return toBuffer(hexDecode(B.digest('sha512', bytesToB64(v))));
        },
        hmac: function (key, value, hash) {
            return toBuffer(hexDecode(B.hmac(hash || 'sha256', bytesToB64(key), bytesToB64(value))));
        },
        hmacString: function (key, value, hash) {
            return B.hmac(hash || 'sha256', bytesToB64(key), bytesToB64(value));
        },
        decryptAesEcb: function (value, key) {
            return toBuffer(b64ToBytes(B.aesDecrypt('ecb', bytesToB64(value), bytesToB64(key), '', 128)));
        },
        decryptAesCbc: function (value, key, iv) {
            return toBuffer(b64ToBytes(B.aesDecrypt('cbc', bytesToB64(value), bytesToB64(key), bytesToB64(iv), 128)));
        },
        hexEncode: function (v) {
            var u = toU8(v);
            var s = '';
            for (var i = 0; i < u.length; i++) s += (u[i] < 16 ? '0' : '') + u[i].toString(16);
            return s;
        },
    };

    // ==================== 杂项全局 ====================

    function randomInt(min, max) {
        return Math.floor(Math.random() * (max - min + 1)) + min;
    }

    function randomDouble(min, max) {
        return Math.random() * (max - min) + min;
    }

    function createUuid() {
        return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
            var r = (Math.random() * 16) | 0;
            var v = c === 'x' ? r : (r & 0x3) | 0x8;
            return v.toString(16);
        });
    }

    function log(level, title, content) {
        try {
            B.log(String(level || 'info'), String(title || '') + ' ' + String(content === undefined ? '' : content));
        } catch (e) { /* 忽略日志失败 */ }
    }

    var console = {
        log: function (c) { log('info', 'JS', c); },
        warn: function (c) { log('warning', 'JS', c); },
        error: function (c) { log('error', 'JS', c); },
        info: function (c) { log('info', 'JS', c); },
        debug: function (c) { log('debug', 'JS', c); },
    };

    /** 在引擎池里计算一段 JS 函数；这里直接同步求值 */
    function compute(func, ...args) {
        var f = new Function('return (' + func + ')')();
        return f(args);
    }

    // ==================== 结果对象（与 venera 一致）====================

    function Comic(o) {
        o = o || {};
        this.id = o.id;
        this.title = o.title;
        this.subtitle = o.subtitle;
        this.subTitle = o.subTitle;
        this.cover = o.cover;
        this.tags = o.tags;
        this.description = o.description;
        this.maxPage = o.maxPage;
        this.language = o.language;
        this.favoriteId = o.favoriteId;
        this.stars = o.stars;
    }

    function ComicDetails(o) {
        o = o || {};
        this.title = o.title;
        this.subtitle = o.subtitle;
        this.subTitle = o.subTitle;
        this.cover = o.cover;
        this.description = o.description;
        this.tags = o.tags;
        this.chapters = o.chapters;
        this.isFavorite = o.isFavorite;
        this.subId = o.subId;
        this.thumbnails = o.thumbnails;
        this.recommend = o.recommend;
        this.commentCount = o.commentCount;
        this.likesCount = o.likesCount;
        this.isLiked = o.isLiked;
        this.uploader = o.uploader;
        this.updateTime = o.updateTime;
        this.uploadTime = o.uploadTime;
        this.url = o.url;
        this.stars = o.stars;
        this.maxPage = o.maxPage;
        this.comments = o.comments;
    }

    function Comment(o) {
        o = o || {};
        this.userName = o.userName;
        this.avatar = o.avatar;
        this.content = o.content;
        this.time = o.time;
        this.replyCount = o.replyCount;
        this.id = o.id;
        this.isLiked = o.isLiked;
        this.score = o.score;
        this.voteStatus = o.voteStatus;
    }

    function ImageLoadingConfig(o) {
        o = o || {};
        this.url = o.url;
        this.method = o.method;
        this.data = o.data;
        this.headers = o.headers;
        this.onResponse = o.onResponse;
        this.modifyImage = o.modifyImage;
        this.onLoadFailed = o.onLoadFailed;
    }

    /** 评论区容器（本应用暂不展示评论，提供最小可用实现） */
    class Comments {
        constructor(o) {
            o = o || {};
            this.comments = o.comments || [];
            this.loadMore = o.loadMore;
            this.maxPage = o.maxPage;
            this.page = o.page;
        }
    }

    function Cookie(o) {
        o = o || {};
        this.name = o.name;
        this.value = o.value;
        this.domain = o.domain;
    }

    // ==================== Network ====================
    // 全部经原生发起：不受同源策略限制，Cookie / 字符集由原生统一处理。

    function headersToJson(headers) {
        return JSON.stringify(headers || {});
    }

    var Network = {
        sendRequest: function (method, url, headers, data) {
            var res = JSON.parse(B.http(String(method || 'GET'), String(url), headersToJson(headers),
                data === null || data === undefined ? null : (typeof data === 'string' ? data : String(data))));
            if (res.error) throw new Error(res.error);
            return { status: res.status, headers: res.headers || {}, body: res.body, url: res.url || url };
        },
        fetchBytes: function (method, url, headers, data) {
            var res = JSON.parse(B.httpBinary(String(method || 'GET'), String(url), headersToJson(headers),
                data === null || data === undefined ? null : (typeof data === 'string' ? data : String(data))));
            if (res.error) throw new Error(res.error);
            return { status: res.status, headers: res.headers || {}, body: b64ToBytes(res.bodyBase64 || '').buffer, url: res.url || url };
        },
        get: function (url, headers) {
            return this.sendRequest('GET', url, headers);
        },
        post: function (url, headers, data) {
            return this.sendRequest('POST', url, headers, data);
        },
        put: function (url, headers, data) {
            return this.sendRequest('PUT', url, headers, data);
        },
        patch: function (url, headers, data) {
            return this.sendRequest('PATCH', url, headers, data);
        },
        delete: function (url, headers) {
            return this.sendRequest('DELETE', url, headers);
        },
        setCookies: function (url, cookies) {
            var list = Array.isArray(cookies) ? cookies : [cookies];
            var lines = [];
            for (var i = 0; i < list.length; i++) {
                var c = list[i];
                if (!c) continue;
                var line = c.name + '=' + c.value;
                if (c.domain) line += '; domain=' + c.domain;
                line += '; path=/';
                lines.push(line);
            }
            if (lines.length) B.setCookie(String(url), lines.join('\n'));
        },
        getCookies: function (url) {
            var raw = B.getCookie(String(url));
            if (!raw) return [];
            var out = [];
            var parts = String(raw).split(';');
            for (var i = 0; i < parts.length; i++) {
                var kv = parts[i].trim();
                if (!kv) continue;
                var idx = kv.indexOf('=');
                if (idx < 0) continue;
                out.push(new Cookie({ name: kv.slice(0, idx), value: kv.slice(idx + 1) }));
            }
            return out;
        },
        deleteCookies: function (url) {
            B.deleteCookies(String(url));
        },
    };

    /** 兼容浏览器 fetch 语义（供少数源使用） */
    async function fetch(url, options) {
        options = options || {};
        var res = await Network.fetchBytes(options.method || 'GET', url, options.headers, options.body);
        return {
            ok: res.status >= 200 && res.status < 300,
            status: res.status,
            statusText: '',
            headers: res.headers,
            arrayBuffer: async function () { return res.body; },
            text: async function () { return decodeBytes(res.body, 'utf-8'); },
            json: async function () { return JSON.parse(decodeBytes(res.body, 'utf-8')); },
        };
    }

    // ==================== HtmlDocument（基于 DOMParser）====================

    class HtmlElement {
        constructor(el) {
            this._el = el;
        }
        get text() {
            return this._el ? (this._el.textContent || '') : '';
        }
        get html() {
            return this._el ? (this._el.innerHTML || '') : '';
        }
        // 官方 API 名是 innerHTML / outerHTML（见 _venera_.js），两者都提供
        get innerHTML() {
            return this._el ? (this._el.innerHTML || '') : '';
        }
        get outerHtml() {
            return this._el ? (this._el.outerHTML || '') : '';
        }
        get outerHTML() {
            return this._el ? (this._el.outerHTML || '') : '';
        }
        get attributes() {
            var o = {};
            if (this._el && this._el.attributes) {
                for (var i = 0; i < this._el.attributes.length; i++) {
                    var a = this._el.attributes[i];
                    o[a.name] = a.value;
                }
            }
            return o;
        }
        get children() {
            if (!this._el) return [];
            return Array.prototype.slice.call(this._el.children).map(function (e) { return new HtmlElement(e); });
        }
        get nodes() {
            if (!this._el) return [];
            return Array.prototype.slice.call(this._el.childNodes).map(function (n) {
                return n.nodeType === 1 ? new HtmlElement(n) : new HtmlNode(n);
            });
        }
        get parent() {
            var p = this._el && this._el.parentElement;
            return p ? new HtmlElement(p) : null;
        }
        get nextSibling() {
            var n = this._el && this._el.nextElementSibling;
            return n ? new HtmlElement(n) : null;
        }
        get previousSibling() {
            var n = this._el && this._el.previousElementSibling;
            return n ? new HtmlElement(n) : null;
        }
        // 官方 API 名是 previousElementSibling / nextElementSibling
        get nextElementSibling() {
            var n = this._el && this._el.nextElementSibling;
            return n ? new HtmlElement(n) : null;
        }
        get previousElementSibling() {
            var n = this._el && this._el.previousElementSibling;
            return n ? new HtmlElement(n) : null;
        }
        get id() {
            return this._el ? (this._el.id || '') : '';
        }
        get className() {
            return this._el ? (this._el.className || '') : '';
        }
        /** 官方语义：类名数组（注意不是 className 字符串） */
        get classNames() {
            if (!this._el) return [];
            var raw = this._el.getAttribute ? (this._el.getAttribute('class') || '') : '';
            if (!raw) return [];
            return String(raw).split(/\s+/).filter(function (s) { return s.length > 0; });
        }
        get localName() {
            return this._el ? (this._el.localName || '') : '';
        }
        get tagName() {
            return this._el ? (this._el.tagName || '') : '';
        }
        getAttribute(name) {
            return this._el ? this._el.getAttribute(name) : null;
        }
        hasAttribute(name) {
            return this._el ? this._el.hasAttribute(name) : false;
        }
        querySelector(selector) {
            var e = this._el && this._el.querySelector(selector);
            return e ? new HtmlElement(e) : null;
        }
        querySelectorAll(selector) {
            if (!this._el) return [];
            return Array.prototype.slice.call(this._el.querySelectorAll(selector)).map(function (e) {
                return new HtmlElement(e);
            });
        }
        getElementById(id) {
            var e = this._el && this._el.querySelector('#' + id);
            return e ? new HtmlElement(e) : null;
        }
        /**
         * 部分源会显式释放节点（Venera 里是释放原生句柄）。
         * DOM 实现下节点由 GC 管理，这里刻意做成 **空操作**：
         * 有些源会在 dispose 之后仍然使用已取到的元素，真正置空反而会弄坏它们。
         */
        dispose() {
        }
    }

    class HtmlNode {
        constructor(n) {
            this._n = n;
        }
        get text() {
            return this._n ? (this._n.textContent || '') : '';
        }
        get nodeType() {
            return this._n ? this._n.nodeType : 0;
        }
        /** 官方语义：字符串类型（text / element / comment / document / unknown） */
        get type() {
            var t = this._n ? this._n.nodeType : 0;
            if (t === 1) return 'element';
            if (t === 3) return 'text';
            if (t === 8) return 'comment';
            if (t === 9) return 'document';
            return 'unknown';
        }
        /** 元素节点转 HtmlElement，非元素返回 null */
        toElement() {
            if (this._n && this._n.nodeType === 1) return new HtmlElement(this._n);
            return null;
        }
        dispose() {
        }
    }

    class HtmlDocument extends HtmlElement {
        constructor(html) {
            var doc = new DOMParser().parseFromString(html === null || html === undefined ? '' : String(html), 'text/html');
            super(doc.documentElement);
            this._doc = doc;
        }
        querySelector(selector) {
            var e = this._doc.querySelector(selector);
            return e ? new HtmlElement(e) : null;
        }
        querySelectorAll(selector) {
            return Array.prototype.slice.call(this._doc.querySelectorAll(selector)).map(function (e) {
                return new HtmlElement(e);
            });
        }
        getElementById(id) {
            var e = this._doc.getElementById(id);
            return e ? new HtmlElement(e) : null;
        }
        dispose() {
        }
    }

    // ==================== UI / APP ====================

    var UI = {
        showMessage: function (msg) {
            log('info', 'UI', msg);
        },
        showLoading: function () { },
        cancelLoading: function () { },
        showDialog: function () {
            return Promise.reject(new Error('Venera 源要求弹窗交互（如登录/验证码），当前版本暂不支持'));
        },
        showInputDialog: function () {
            return Promise.reject(new Error('Venera 源要求输入交互，当前版本暂不支持'));
        },
        launchUrl: function (url) {
            log('info', 'UI', 'launchUrl: ' + url);
        },
    };

    var APP = {
        version: '1.7.0',
        locale: 'zh-CN',
        platform: 'android',
        isAndroid: true,
        isIOS: false,
        isWindows: false,
    };

    // ==================== ComicSource 基类 ====================

    function settingKey(key, name) {
        return 'venera:setting:' + key + ':' + name;
    }

    function dataKey(key, name) {
        return 'venera:data:' + key + ':' + name;
    }

    class ComicSource {
        // 与官方运行时的 ComicSource 字段严格一致（见 _venera_.js）。
        // 特别地：官方**没有** settings 字段 —— 少数源用 `get settings()`
        // 在原型上暴露设置项，基类若也声明同名字段，实例属性会遮蔽该 getter。
        name = '';
        key = '';
        version = '';
        minAppVersion = '';
        url = '';
        translation = {};

        loadSetting(name) {
            var stored = B.storageGet(settingKey(this.key, name));
            if (stored !== null && stored !== undefined && stored !== '') {
                try { return JSON.parse(stored); } catch (e) { return stored; }
            }
            var defs = this.settings;
            var def = defs && defs[name];
            if (def && Object.prototype.hasOwnProperty.call(def, 'default')) return def.default;
            return null;
        }

        saveSetting(name, value) {
            B.storageSet(settingKey(this.key, name), JSON.stringify(value));
        }

        loadData(name) {
            var raw = B.storageGet(dataKey(this.key, name));
            if (raw === null || raw === undefined || raw === '') return null;
            try { return JSON.parse(raw); } catch (e) { return raw; }
        }

        saveData(name, value) {
            B.storageSet(dataKey(this.key, name), JSON.stringify(value === undefined ? null : value));
        }

        deleteData(name) {
            B.storageDelete(dataKey(this.key, name));
        }

        get isLogged() {
            return false;
        }

        translate(key) {
            var table = this.translation && this.translation[APP.locale];
            if (table && table[key] !== undefined) return table[key];
            return key;
        }

        init() { }

        static sources = {}
    }

    // ==================== 源注册与调用 ====================
    // 语法注意：用 new Function 包住源码，让 class 声明留在函数作用域内，
    // 同时全局的 ComicSource / Network / HtmlDocument 等仍可见。

    var SOURCES = {};
    var INFO_CACHE = {};

    function registerSource(key) {
        var code = B.getSource(String(key));
        if (!code) throw new Error('未找到源代码：' + key);
        var m = /class\s+([A-Za-z_$][\w$]*)\s+extends\s+ComicSource/.exec(code);
        if (!m) throw new Error('不是有效的 Venera 源（未找到 class X extends ComicSource）');
        var cls = m[1];
        var factory = new Function(code + '\n;return new ' + cls + '();');
        var instance = factory();
        instance.key = instance.key || key;
        SOURCES[key] = instance;
        return instance;
    }

    /**
     * 取（并按需构造 / 初始化）源实例。
     *
     * 注意：`init()` 在部分源里是 async，且会填充后面 getter 依赖的状态
     * （例如某些源的 `static apiDomains` 由 init → refreshApiDomains 写入）。
     * 因此必须 await，否则第一次调用会撞上未完成的初始化。
     */
    var INIT_DONE = {};

    async function ensureSource(key) {
        var src = SOURCES[key] || registerSource(key);
        if (!INIT_DONE[key]) {
            INIT_DONE[key] = true;
            if (typeof src.init === 'function') {
                try {
                    await src.init();
                } catch (e) {
                    log('warning', 'init', key + ' ' + ((e && e.message) || e));
                }
            }
        }
        return src;
    }

    function chaptersToPairs(chapters) {
        var out = [];
        if (!chapters) return out;
        if (typeof chapters.entries === 'function') {
            var it = chapters.entries();
            var step = it.next();
            while (!step.done) {
                out.push(step.value);
                step = it.next();
            }
            return out;
        }
        for (var k in chapters) {
            if (Object.prototype.hasOwnProperty.call(chapters, k)) out.push([k, chapters[k]]);
        }
        return out;
    }

    function chapterTitle(v) {
        if (v === null || v === undefined) return '';
        if (typeof v === 'string') return v;
        if (typeof v === 'object') {
            if (typeof v.title === 'string') return v.title;
            if (typeof v.name === 'string') return v.name;
        }
        return String(v);
    }

    /**
     * 把源返回的图片列表序列化成 app 可消费的结构。
     *
     * `loadEp` 可能返回 `["url", …]`（纯地址）或 `[{url, headers}, …]`（按图请求头）。
     * 之后逐张调用 `onImageLoad(url, comicId, epId)`（18/33 个源实现了它），
     * 其返回值可以「改地址 / 带请求头 / 给出 modifyImage 脚本」：
     * modifyImage 是**解码后的像素后处理脚本**，用于把「按 N 段纵向打乱」
     * 的图片还原回正常顺序。这个脚本必须跟着地址一起交给 app，
     * 否则读者看到的就是一页页乱序的图片。
     */
    async function serializeImages(list, src, comicId, epId) {
        var urls = [];
        var headers = {};
        var scripts = {};
        var arr = list || [];
        for (var i = 0; i < arr.length; i++) {
            var item = arr[i];
            if (typeof item === 'string') {
                urls.push(item);
                continue;
            }
            if (!item) continue;
            if (item.url) urls.push(item.url);
            if (item.headers && Object.keys(item.headers).length && Object.keys(headers).length === 0) {
                headers = item.headers;
            }
        }
        // onImageLoad：源用它补签名 / 防盗链头 / 二次解析真实地址 / 给出乱序还原脚本
        var onLoad = src.comic && src.comic.onImageLoad;
        if (typeof onLoad === 'function' && urls.length) {
            var fixed = [];
            for (var j = 0; j < urls.length; j++) {
                var orig = urls[j];
                var finalUrl = orig;
                try {
                    var r = await onLoad.call(src, orig, comicId, epId);
                    if (typeof r === 'string') {
                        finalUrl = r;
                    } else if (r && typeof r === 'object') {
                        if (typeof r.url === 'string' && r.url) finalUrl = r.url;
                        if (r.headers && Object.keys(r.headers).length && Object.keys(headers).length === 0) {
                            headers = r.headers;
                        }
                        // modifyImage 多为代码字符串；函数无法跨桥传递，只能跳过
                        if (typeof r.modifyImage === 'string' && r.modifyImage) {
                            scripts[finalUrl] = r.modifyImage;
                        }
                    }
                } catch (e) {
                    log('warning', 'onImageLoad', String(e));
                }
                fixed.push(finalUrl);
            }
            urls = fixed;
        }
        return { images: urls, headers: headers, scripts: scripts };
    }

    /**
     * 由 optionList 生成「默认选项」数组。
     *
     * Venera 的 optionList 形如 [{type, label, options: ["value-text", ...]}]，
     * 每个分组对应 options[] 里的一个位置，默认取分组里的**第一项**的值
     * （`-` 之前的部分；如 "-全部" → ""）。这一点很关键：不少源会直接写
     *   options[0] = "&sort_by=" + options[0]
     * 若默认给 null/undefined，请求就会带上 "sort_by=null" 之类的脏参数。
     */
    function defaultOptions(section) {
        var list = section && section.optionList;
        if (!list || !Array.isArray(list)) return [];
        var out = [];
        for (var i = 0; i < list.length; i++) {
            var group = list[i];
            var opts = group && group.options;
            if (!opts || !opts.length) {
                out.push(null);
                continue;
            }
            var first = String(opts[0]);
            var dash = first.indexOf('-');
            out.push(dash >= 0 ? first.slice(0, dash) : first);
        }
        return out;
    }

    async function doCall(key, op, args) {
        var src = await ensureSource(key);
        switch (op) {
            case 'ping':
                return { ok: true, name: src.name, version: src.version };

            case 'search': {
                // 两种契约：load(keyword, options, page) 与 loadNext(keyword, options, next)
                // （loadNext 用于「下一页 token」型源；两者都存在时 load 优先，与官方一致）
                var hasLoad = src.search && typeof src.search.load === 'function';
                var hasNext = src.search && typeof src.search.loadNext === 'function';
                if (!hasLoad && !hasNext) {
                    throw new Error('该源没有实现搜索（search.load）');
                }
                // 调用方显式传入选项时用传入的，否则按 optionList 取默认值
                var opts = Array.isArray(args[2]) && args[2].length ? args[2].slice() : defaultOptions(src.search);
                var pageNo = args[1] || 1;
                var r;
                if (hasLoad) {
                    r = await src.search.load.call(src, args[0], opts, pageNo);
                } else {
                    // 只能从第一页开始翻 token；第 1 页之后的暂不支持
                    if (pageNo > 1) return { comics: [], maxPage: null };
                    r = await src.search.loadNext.call(src, args[0], opts, null);
                }
                var comics = (r && r.comics) || [];
                var list = [];
                for (var i = 0; i < comics.length; i++) {
                    var c = comics[i];
                    if (!c) continue;
                    list.push({
                        id: String(c.id === undefined || c.id === null ? '' : c.id),
                        title: c.title || '',
                        subTitle: c.subTitle || c.subtitle || '',
                        cover: c.cover || '',
                        description: c.description || '',
                        tags: c.tags || [],
                    });
                }
                return { comics: list, maxPage: (r && r.maxPage) || null };
            }

            case 'info': {
                var d = await src.comic.loadInfo.call(src, args[0]);
                INFO_CACHE[key + '|' + args[0]] = d;
                return {
                    title: d && d.title || '',
                    subTitle: (d && (d.subtitle || d.subTitle)) || '',
                    cover: (d && d.cover) || '',
                    description: (d && d.description) || '',
                    tags: (d && d.tags) || null,
                    updateTime: (d && d.updateTime) || '',
                    chapters: chaptersToPairs(d && d.chapters).map(function (p) {
                        return [String(p[0]), chapterTitle(p[1])];
                    }),
                };
            }

            case 'chapters': {
                var cached = INFO_CACHE[key + '|' + args[0]];
                if (!cached) {
                    cached = await src.comic.loadInfo.call(src, args[0]);
                    INFO_CACHE[key + '|' + args[0]] = cached;
                }
                return {
                    chapters: chaptersToPairs(cached && cached.chapters).map(function (p) {
                        return [String(p[0]), chapterTitle(p[1])];
                    }),
                };
            }

            case 'content': {
                var loadEp = (src.comic && src.comic.loadEp) || (src.comic && src.comic.loadChapter);
                if (typeof loadEp !== 'function') throw new Error('该源没有实现章节图片加载（comic.loadEp）');
                var res = await loadEp.call(src, args[0], args[1]);
                var imgs = res && (res.images || res);
                return await serializeImages(imgs, src, args[0], args[1]);
            }

            default:
                throw new Error('未知操作：' + op);
        }
    }

    async function runCall(callId, key, op, argsJson) {
        try {
            var args = argsJson ? JSON.parse(argsJson) : [];
            var out = await doCall(key, op, args || []);
            B.postResult(String(callId), JSON.stringify(out === undefined ? null : out));
        } catch (e) {
            var msg = (e && (e.message || e.toString())) || String(e);
            // window.__veneraDebug = true 时附带堆栈（离线验证台用），应用内保持简洁
            if (window.__veneraDebug && e && e.stack) msg += '\n' + e.stack;
            B.postError(String(callId), msg);
        }
    }

    // 暴露给 Kotlin
    window.__veneraRegister = function (key) {
        try {
            var s = registerSource(key);
            return JSON.stringify({ ok: true, name: s.name, version: s.version });
        } catch (e) {
            return JSON.stringify({ ok: false, error: (e && e.message) || String(e) });
        }
    };

    window.__veneraCall = function (callId, key, op, argsJson) {
        runCall(callId, key, op, argsJson);
        return true;
    };

    // 全局导出（源脚本里直接用这些名字）
    window.ComicSource = ComicSource;
    window.Comic = Comic;
    window.ComicDetails = ComicDetails;
    window.Comment = Comment;
    window.Comments = Comments;
    window.Cookie = Cookie;
    window.ImageLoadingConfig = ImageLoadingConfig;
    window.Network = Network;
    window.HtmlDocument = HtmlDocument;
    window.HtmlElement = HtmlElement;
    window.HtmlNode = HtmlNode;
    window.Convert = Convert;
    window.UI = UI;
    window.APP = APP;
    window.log = log;
    window.console = console;
    window.fetch = fetch;
    window.randomInt = randomInt;
    window.randomDouble = randomDouble;
    window.createUuid = createUuid;
    window.compute = compute;

    B.ready();
})();
