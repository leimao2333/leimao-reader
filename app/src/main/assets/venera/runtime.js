/*
 * Venera 源运行时（宿主：隐藏 WebView）
 * ------------------------------------------------------------------
 * Venera 的源是「可执行 JS」：`class Xxx extends ComicSource { ... }`，
 * 依赖 ComicSource / Network / HtmlDocument / Convert / ComicDetails 等运行时对象，
 * 并且大量使用 class 字段、async/await、Map、可选链 —— Rhino（本项目既有 JS 引擎）
 * 连 class 都不支持，所以这里改用系统 WebView（V8）来跑，语法层面零妥协。
 *
 * 与原生（Kotlin）的边界只有 NativeBridge：
 *   - 所有 HTTP 走原生（OkHttp），因此不受 WebView 同源策略 / CORS 限制，
 *     也能复用应用自己的 Cookie 与字符集处理；
 *   - **网络走异步桥**（v1.8.8.8）：Network.get() 立刻返回 Promise，原生在
 *     自己的 IO 线程池里发请求，完成时回调 __veneraHttpDone 再结掉 Promise。
 *     这样等待网络期间 JS 线程是空的，多个源才能真正并行。源脚本里写的
 *     `await Network.get(...)` 不用改 —— await 一个 Promise 与 await 一个值等价；
 *   - 其余桥方法（Cookie / 存储 / 加解密）仍是同步的，都是本地计算，耗时极小。
 *
 * 调用结果通过 NativeBridge.postResult / postError 异步回传。
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

    // ==================== 请求缓存 / 同址串行（v1.9.2.3）====================
    //
    // Venera 的 Network 认两个**伪请求头**，它们不进网络、由运行时消化：
    //   - `cache-time: long | no`：GET 结果缓存。long = 6 小时内直接用缓存，
    //     no = 丢掉缓存重新拉；不给值 = 5 秒内复用（够挡「同一页被连点两次」）。
    //   - `prevent-parallel: true`：同一个 URL 同时只允许一个在飞，其余的等它回来。
    //
    // 为什么要补这块：图库类源（ehentai 最典型）的 `onImageLoad` 是**逐张**解析的，
    // 里面每次都会重新取缩略图页 + `showkey` 页。没有缓存时一章 72 页要打 270 次请求、
    // 60 秒以上，上层早就超时了 —— 用户侧就是「章节一直转圈 / 加载不出图」。
    // 有缓存后同样的章节只剩「一次缩略图页 + 一次 key 页 + 每图一次接口」。
    //
    // 口径对齐 venera 的 `NetworkCacheManager`（lib/network/cache.dart）：
    // 只缓存 GET、按 URL+请求头区分、总容量 8MB、命中时给响应补 `venera-cache: true`。

    /** 缓存总容量上限（字符数近似字节；与 venera 的 10MB 同量级） */
    var HTTP_CACHE_MAX_CHARS = 8 * 1024 * 1024;

    /** cache-time: long 的有效期 */
    var HTTP_CACHE_LONG_MS = 6 * 60 * 60 * 1000;

    /** 不带 cache-time 时的默认复用窗口（venera 是 5 秒） */
    var HTTP_CACHE_DEFAULT_MS = 5 * 1000;

    /** key -> { at, json, size }（Map 的插入顺序天然可做 LRU） */
    var httpCache = new Map();
    var httpCacheChars = 0;

    /** URL+方法+头 -> 正在飞的 Promise（prevent-parallel 用） */
    var httpInFlight = new Map();

    /** 这些头只影响缓存判定，不能发给服务器 */
    function isPseudoHeader(name) {
        var n = String(name).toLowerCase();
        return n === 'cache-time' || n === 'prevent-parallel';
    }

    /** 拆出伪头：返回 { headers（可直接发）, cacheTime, preventParallel } */
    /**
     * 请求体归一化（v1.9.2.3）。
     *
     * 官方把 body 原样交给 Dio（`lib/network/app_dio.dart` 的 `data: data`），
     * Dio 遇到 Map / List 会**自动 JSON 编码**并补 `Content-Type: application/json`。
     * 我们的原生桥只能传字符串，对象原样过去会被 `String()` 成 `"[object Object]"`。
     *
     * 这不是理论问题：上游有源就是直接把对象当 body 传的（某图站的
     * `imagedispatch` / `showpage` 接口），表现为「接口回了别的 JSON」→ 源里再取
     * 字段时 `reg[1]` 抛 `Cannot read properties of null` → 整章图片全失败。
     *
     * @returns {{ data: *, isJson: boolean }}
     */
    function normalizeBody(data) {
        if (data === null || data === undefined) return { data: data, isJson: false };
        if (typeof data === 'string') return { data: data, isJson: false };
        if (typeof data === 'number' || typeof data === 'boolean') {
            return { data: String(data), isJson: false };
        }
        try {
            return { data: JSON.stringify(data), isJson: true };
        } catch (e) {
            return { data: String(data), isJson: false };
        }
    }

    /** 头里是否已有 content-type（大小写不敏感） */
    function hasHeader(headers, name) {
        for (var k in headers) {
            if (Object.prototype.hasOwnProperty.call(headers, k) && k.toLowerCase() === name) return true;
        }
        return false;
    }

    function splitHeaders(headers) {
        var clean = {};
        var cacheTime = null;
        var preventParallel = false;
        if (headers) {
            for (var k in headers) {
                if (!Object.prototype.hasOwnProperty.call(headers, k)) continue;
                var lk = String(k).toLowerCase();
                if (lk === 'cache-time') {
                    cacheTime = String(headers[k]);
                } else if (lk === 'prevent-parallel') {
                    preventParallel = String(headers[k]) === 'true';
                } else {
                    clean[k] = headers[k];
                }
            }
        }
        return { headers: clean, cacheTime: cacheTime, preventParallel: preventParallel };
    }

    /** 缓存键：方法 + URL + 头（头排序，避免顺序不同算成两份） */    function cacheKeyOf(method, url, headers) {
        var keys = Object.keys(headers || {}).sort();
        var parts = [];
        for (var i = 0; i < keys.length; i++) {
            parts.push(keys[i].toLowerCase() + '=' + String(headers[keys[i]]));
        }
        return String(method).toUpperCase() + ' ' + url + '\n' + parts.join('&');
    }

    function cacheGet(key, cacheTime) {
        var hit = httpCache.get(key);
        if (!hit) return null;
        var ttl = cacheTime === 'long' ? HTTP_CACHE_LONG_MS : HTTP_CACHE_DEFAULT_MS;
        if (Date.now() - hit.at > ttl) {
            httpCache.delete(key);
            httpCacheChars -= hit.size;
            return null;
        }
        return hit.json;
    }

    function cachePut(key, json) {
        var size = json.length;
        if (size > HTTP_CACHE_MAX_CHARS / 4) return; // 单个超大响应不进缓存
        var old = httpCache.get(key);
        if (old) {
            httpCacheChars -= old.size;
            httpCache.delete(key);
        }
        while (httpCacheChars + size > HTTP_CACHE_MAX_CHARS && httpCache.size > 0) {
            var firstKey = httpCache.keys().next().value;
            var first = httpCache.get(firstKey);
            httpCacheChars -= first ? first.size : 0;
            httpCache.delete(firstKey);
        }
        httpCache.set(key, { at: Date.now(), json: json, size: size });
        httpCacheChars += size;
    }

    /** 命中缓存时给响应打标（与 venera 一致），源可以据此判断「这是缓存」 */
    function withCacheFlag(json) {
        try {
            var obj = JSON.parse(json);
            if (obj && typeof obj === 'object') {
                var h = obj.headers || (obj.headers = {});
                h['venera-cache'] = 'true';
                return JSON.stringify(obj);
            }
        } catch (e) { /* 非 JSON 就原样返回 */ }
        return json;
    }

    /**
     * 同址共享：同一个缓存键同时只跑一次，后来者直接复用那个 Promise。
     *
     * 比 venera 的「排队等前一个跑完再自己跑」更省 —— 反正结果一样，
     * 而 ehentai 那种一章几十张图的源，重复请求正是它慢的主因。
     */
    function shareInFlight(key, run) {
        var existing = httpInFlight.get(key);
        if (existing) return existing;
        var p;
        try {
            p = Promise.resolve(run());
        } catch (e) {
            return Promise.reject(e);
        }
        httpInFlight.set(key, p);
        var clear = function () {
            if (httpInFlight.get(key) === p) httpInFlight.delete(key);
        };
        p.then(clear, clear);
        return p;
    }

    // ==================== Network ====================
    // 全部经原生发起：不受同源策略限制，Cookie / 字符集由原生统一处理。

    function headersToJson(headers) {
        return JSON.stringify(headers || {});
    }

    // ---------- 异步网络桥（v1.8.8.8）----------
    //
    // 旧实现：Network.get() 直接同步调 NativeBridge.http()，一次 HTTP 往返（6~12s）
    // 期间 JS 线程完全被占住 —— 无论上层把并发开到多大，所有源实际上还是一个接
    // 一个地跑。现在改成：原生在自己的 IO 线程池里发请求，立刻返回 reqId，JS 侧挂
    // 一个 Promise；请求完成时原生回调 __veneraHttpDone(reqId)，JS 再同步把结果
    // 取走（一次查表，不再等网络）。
    //
    // 对源脚本完全透明：源里一律是 `await Network.get(...)`，await 一个 Promise
    // 与 await 一个值的行为一致，所以源码不需要任何改动。

    /** reqId -> { resolve, reject }：还等着原生回话的请求 */
    var httpPending = Object.create(null);

    /** >0 时本次调用强制走同步桥（sync-only 源 / 自愈重试期间） */
    var syncDepth = 0;

    /**
     * key -> true：已确认「只能用同步桥」的源（Promise 误用会直接崩的写法）。
     *
     * **只在内存里有效，绝不落盘**（v1.8.9.5）。旧实现把结论写进
     * `venera:net_sync:<key>` 持久化，而判定条件又宽得离谱 —— 一次普通的业务报错
     * （接口改版、字段缺失、站点返回 HTML）就会被当成「源不支持异步桥」，从此这台
     * 机器上该源**永久**走同步桥。同步桥会阻塞整条共享 JS 线程 6~12 秒，于是用户
     * 侧表现为「点进 Venera 源一直转圈」。内存态最多影响当前进程，重启即恢复。
     */
    var syncOnly = Object.create(null);

    /**
     * key -> `{ hits, message }`：连续「异步失败 + 同步成功」的计数。
     *
     * 判据不是猜错误文本，而是**报错必须一模一样**：同一条报错连续出现两次，
     * 才说明它是结构性的（源真的在用同步取值），而不是偶发网络抖动。
     * 任何一次异步调用成功都会把它清掉。
     */
    var syncSuspect = Object.create(null);

    /** 连续命中多少次才真正钉成同步桥（每次命中都已经过「同步重试成功」验证） */
    var SYNC_SUSPECT_THRESHOLD = 2;

    /** 异步桥实际发起的请求计数，用于判断一次失败是否可能由异步化引起 */
    var asyncHits = 0;

    /** 网络调用总计数（同步 + 异步都算）：看门狗用它判断「源到底有没有发请求」 */
    var netCalls = 0;

    /** 旧版本落盘的「只能用同步桥」标记前缀：v1.8.9.5 起不再读取，注册时顺手清掉 */
    var LEGACY_SYNC_FLAG_PREFIX = 'venera:net_sync:';

    function httpBridgeAsync() {
        return typeof B.httpAsync === 'function' && typeof B.httpTake === 'function';
    }

    function wantAsync() {
        return syncDepth === 0 && httpBridgeAsync();
    }

    function httpBodyArg(data) {
        return data === null || data === undefined
            ? null
            : (typeof data === 'string' ? data : String(data));
    }

    /** 同步桥：直接返回响应 JSON 字符串（阻塞 JS 线程，仅作回落用） */
    function httpSyncRaw(method, url, headers, data, binary) {
        if (orphanedNetwork()) throw new Error('调用已取消');
        netCalls++;
        return binary
            ? B.httpBinary(String(method || 'GET'), String(url), headersToJson(headers), httpBodyArg(data))
            : B.http(String(method || 'GET'), String(url), headersToJson(headers), httpBodyArg(data));
    }

    /**
     * 发一个**异步**请求，返回 Promise&lt;响应 JSON 字符串&gt;。
     *
     * 只负责异步路径；同步回落的判断在 Network.sendRequest / fetchBytes 里做 ——
     * 那里必须返回**裸对象**（而不是 Promise），否则「同步取值」的老写法回落之后
     * 照样拿到 Promise，等于没救回来。
     */
    function httpAsyncCall(method, url, headers, data, binary) {
        return new Promise(function (resolve, reject) {
            var reqId;
            try {
                // 已被放弃的孤儿循环：直接抛错打断，别再往原生塞请求
                if (orphanedNetwork()) throw new Error('调用已取消');
                asyncHits++;
                netCalls++;
                reqId = B.httpAsync(
                    String(method || 'GET'),
                    String(url),
                    headersToJson(headers),
                    httpBodyArg(data),
                    !!binary,
                );
            } catch (e) {
                reject(e);
                return;
            }
            if (!reqId) {
                reject(new Error('网络桥不可用'));
                return;
            }
            httpPending[String(reqId)] = { resolve: resolve, reject: reject, t0: Date.now() };
        });
    }

    /**
     * 取件并结掉 Promise（推送与轮询两条路共用）。
     *
     * @param strict 推送路径传 true：原生已经说「有结果了」，取不到就是结果丢了，
     *               立刻报错，别让源永远等下去。轮询路径传 false：取不到只说明还没
     *               到，继续等下一轮。
     * @returns true 表示已经处理（无论成败），false 表示结果还没到
     */
    function settleHttp(reqId, slot, strict) {
        var raw;
        try {
            raw = B.httpTake(reqId);
        } catch (e) {
            delete httpPending[reqId];
            slot.reject(e);
            return true;
        }
        if (raw === null || raw === undefined) {
            if (!strict) return false;
            delete httpPending[reqId];
            slot.reject(new Error('网络结果已失效'));
            return true;
        }
        delete httpPending[reqId];
        slot.resolve(raw);
        return true;
    }

    /**
     * 结果投递的**轮询兜底**（v1.8.9.5）。
     *
     * 原生完成一个异步请求后走 `wv.post { evaluateJavascript("__veneraHttpDone(...)") }`
     * 通知 JS —— 而 `wv.post` 排在**应用主线程**上。首页正在聚合几十个源、列表在
     * 重组时，主线程一旦被占住十几秒，所有通知就全卡在队列里：JS 侧那些 Promise
     * 一个都结不了，`info` 自然一直等，最后撞上 30 秒超时 —— 用户侧就是「点进去
     * 一直转圈」。真机日志实测过：`pending=14`（14 个请求已发完却一个都没回话）。
     *
     * 这里补一条完全走 JS 定时器 + 同步桥的取件通道（不经过应用主线程）：
     * 超过 [HTTP_POLL_MIN_AGE_MS] 还没收到通知的请求，就自己 `httpTake` 一次。
     * 推送仍是快路径（毫秒级），轮询只在推送被卡住时兜底，平时几乎不触发。
     */
    var HTTP_POLL_TICK_MS = 250;
    var HTTP_POLL_MIN_AGE_MS = 1000;

    setInterval(function () {
        var now = Date.now();
        Object.keys(httpPending).forEach(function (reqId) {
            var slot = httpPending[reqId];
            if (!slot || now - (slot.t0 || 0) < HTTP_POLL_MIN_AGE_MS) return;
            settleHttp(reqId, slot);
        });
    }, HTTP_POLL_TICK_MS);

    /**
     * 原生回调入口：某个异步请求已有结果，来把 Promise 结掉。
     *
     * 结果串不随通知一起回来（那样要多经一次脚本转义 + 一次全量拷贝），
     * 而是这里同步调用 httpTake 现取。
     */
    window.__veneraHttpDone = function (reqId) {
        reqId = String(reqId);
        var slot = httpPending[reqId];
        if (!slot) {
            // 没人等（调用已被上层超时放弃）→ 结果丢弃，别留在原生 map 里
            try { B.httpTake(reqId); } catch (e) { /* ignore */ }
            return;
        }
        settleHttp(reqId, slot, true);
    };

    var Network = {
        sendRequest: function (method, url, headers, data) {
            var sp = splitHeaders(headers);
            // 对象 / 数组 body → JSON 串（对齐 Dio 的行为），并补 content-type
            var nb = normalizeBody(data);
            data = nb.data;
            if (nb.isJson && !hasHeader(sp.headers, 'content-type')) {
                sp.headers['content-type'] = 'application/json';
            }
            var isGet = String(method || 'GET').toUpperCase() === 'GET';
            var key = cacheKeyOf(method, url, sp.headers);
            if (isGet) {
                if (sp.cacheTime === 'no') {
                    // 明确要求重拉：先清掉旧缓存
                    var stale = httpCache.get(key);
                    if (stale) {
                        httpCacheChars -= stale.size;
                        httpCache.delete(key);
                    }
                } else {
                    var hit = cacheGet(key, sp.cacheTime);
                    // 命中直接给**裸对象**：await 一个对象与 await 一个 Promise 等价，
                    // 而同步写法的源（不 await 直接取 .body）也能正常拿到。
                    // 注意 withCacheFlag 返回的是 JSON 串（缓存里存的就是串），要解回来。
                    if (hit) return JSON.parse(withCacheFlag(hit));
                }
            }
            var run = function () {
                if (!wantAsync()) {
                    // 同步回落：返回裸对象，与改动前的行为完全一致
                    var sync = JSON.parse(httpSyncRaw(method, url, sp.headers, data, false));
                    if (sync.error) throw new Error(sync.error);
                    return { status: sync.status, headers: sync.headers || {}, body: sync.body, url: sync.url || url };
                }
                return httpAsyncCall(method, url, sp.headers, data, false).then(function (raw) {
                    var res = JSON.parse(raw);
                    if (res.error) throw new Error(res.error);
                    return { status: res.status, headers: res.headers || {}, body: res.body, url: res.url || url };
                });
            };
            // prevent-parallel：同一 URL 同时只跑一次，后来者共享同一个 Promise。
            // 只在异步桥下做 —— 同步桥返回的是裸对象，没有「在飞」的概念。
            var task = (sp.preventParallel && wantAsync()) ? shareInFlight(key, run) : run();
            if (!isGet) return task;
            // 同步桥（sync-style 源）返回的是裸对象：就地缓存后原样返回，
            // 绝不能包成 Promise —— 那些源是直接取 `.body` 的，包了就全坏。
            if (!task || typeof task.then !== 'function') {
                if (task && task.status >= 200 && task.status < 300 && typeof task.body === 'string') {
                    cachePut(key, JSON.stringify(task));
                }
                return task;
            }
            return task.then(function (res) {
                // 只缓存成功的 GET（4xx/5xx 缓存下来会把「临时抽风」变成 6 小时打不开）
                if (res && res.status >= 200 && res.status < 300 && typeof res.body === 'string') {
                    cachePut(key, JSON.stringify(res));
                }
                return res;
            });
        },
        fetchBytes: function (method, url, headers, data) {
            var sp = splitHeaders(headers);
            var nb = normalizeBody(data);
            data = nb.data;
            if (nb.isJson && !hasHeader(sp.headers, 'content-type')) {
                sp.headers['content-type'] = 'application/json';
            }
            var run = function () {
                if (!wantAsync()) {
                    var syncB = JSON.parse(httpSyncRaw(method, url, sp.headers, data, true));
                    if (syncB.error) throw new Error(syncB.error);
                    return { status: syncB.status, headers: syncB.headers || {}, body: b64ToBytes(syncB.bodyBase64 || '').buffer, url: syncB.url || url };
                }
                return httpAsyncCall(method, url, sp.headers, data, true).then(function (raw) {
                    var res = JSON.parse(raw);
                    if (res.error) throw new Error(res.error);
                    return { status: res.status, headers: res.headers || {}, body: b64ToBytes(res.bodyBase64 || '').buffer, url: res.url || url };
                });
            };
            // 二进制不走缓存（图片 / 索引文件太大，且源自己会按 range 精确取）
            return sp.preventParallel ? shareInFlight(cacheKeyOf(method, url, sp.headers), run) : run();
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
            // v1.7.5.0：源在运行时把 settings[name] **整个换成值** 的情况。
            // 典型是拷贝漫画的 refreshAppApi：`this.settings.base_url = 接口主机名`。
            // 旧实现只认 { default: ... } 对象，于是返回 null，URL 变成
            // `https://null/api/v3/...`（实测该源搜索全空）。官方运行时按值返回。
            if (def !== null && def !== undefined && typeof def !== 'object') return def;
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

    /**
     * 正在飞的 `loadInfo`：`源|id` → Promise（v1.8.9.4）。
     *
     * 详情页是**并发**问 `info` 和 `chapters` 的。以前 `chapters` 只在 `info` 已经
     * 完成时才命中 [INFO_CACHE]，撞上并发就变成把同一个 `loadInfo` 跑两遍 ——
     * 慢源（要翻页 / 二次请求）直接翻倍，实测顶到 30 秒上界，用户侧就是
     * 「点进去一直转圈」。上游只加载一次 ComicDetails，这里对齐：同源同 id 共享
     * 同一个 Promise，后来的调用等它，不重复发请求。
     */
    var INFO_INFLIGHT = Object.create(null);
    var INFO_CACHE_MAX = 64;
    var INFO_CACHE_ORDER = [];

    /**
     * 刚失败的 `loadInfo`：`源|id` → `{ message, at }`（v1.8.9.5）。
     *
     * 详情页是**串行**问 `info` 再问 `chapters` 的（见 ComicDetailViewModel：
     * 目录必须在详情之后取，legado 源会靠详情写入变量）。详情一旦卡到 30 秒上界
     * 失败，目录紧接着又把同一个 `loadInfo` 完整重跑一遍 —— 用户侧就是转圈一分钟。
     * 记一个短时负缓存，让紧随其后的 `chapters` 立刻拿到同一个错误，
     * 而不是再赌一次网络。
     */
    var INFO_FAIL = Object.create(null);
    var INFO_FAIL_TTL_MS = 5000;

    /**
     * 单次 `loadInfo` 的上限（v1.8.9.5）。
     *
     * 不加这条的话，源自己的 `loadInfo` 一旦永不返回（等一个永远不结的 Promise），
     * `INFO_INFLIGHT` 里那条记录就永远摘不掉 —— 之后这个作品的每一次 `info` /
     * `chapters` 都会共享同一个卡死的 Promise，**本次进程内永久打不开**。
     * 上界取得比 Kotlin 侧的 30s 宽，正常慢源不会被它误杀。
     */
    var LOAD_INFO_TIMEOUT_MS = 45000;

    /** 清掉某个源的全部详情负缓存（同步重试前必须清，见 [attempt]） */
    function clearInfoFail(key) {
        var prefix = key + '|';
        Object.keys(INFO_FAIL).forEach(function (k) {
            if (k.indexOf(prefix) === 0) delete INFO_FAIL[k];
        });
    }

    function loadInfoShared(src, key, id) {
        var cacheKey = key + '|' + id;
        if (INFO_CACHE[cacheKey]) return Promise.resolve(INFO_CACHE[cacheKey]);
        var failed = INFO_FAIL[cacheKey];
        if (failed) {
            if (Date.now() - failed.at < INFO_FAIL_TTL_MS) {
                return Promise.reject(new Error(failed.message));
            }
            delete INFO_FAIL[cacheKey];
        }
        var inflight = INFO_INFLIGHT[cacheKey];
        if (inflight) return inflight;
        inflight = (async function () {
            try {
                if (!src.comic || typeof src.comic.loadInfo !== 'function') {
                    throw new Error('该源没有实现详情加载（comic.loadInfo）');
                }
                var d = await Promise.race([
                    src.comic.loadInfo.call(src, id),
                    new Promise(function (_resolve, reject) {
                        setTimeout(function () {
                            reject(new Error('详情加载超时（源 ' + (LOAD_INFO_TIMEOUT_MS / 1000) + 's 未返回）'));
                        }, LOAD_INFO_TIMEOUT_MS);
                    }),
                ]);
                INFO_CACHE[cacheKey] = d;
                INFO_CACHE_ORDER.push(cacheKey);
                while (INFO_CACHE_ORDER.length > INFO_CACHE_MAX) {
                    delete INFO_CACHE[INFO_CACHE_ORDER.shift()];
                }
                return d;
            } catch (e) {
                INFO_FAIL[cacheKey] = { message: msgOf(e), at: Date.now() };
                throw e;
            } finally {
                // 失败也要摘掉，否则一次失败会让这个 id 永远拿到同一个 rejected Promise
                delete INFO_INFLIGHT[cacheKey];
            }
        })();
        INFO_INFLIGHT[cacheKey] = inflight;
        return inflight;
    }
    /**
     * 逐图「失败回退」表（v1.7.1.0）。
     *
     * 源可以在 onImageLoad 的返回值里给出 `onLoadFailed`（函数），
     * 它在图片 404/403 时返回**下一个镜像地址**。Venera 官方会自己调它重试；
     * 我们这边图片由 Coil 直接加载，函数也无法跨桥传递，因此这里按地址把函数
     * 存起来，再由 `retryImage` 操作按需调用 —— 否则「5 个内置备用域名」等于白给，
     * 第一个域名被墙就整章图都加载不出来。
     */
    var IMG_RETRY = {};

    /**
     * `search.loadNext` 型源的**游标链缓存**。
     *
     * 这类源的续页靠 token，没有页码概念；而 App 的列表接口是页码驱动的
     * （`search(keyword, page)`）。要拿第 N 页就必须从首页 token 顺着走 N-1 次。
     * 这里把走过的 token 链按 `源|关键词|选项` 缓存下来，翻页时只补走新增段，
     * 不会每页都从头重走。超过 [NEXT_CHAINS_MAX] 条时丢弃最早插入的。
     */
    var NEXT_CHAINS = {};
    var NEXT_CHAINS_MAX = 64;
    var NEXT_CHAINS_ORDER = [];

    /**
     * 丢弃某个源的全部派生状态。
     *
     * 什么时候会重新注册同一个 key：源码被编辑 / 更新后 `putSource` 会清掉 Kotlin
     * 侧的注册标记，下一次调用重新求值。旧实现只换 `SOURCES[key]`，`INIT_STATE` /
     * `INFO_CACHE` / `NEXT_CHAINS` 里全是**旧实例**留下的东西：新实例永远等不到
     * 自己的 init，详情还会直接命中旧实例的缓存。这里一并清干净。
     */
    function resetSourceState(key) {
        key = String(key);
        delete INIT_STATE[key];
        delete syncOnly[key];
        delete syncSuspect[key];
        var prefix = key + '|';
        Object.keys(INFO_CACHE).forEach(function (k) {
            if (k.indexOf(prefix) === 0) delete INFO_CACHE[k];
        });
        Object.keys(INFO_INFLIGHT).forEach(function (k) {
            if (k.indexOf(prefix) === 0) delete INFO_INFLIGHT[k];
        });
        Object.keys(INFO_FAIL).forEach(function (k) {
            if (k.indexOf(prefix) === 0) delete INFO_FAIL[k];
        });
        Object.keys(NEXT_CHAINS).forEach(function (k) {
            if (k.indexOf(prefix) === 0) delete NEXT_CHAINS[k];
        });
    }

    function registerSource(key) {
        key = String(key);
        var code = B.getSource(key);
        if (!code) throw new Error('未找到源代码：' + key);
        var m = /class\s+([A-Za-z_$][\w$]*)\s+extends\s+ComicSource/.exec(code);
        if (!m) throw new Error('不是有效的 Venera 源（未找到 class X extends ComicSource）');
        var cls = m[1];
        var factory = new Function(code + '\n;return new ' + cls + '();');
        var instance = factory();
        instance.key = instance.key || key;
        resetSourceState(key);
        SOURCES[key] = instance;
        // 与上游对齐：解析完成即触发 init，不等到第一次调用才现场跑
        fireInit(key, instance);
        return instance;
    }

    /**
     * key -> `{ state, promise }`，`state ∈ 'pending' | 'done' | 'failed'`。
     *
     * 与上游对齐（`venera/lib/foundation/comic_source/parser.dart`）：源在**解析
     * 完成的那一刻**就触发 `init()`，而不是等到第一次调用才现场跑 —— 上游甚至是
     * `Future.delayed(50ms, () => runCode("...init()"))`，触发后根本不 await。
     * 这里同样「注册即触发」，让 init 尽早拿到那几百毫秒 / 几秒的提前量。
     */
    var INIT_STATE = Object.create(null);

    /**
     * 失败后愿意**为 init 补等**多久，以及哪些操作值得补等（毫秒）。
     *
     * 为什么不是「调用前先等 init」：`init` 只是少数源才需要的（刷新域名表 /
     * 登录态），大部分源的 `loadInfo` 根本不碰它。调用前一律等，等于让**所有**源
     * 都替一个可能永远不结的 Promise 付时间 —— 用户侧就是「点进去一直转圈」。
     * 所以改成「先直接跑，失败了再看是不是 init 还没完成，是的话补等一次再重试」：
     * - init 已完成 → 和以前一样，零额外开销；
     * - init 不需要 → 第一次就成功，连等都不用等；
     * - init 确实需要 → 第一次毫秒级失败（`this.apiDomains[0]` 取不到），
     *   补等 init 后重试一次即成功；
     * - init 卡死 → 也只等一次补等上界就给出明确错误，不会无限拖。
     *
     * 后台操作（召回 / 分类枚举）补等时间更短：它们讲究「出多少算多少」，
     * 一个源的 init 不该拖住整批。
     */
    var INIT_RETRY_WAIT_INTERACTIVE_MS = 15000;
    var INIT_RETRY_WAIT_BACKGROUND_MS = 5000;

    function initRetryWaitForOp(op) {
        if (op === 'info' || op === 'chapters' || op === 'content') return INIT_RETRY_WAIT_INTERACTIVE_MS;
        if (op === 'search' || op === 'categories' || op === 'categoryComics' || op === 'explore') {
            return INIT_RETRY_WAIT_BACKGROUND_MS;
        }
        return 0;
    }

    /** init 失败后允许重试的冷却时间：源站一时不通不该让这个源本次进程内永久不可用 */
    var INIT_RETRY_COOLDOWN_MS = 30000;

    /** 触发（或复用）某个源的 init，返回它的 Promise；失败只记日志，不向外抛 */
    function fireInit(key, src) {
        var st = INIT_STATE[key];
        if (st) {
            // 已经跑过、正在跑、或刚失败还没到冷却时间 → 复用旧结果
            if (st.state !== 'failed') return st.promise;
            if (Date.now() - (st.at || 0) < INIT_RETRY_COOLDOWN_MS) return st.promise;
        }
        st = { state: 'pending', promise: null, at: Date.now() };
        INIT_STATE[key] = st;
        st.promise = (async function () {
            try {
                if (typeof src.init === 'function') await src.init();
                st.state = 'done';
            } catch (e) {
                st.state = 'failed';
                st.at = Date.now();
                // 与上游一致：init 失败不让整个源不可用（很多源的 init 只是刷新域名表）
                log('warning', 'init 失败', ((e && e.message) || e));
            }
        })();
        return st.promise;
    }

    /**
     * 取（并按需构造 / 触发初始化）源实例。
     *
     * **不等 init**（v1.8.9.5）：只负责把 init 跑起来。要不要为 init 补等，
     * 由 [runCall] 在**失败之后**按操作类型决定（见 [initRetryWaitForOp]）。
     */
    async function ensureSource(key) {
        var src = SOURCES[key];
        if (!src) src = registerSource(key);
        // 幂等：已在跑就直接复用；失败且过了冷却时间会重新触发一次
        fireInit(key, src);
        return src;
    }

    /** 取「键值对」序列，兼容 Map / 普通对象 / 数组 */
    function entriesOf(obj) {
        var out = [];
        if (!obj) return out;
        if (typeof obj.entries === 'function') {
            var it = obj.entries();
            var step = it.next();
            while (!step.done) {
                out.push(step.value);
                step = it.next();
            }
            return out;
        }
        if (Array.isArray(obj)) {
            for (var i = 0; i < obj.length; i++) out.push([String(i), obj[i]]);
            return out;
        }
        if (typeof obj === 'object') {
            for (var k in obj) {
                if (Object.prototype.hasOwnProperty.call(obj, k)) out.push([k, obj[k]]);
            }
        }
        return out;
    }

    /**
     * 判断章节的「值」是不是**分组容器**，而不是单独一章。
     *
     * Venera 的基础约定是 `chapters = Map<epId, 标题>`，但源可以写成两层
     * `Map<分组名, Map<epId, 标题>>`——漫画人（manhuaren）就按「话数区间」分组。
     * 两层结构若不当心展开，内层 Map 会被直接 `String()` 成 `[object Map]`，
     * 目录里就整列显示成 `[object Map]`。
     *
     * 单个章节的值长这样：字符串，或带 `title` / `name` / `url` / `href` 的对象。
     * 分组容器的值是 Map、数组，或「每个值都是字符串 / 对象」的纯对象。
     */
    function isChapterGroup(v) {
        if (!v || typeof v !== 'object') return false;
        if (typeof v.entries === 'function') return true;
        if (Array.isArray(v)) return false;
        if (typeof v.title === 'string' || typeof v.name === 'string' ||
            typeof v.url === 'string' || typeof v.href === 'string') return false;
        var ks = Object.keys(v);
        if (ks.length === 0) return false;
        for (var i = 0; i < ks.length; i++) {
            var val = v[ks[i]];
            if (val === null || val === undefined) return false;
            if (typeof val !== 'object' && typeof val !== 'string') return false;
        }
        return true;
    }

    /** 分组名没信息量时（纯分隔标签）不往章节标题上拼 */
    var GENERIC_GROUP_NAMES = ['连载', '正文', '默认', '全部', '目录', '章节', '漫画', '本话', 'default'];

    /**
     * 「看起来是卷号」的分组名才拼前缀。
     *
     * 拼上去有两个好处：目录里能看出这一话属于哪一卷；app 的
     * `ComicChapterVolume` 正是从标题里解析卷号（第N卷 / 第N卷 / Vol.N）来做分卷展示的，
     * 拼上就能自动分组。反过来，「第1-30话」这类区间标签拼上去只会变噪音，所以不拼。
     */
    var VOLUME_NAME_RES = [
        /第\s*[0-9０-９]+\s*卷/,
        /第\s*[零一二三四五六七八九十百千万两]+\s*卷/,
        /卷\s*[0-9０-９]+/,
        /vol\.?\s*[0-9０-９]+/i,
    ];

    function groupLabel(name) {
        var s = String(name === undefined || name === null ? '' : name).trim();
        if (!s) return '';
        for (var i = 0; i < GENERIC_GROUP_NAMES.length; i++) {
            if (s === GENERIC_GROUP_NAMES[i]) return '';
        }
        for (var j = 0; j < VOLUME_NAME_RES.length; j++) {
            if (VOLUME_NAME_RES[j].test(s)) return s;
        }
        return '';
    }

    /** 给分组内的章节标题加分组前缀；标题里已经含分组名、或标题取不出来时不动 */
    function labelChapter(label, value) {
        if (!label) return value;
        var t = chapterTitle(value);
        if (!t) return label;
        if (t.indexOf(label) >= 0) return value;
        return label + ' ' + t;
    }

    /**
     * 把源返回的 `chapters` 拍平成 `[epId, 标题]` 对数组。
     *
     * 支持：`Map<epId, 标题>` / 普通对象 / `[[epId, 标题], …]` /
     * **两层嵌套分组**（`Map<分组名, Map<epId, 标题>>`）。
     * 嵌套时把分组名拼到标题前（`第1卷 第3话`）。
     */
    function chaptersToPairs(chapters) {
        var out = [];
        // 已经是 `[[epId, 标题], …]` 的数组形态：直接用，别被当成下标键
        if (Array.isArray(chapters) && chapters.length > 0 && Array.isArray(chapters[0])) {
            for (var n = 0; n < chapters.length; n++) {
                var p = chapters[n];
                if (!p || p.length === 0) continue;
                out.push([p[0], p.length > 1 ? p[1] : '']);
            }
            return out;
        }
        var direct = entriesOf(chapters);
        for (var i = 0; i < direct.length; i++) {
            var k = direct[i][0];
            var v = direct[i][1];
            if (!isChapterGroup(v)) {
                out.push([k, v]);
                continue;
            }
            var label = groupLabel(k);
            var inner = entriesOf(v);
            for (var j = 0; j < inner.length; j++) {
                out.push([inner[j][0], labelChapter(label, inner[j][1])]);
            }
        }
        return out;
    }

    /**
     * 单章图源的章节兜底（v1.7.2.0）。
     *
     * nhentai / ehentai / galleriesindex 这类**图库站**的 `loadInfo` 只返回
     * `thumbnails`（页面缩略图）与 `maxPage`，**根本不返回 `chapters`** ——
     * 因为「一个图库 = 一章」，页面列表就是章节本身。
     *
     * 我们的详情页依赖 `chapters` 渲染目录，于是这些源一律显示
     * 「没有获取到章节 / 该源没有返回任何章节，可能源已失效」：**一本都打不开**，
     * 而且因为 `loadInfo` 其实成功返回了（没抛异常），错误提示还会把用户
     * 往「源已失效」的方向误导。
     *
     * 这里探测「没有 chapters 但有页面信息」 → 合成一章「全集」。
     * `loadEp(comicId, epId)` 对这类源本来就忽略 epId（直接返回全部页面），
     * 传 `"0"` 即可。
     */
    function galleryFallbackChapters(d) {
        if (!d) return [];
        var thumbs = d.thumbnails;
        var hasThumbs = Array.isArray(thumbs) && thumbs.length > 0;
        var maxPage = Number(d.maxPage || 0);
        if (!hasThumbs && !(maxPage > 0)) return [];
        var pages = hasThumbs ? thumbs.length : maxPage;
        return [['0', pages > 0 ? '全集（' + pages + ' 页）' : '全集']];
    }

    /** `chaptersToPairs` + 单章图源兜底：得到最终的 `[epId, 标题]` 对数组 */
    function chaptersOf(d) {
        var pairs = chaptersToPairs(d && d.chapters);
        if (pairs.length === 0) pairs = galleryFallbackChapters(d);
        // v1.9.2.3：`chapters` 为 null / undefined 时，venera 把这部作品当**单话**处理
        // （不写章节表的图源，如紳士漫畫的非系列作品）。以前这里返回空数组，
        // App 详情页就是「0 话」，根本点不进去。
        // 注意只兜 null/undefined：`{}` / `[]` 是源**明确说没有章节**，不能凭空造一话。
        if (pairs.length === 0 && d && (d.chapters === null || d.chapters === undefined)) {
            pairs = [['', '全一话']];
        }
        return pairs;
    }

    function chapterTitle(v) {
        if (v === null || v === undefined) return '';
        if (typeof v === 'string') return v;
        if (typeof v === 'number' || typeof v === 'boolean') return String(v);
        if (typeof v === 'object') {
            if (typeof v.title === 'string') return v.title;
            if (typeof v.name === 'string') return v.name;
            // 数组：可能是 [epId, 标题] 之类的对，取后面的标题位
            if (Array.isArray(v)) {
                return v.length > 1 ? chapterTitle(v[1]) : chapterTitle(v[0]);
            }
            // 兜底：万一还有没展开的分组容器，也绝不返回 '[object Object]' / '[object Map]'
            if (isChapterGroup(v)) {
                var subs = [];
                var pairs = entriesOf(v);
                for (var i = 0; i < pairs.length; i++) {
                    var t = chapterTitle(pairs[i][1]);
                    if (t) subs.push(t);
                }
                return subs.join(' / ');
            }
        }
        return '';
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
    async function serializeImages(list, src, comicId, epId, topHeaders) {
        var urls = [];
        var headers = {};
        var scripts = {};
        // v1.7.1.0：loadEp 也可能返回 `{ images: [...], headers: {...} }` —— 顶层请求头。
        // 以前只从**单张图片对象**里取 headers、且只在 headers 还空着时才取，
        // 于是「顶层头」与「后续图片自带的头」全被丢弃，防盗链站点直接 403 →「图片加载失败」。
        if (topHeaders && typeof topHeaders === 'object') {
            for (var tk in topHeaders) {
                if (topHeaders[tk] != null) headers[tk] = String(topHeaders[tk]);
            }
        }
        var arr = list || [];
        for (var i = 0; i < arr.length; i++) {
            var item = arr[i];
            if (typeof item === 'string') {
                urls.push(item);
                continue;
            }
            if (!item) continue;
            if (item.url) urls.push(item.url);
            if (item.headers && Object.keys(item.headers).length) {
                // 逐图头**补齐**已有键（不覆盖顶层头），而不是「只有第一份生效」
                for (var hk in item.headers) {
                    if (item.headers[hk] != null && headers[hk] == null) {
                        headers[hk] = String(item.headers[hk]);
                    }
                }
            }
        }
        // onImageLoad：源用它补签名 / 防盗链头 / 二次解析真实地址 / 给出乱序还原脚本
        //
        // **必须并发**（v1.8.9.5）。图库类源（`loadEp` 只返回页码，真实地址在
        // `onImageLoad` 里逐张解析）一章几十页就是几十次往返 —— 以前这里是
        // 一个 `await` 接一个 `await` 的串行循环，一章要跑上百次请求、好几分钟，
        // 上层早就超时了，用户侧就是「章节一直转圈 / 加载不出内容」。
        // 现在按 [IMAGE_RESOLVE_CONCURRENCY] 并发解析，顺序保持不变（结果按下标回填）。
        //
        // **但不再全量预解析**（v1.9.2.3）：官方是在**图片真正要加载时**才调
        // `onImageLoad`（`lib/network/images.dart:143` 的 `getImageLoadingConfig`），
        // 而这里以前是一次性把整章都解析完。图库类源一章动辄几百页 —— 实测某图站
        // 一个 429 页的章节，光是预解析就要发 429 次 POST，直接撞上调用硬上限，
        // 用户侧就是「章节打不开」；就算不超时，也会因为瞬时请求量被源站限流。
        // 现在只预解析前 [IMAGE_EAGER_RESOLVE_LIMIT] 张（覆盖首屏 + 若干页滚动），
        // 其余的交给 `retryImage` 按需解析（见 [lazyImageToken]）。
        var onLoad = src.comic && src.comic.onImageLoad;
        if (typeof onLoad === 'function' && urls.length) {
            // 未解析前的原始 key（页码 / 原始地址），惰性解析时要原样还给源
            var rawUrls = urls.slice();
            var resolveOne = async function (orig) {
                var finalUrl = orig;
                try {
                    var r = await onLoad.call(src, orig, comicId, epId);
                    if (typeof r === 'string') {
                        finalUrl = r;
                    } else if (r && typeof r === 'object') {
                        if (typeof r.url === 'string' && r.url) finalUrl = r.url;
                        if (r.headers && Object.keys(r.headers).length) {
                            // v1.7.1.0：onImageLoad 给出的头是最具体的（常带签名 / 时效 token），
                            // 直接并入并覆盖同名键 —— 以前只在 headers 全空时才用，等于白给。
                            for (var rk in r.headers) {
                                if (r.headers[rk] != null) headers[rk] = String(r.headers[rk]);
                            }
                        }
                        // modifyImage 多为代码字符串；函数无法跨桥传递，只能跳过
                        if (typeof r.modifyImage === 'string' && r.modifyImage) {
                            scripts[finalUrl] = r.modifyImage;
                        }
                        // 失败回退函数存起来，供 retryImage 按需调用（见 IMG_RETRY 说明）
                        if (typeof r.onLoadFailed === 'function') {
                            IMG_RETRY[finalUrl] = r.onLoadFailed;
                        }
                    }
                } catch (e) {
                    log('warning', 'onImageLoad', String(e));
                }
                return finalUrl;
            };
            // 分批推进 + **时间预算**（v1.9.2.4）。此前只按张数卡（前 24 张），
            // 但 `onImageLoad` 的成本因源而异：
            //   - 只补 referer / 拼地址的源（jcomic、wnacg 这类）零网络开销，
            //     整章几十张瞬间就能解析完，根本不需要惰性 —— 强行惰性只会让
            //     第 25 张之后全部落到按需解析那条路上，一旦那条路出问题，
            //     用户看到的就是「后半章一直转圈」；
            //   - 真正要逐张发请求的源（几百页的图库）才是惰性要救的对象。
            // 所以改成「预算内尽量多解析，超预算再惰性」：免费的一次解析完整章，
            // 昂贵的自然在 [IMAGE_EAGER_BUDGET_MS] 处停下。
            var resolved = [];
            var cursor = 0;
            var eagerStart = Date.now();
            while (cursor < rawUrls.length && cursor < IMAGE_EAGER_RESOLVE_LIMIT) {
                if (cursor > 0 && Date.now() - eagerStart > IMAGE_EAGER_BUDGET_MS) break;
                var batchEnd = Math.min(cursor + IMAGE_RESOLVE_BATCH, rawUrls.length);
                var part = await mapLimit(
                    rawUrls.slice(cursor, batchEnd),
                    IMAGE_RESOLVE_CONCURRENCY,
                    resolveOne
                );
                for (var pi = 0; pi < part.length; pi++) resolved.push(part[pi]);
                cursor = batchEnd;
            }
            urls = resolved;
            // 预算外 / 硬上限外的图片改成占位地址，进视口时再解析
            for (var lj = cursor; lj < rawUrls.length; lj++) {
                urls.push(lazyImageToken(comicId, epId, rawUrls[lj]));
            }
        }
        return { images: urls, headers: headers, scripts: scripts };
    }

    /** 逐张解析图片地址的并发度：够快，又不至于把 HTTP 闸门（24）挤满 */
    var IMAGE_RESOLVE_CONCURRENCY = 8;

    /**
     * 章节打开时**最多预解析**多少张图的真实地址（v1.9.2.3 引入，v1.9.2.4 放宽）。
     *
     * 这是**硬上限**，不是常态闸门 —— 常态由 [IMAGE_EAGER_BUDGET_MS] 的预算控制。
     * 早期这里取 24（≈ 首屏 + 上下滚动余量），但「张数」并不能反映 `onImageLoad`
     * 的成本：只补 referer / 拼地址的源零开销，一张 43 页的章节解析完整章也就几毫秒，
     * 却因为卡在 24 张被迫让后 19 张走按需解析 —— 那条路一旦出问题，用户看到的就是
     * 「后半章一直转圈」。现在放宽到 300 只是兜底防呆，真正决定停在哪的是时间预算。
     *
     * 为什么必须有上限：官方是「加载哪张解析哪张」，我们是「打开章节就把整章解析完」。
     * 实测某图站 429 页的章节，全量预解析要 429 次 POST，直接撞上 90s 的调用硬上限
     * —— 章节根本打不开；即便不超时，瞬时几百次请求也容易被限流。
     */
    var IMAGE_EAGER_RESOLVE_LIMIT = 300;

    /**
     * 预解析的时间预算（v1.9.2.4）。
     *
     * 分批推进，每批开始前看一眼已耗时：超预算就停、剩下的转惰性。
     * 免费型 `onImageLoad`（jcomic / wnacg 这类只补 referer 的）整章都在预算内跑完；
     * 逐张发请求的图库型源自然在 1.5s 左右停下，与 v1.9.2.3 的 24 张量级相当。
     */
    var IMAGE_EAGER_BUDGET_MS = 1500;

    /** 预解析的批次大小：每批做完检查一次预算，避免预算判断被单批拖长 */
    var IMAGE_RESOLVE_BATCH = 8;

    /** 惰性图片占位地址的前缀（见 [lazyImageToken]） */
    var LAZY_IMAGE_PREFIX = 'venera-image:';

    /**
     * 生成一张「还没解析真实地址」的占位地址。
     *
     * 这个串会作为图片 URL 一路传到 UI：Coil 打不开（未知 scheme，本地立刻失败，
     * 不会发网络请求）→ 上层 `onLoadFailed` → `retryImage` 收到它 → 这里解析出
     * 真实地址并回填。等于借用「备用镜像」那条现成链路做按需解析，不必改 UI 主流程。
     */
    function lazyImageToken(comicId, epId, key) {
        var payload = JSON.stringify([comicId === undefined ? null : comicId,
            epId === undefined ? null : epId, key]);
        return LAZY_IMAGE_PREFIX + encodeURIComponent(payload);
    }

    /** 解析惰性占位地址；不是占位地址时返回 null */
    function parseLazyImageToken(token) {
        var s = String(token || '');
        if (s.indexOf(LAZY_IMAGE_PREFIX) !== 0) return null;
        try {
            var parts = JSON.parse(decodeURIComponent(s.slice(LAZY_IMAGE_PREFIX.length)));
            if (!parts || parts.length < 3) return null;
            return { comicId: parts[0], epId: parts[1], key: parts[2] };
        } catch (e) {
            return null;
        }
    }

    /**
     * 按并发上限跑一个异步映射，**结果顺序与原数组一致**。
     *
     * 用于「一张图一次往返」的解析：串行太慢（几十页要几分钟），无上限又会把
     * 并发闸门挤爆。任一任务抛错都按调用方自己的 try/catch 处理，这里不吞。
     */
    async function mapLimit(items, limit, fn) {
        var out = new Array(items.length);
        var next = 0;
        var workers = [];
        var n = Math.min(limit, items.length);
        for (var w = 0; w < n; w++) {
            workers.push((async function () {
                for (;;) {
                    var i = next++;
                    if (i >= items.length) return;
                    out[i] = await fn(items[i], i);
                }
            })());
        }
        await Promise.all(workers);
        return out;
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
                // 空串而不是 null（v1.8.9.4）：源里常写 `options[0].type` /
                // `options[0].split(...)` 这类直接取属性的写法，给 null 会抛
                // `Cannot read properties of undefined (reading 'type')`，
                // 整次搜索直接失败（真机日志里出现过）。
                out.push('');
                continue;
            }
            var first = String(opts[0]);
            var dash = first.indexOf('-');
            out.push(dash >= 0 ? first.slice(0, dash) : first);
        }
        return out;
    }

    /**
     * `tags` 里这些分组是**元信息**不是题材，摊平后要丢掉
     * （Venera 的 `ComicDetails.tags` 是 `{分组名: [标签…]}`，
     * kavita 用「作者 / 类型 / 标签」，包子漫画用「作者 / 标签」）。
     */
    var TAG_GROUP_SKIP = ['作者', '作者名', '别名', '提示', 'author', 'authors'];

    /** 标签元素 → 名字；只有字符串（或带 name/title/label 的对象）才算标签 */
    function tagName(x) {
        if (x === null || x === undefined) return '';
        if (typeof x === 'string') return x.trim();
        if (typeof x === 'object') {
            var n = x.name || x.title || x.label || x.tag || x.value;
            return typeof n === 'string' ? n.trim() : '';
        }
        // 数字 / 布尔是坏数据，不当标签
        return '';
    }

    /**
     * 把源返回的 `tags` 归一成**字符串数组**。
     *
     * 两种写法在真实源里都很常见：
     * - 数组：`["玄幻", "热血"]`（搜索列表结果基本都是这种）
     * - 对象：`{ 作者: ["xx"], 标签: ["玄幻", "热血"] }` —— 这是 Venera
     *   `ComicDetails` / `Comic` 的标准结构，**detail 响应几乎全是它**。
     *
     * 以前 app 侧直接按数组解析，遇到对象就整条解析失败（`getAsJsonArray`
     * 抛 IllegalStateException），漫画源的标签与详情一起没了。
     * 这里统一摊平，元信息分组（作者等）丢掉。
     */
    function flattenTags(v) {
        var out = [];
        var add = function (x) {
            var s = tagName(x);
            if (s && out.indexOf(s) < 0) out.push(s);
        };
        if (!v) return out;
        if (Array.isArray(v)) {
            for (var i = 0; i < v.length; i++) add(v[i]);
            return out;
        }
        if (typeof v === 'object') {
            var pairs = entriesOf(v);
            for (var j = 0; j < pairs.length; j++) {
                var group = String(pairs[j][0] === undefined ? '' : pairs[j][0]).trim();
                if (TAG_GROUP_SKIP.indexOf(group) >= 0) continue;
                var val = pairs[j][1];
                if (Array.isArray(val)) {
                    for (var k = 0; k < val.length; k++) add(val[k]);
                } else {
                    add(val);
                }
            }
        }
        return out;
    }

    /**
     * 把源返回的漫画数组序列化成 app 可消费的结构。
     *
     * `tags` 必须带上：不少源（包子漫画等）在列表里就给出该作品的题材标签，
     * app 侧拿它做详情页标签栏、也用来判断「同时属于多个标签」（v1.7.0.0）。
     */
    function serializeComics(comics) {
        var list = [];
        var arr = comics || [];
        for (var i = 0; i < arr.length; i++) {
            var c = arr[i];
            if (!c) continue;
            list.push({
                id: String(c.id === undefined || c.id === null ? '' : c.id),
                title: c.title || '',
                subTitle: c.subTitle || c.subtitle || '',
                cover: c.cover || '',
                description: c.description || '',
                tags: flattenTags(c.tags),
            });
        }
        return list;
    }

    /**
     * 把「分类项」归一成 `{name, param, itemType}`。
     *
     * 分类项的三种写法（真实源里都出现过）：
     * - 纯字符串：`"恋爱"`（param 取同下标的 `categoryParams[i]` / `groupParam`）
     * - 老式对象：`{ label: "全彩", target: { action/page, param/attributes.param, keyword } }`
     * - 动态 loader 的返回值：与上面两种同形，额外可能是 `{ name, param }`
     *
     * `itemType` 决定 app 侧怎么消费：`search` = 该分类没有分类页、退回搜索；
     * 其余一律当分类页处理（Venera 里就是 `categoryComics.load`）。
     */
    function normalizeCategoryItem(raw, fallbackParam, fallbackType) {
        if (raw === null || raw === undefined) return null;
        if (typeof raw === 'string') {
            var s = raw.trim();
            if (!s) return null;
            return { name: s, param: fallbackParam, itemType: fallbackType || 'category' };
        }
        if (typeof raw !== 'object') return null;
        var target = raw.target || {};
        var name = raw.label || raw.name || raw.title || target.keyword || '';
        if (!name) return null;
        var attributes = target.attributes || {};
        var param = null;
        if (target.param !== undefined && target.param !== null) param = target.param;
        else if (attributes.param !== undefined && attributes.param !== null) param = attributes.param;
        else if (raw.param !== undefined && raw.param !== null) param = raw.param;
        else param = fallbackParam;
        // action: 'category' → 分类页；'search' / 带 keyword 的 → 搜索
        var type = fallbackType || 'category';
        if (target.action === 'search' || target.page === 'search') type = 'search';
        else if (target.action === 'category' || target.page === 'category') type = 'category';
        var keyword = (target.keyword !== undefined && target.keyword !== null)
            ? String(target.keyword) : String(name);
        return { name: String(name), param: param, itemType: type, keyword: keyword };
    }

    /**
     * 解析源声明的 `category = { parts: [...] }`（含 `type:"dynamic"` 的 loader）。
     *
     * 同时带上 `options`：分类页的**其它过滤器**（`categoryComics.optionList` 各组）
     * 的默认选择，按位置取用（与 [defaultOptions] 同规则，`"all-全部"` → `"all"`）。
     * app 侧把这份默认值随分类入口一起存下来，点标签时才能凑出一次合法调用。
     */
    async function collectCategoryParts(src) {
        var cat = src.category;
        var options = defaultOptions(src.categoryComics);
        if (!cat || !Array.isArray(cat.parts) || !cat.parts.length) {
            return { title: (cat && cat.title) || '', parts: [], options: options };
        }
        var parts = [];
        for (var i = 0; i < cat.parts.length; i++) {
            var part = cat.parts[i];
            if (!part) continue;
            var rawItems = [];
            try {
                if (part.type === 'dynamic' && typeof part.loader === 'function') {
                    rawItems = await part.loader.call(src) || [];
                } else {
                    rawItems = part.categories || [];
                }
            } catch (e) {
                // loader 可能需要登录 / 网络：单个 part 失败不能拖垮整张分类表
                log('warning', 'category.parts[' + i + ']', String(e));
                rawItems = part.categories || [];
            }
            if (!Array.isArray(rawItems)) rawItems = [];
            var params = Array.isArray(part.categoryParams) ? part.categoryParams : [];
            var group = (part.groupParam === undefined) ? null : part.groupParam;
            var type = part.itemType || 'category';
            var items = [];
            for (var j = 0; j < rawItems.length; j++) {
                var fallbackParam = params.length > j ? params[j] : group;
                var item = normalizeCategoryItem(rawItems[j], fallbackParam, type);
                if (item) items.push(item);
            }
            if (items.length) {
                parts.push({ name: String(part.name || ''), items: items });
            }
        }
        return { title: cat.title || '', parts: parts, options: options };
    }

    async function doCall(key, op, args) {
        var src = await ensureSource(key);
        switch (op) {
            case 'ping':
                return { ok: true, name: src.name, version: src.version };

            // 图片加载失败 → 向源要下一个镜像地址（v1.7.1.0，见 IMG_RETRY 说明）
            // v1.9.2.3：也负责**惰性解析**——章节打开时没预解析的图片会带一个
            // `venera-image:` 占位地址进来，这里现解析（见 lazyImageToken）。
            case 'retryImage': {
                var lazy = parseLazyImageToken(args[0]);
                if (lazy) {
                    var lazyOnLoad = src.comic && src.comic.onImageLoad;
                    if (typeof lazyOnLoad !== 'function') return null;
                    var lr;
                    try {
                        lr = await lazyOnLoad.call(src, lazy.key, lazy.comicId, lazy.epId);
                    } catch (e) {
                        log('warning', 'onImageLoad', String(e));
                        return null;
                    }
                    var lazyUrl = typeof lr === 'string' ? lr : (lr && lr.url);
                    if (!lazyUrl) return null;
                    var lazyHeaders = (lr && typeof lr === 'object' && lr.headers) ? lr.headers : {};
                    // 乱序还原脚本必须一并回传，否则按需解析出来的图会显示成乱序
                    var lazyScript = (lr && typeof lr === 'object' && typeof lr.modifyImage === 'string')
                        ? lr.modifyImage : null;
                    if (lr && typeof lr === 'object' && typeof lr.onLoadFailed === 'function') {
                        IMG_RETRY[lazyUrl] = lr.onLoadFailed;
                    }
                    return { url: lazyUrl, headers: lazyHeaders, script: lazyScript };
                }
                var retryFn = IMG_RETRY[args[0]];
                if (typeof retryFn !== 'function') return null;
                var nr = null;
                try {
                    nr = await retryFn();
                } catch (e) {
                    log('warning', 'onLoadFailed', String(e));
                }
                if (!nr || !nr.url) {
                    delete IMG_RETRY[args[0]];
                    return null;
                }
                // 把回退函数转挂到新地址上：源里的 tried 计数器会继续前进，
                // 于是「第 2、3…个镜像」还能接着回退（否则一次就到底）。
                IMG_RETRY[nr.url] = retryFn;
                delete IMG_RETRY[args[0]];
                return { url: nr.url, headers: nr.headers || {}, script: null };
            }

            case 'search': {
                // 契约以**源代码**为准（venera-configs 实测多个源一致）：
                //   load     = (keyword, options, page)
                //   loadNext = (keyword, options, next)
                // 注意 options 在**第 2 位**、page/next 在第 3 位。
                // 上游 Dart 侧 SearchFunction typedef 写的是 (keyword, page, options)，
                // 那是桥接层内部签名，不能拿来对齐 JS 源，否则 page 会拿到选项数组。
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
                    // loadNext 型源：第 N 页的 token 要顺着链走 N-1 次才能拿到。
                    // 把走过的 token 缓存起来，翻页时只补走新增的那一段。
                    var chainKey = key + '|' + args[0] + '|' + opts.join(',');
                    var chain = NEXT_CHAINS[chainKey] || [null]; // chain[0] = 首页 token
                    var target = pageNo - 1;
                    var guard = 0;
                    while (chain.length <= target && guard++ < 200) {
                        var prevToken = chain[chain.length - 1];
                        var pageRes = await src.search.loadNext.call(src, args[0], opts, prevToken);
                        var nextToken = pageRes && (pageRes.nextToken || pageRes.next);
                        if (!nextToken) break; // 没有下一页了
                        chain.push(nextToken);
                    }
                    if (!NEXT_CHAINS[chainKey]) {
                        NEXT_CHAINS_ORDER.push(chainKey);
                        while (NEXT_CHAINS_ORDER.length > NEXT_CHAINS_MAX) {
                            delete NEXT_CHAINS[NEXT_CHAINS_ORDER.shift()];
                        }
                    }
                    NEXT_CHAINS[chainKey] = chain;
                    if (target >= chain.length) return { comics: [], maxPage: null };
                    r = await src.search.loadNext.call(src, args[0], opts, chain[target]);
                }
                // 源可能直接返回数组，也可能返回 {comics, maxPage} / {data:[...]}
                var list = Array.isArray(r) ? r : ((r && (r.comics || r.data)) || []);
                return { comics: serializeComics(list), maxPage: (r && r.maxPage) || null };
            }

            // ===== 分类浏览（v1.7.0.0）=====
            // Venera 源声明的 `category = { parts: [{name, categories, categoryParams,
            // itemType, type}] }`，以前只被静态解析成「分类名 → 按关键词搜索」，
            // 而搜索只匹配标题，于是漫画标签点了大概率查不到东西。
            // 这里改成真正评估源代码里的分类表（dynamic part 调 loader），
            // 再配合下面的 `categoryComics` 走源自己的分类页。
            case 'categories':
                return await collectCategoryParts(src);

            case 'categoryComics': {
                var loader = src.categoryComics && src.categoryComics.load;
                if (typeof loader !== 'function') {
                    throw new Error('该源没有实现分类加载（categoryComics.load）');
                }
                var ccOptions = Array.isArray(args[2]) ? args[2] : [];
                var ccPage = args[3] || 1;
                var res = await loader.call(src, args[0], args[1], ccOptions, ccPage);
                return {
                    comics: serializeComics((res && res.comics) || []),
                    maxPage: (res && res.maxPage) || null,
                };
            }

            // ===== 发现页（explore，v1.7.8.0）=====
            // 少数源**没有 `category`，只有 `explore` 数组**（如 カドコミ / Lanraragi / Komga）：
            // 每项 `{ title, type, load }`，type 决定 `load` 的返回形态：
            //   - multiPartPage / singlePageWithMultiPart：多组内容
            //   - multiPageComicList / mixed：一组 + 可翻页
            // 以前这类源在 App 里只能搜索、浏览页整页空白（连分类项都没有），
            // 现在按 explore 的下标取内容，App 侧再用 `vexp::<下标>` 伪 URL 调进来。
            case 'explore': {
                var exList = Array.isArray(src.explore) ? src.explore : [];
                var exIndex = typeof args[0] === 'number' ? args[0] : parseInt(args[0], 10);
                var exPage = args[1] || 1;
                var exItem = (exIndex >= 0 && exIndex < exList.length) ? exList[exIndex] : null;
                if (!exItem || typeof exItem.load !== 'function') {
                    throw new Error('该源没有第 ' + exIndex + ' 个发现页（explore）');
                }
                var exRaw = await exItem.load.call(src, exPage);
                var exSections = [];
                var exMaxPage = null;
                if (Array.isArray(exRaw)) {
                    // multiPartPage：[{title, comics}]
                    for (var ei = 0; ei < exRaw.length; ei++) {
                        var g = exRaw[ei];
                        if (!g || !Array.isArray(g.comics)) continue;
                        exSections.push({ title: String(g.title || ''), comics: serializeComics(g.comics) });
                    }
                } else if (exRaw && typeof exRaw === 'object') {
                    if (Array.isArray(exRaw.comics)) {
                        // multiPageComicList / mixed：{comics, maxPage}
                        exSections.push({ title: String(exItem.title || ''), comics: serializeComics(exRaw.comics) });
                        exMaxPage = exRaw.maxPage || null;
                    } else {
                        // singlePageWithMultiPart：{ 分组名: Comic[] }
                        var exKeys = Object.keys(exRaw);
                        for (var ek = 0; ek < exKeys.length; ek++) {
                            var ev = exRaw[exKeys[ek]];
                            if (!Array.isArray(ev)) continue;
                            exSections.push({ title: exKeys[ek], comics: serializeComics(ev) });
                        }
                    }
                }
                return {
                    title: String(exItem.title || ''),
                    type: String(exItem.type || ''),
                    sections: exSections,
                    maxPage: exMaxPage,
                };
            }

            case 'info': {
                var d = await loadInfoShared(src, key, args[0]);
                return {
                    title: d && d.title || '',
                    subTitle: (d && (d.subtitle || d.subTitle)) || '',
                    cover: (d && d.cover) || '',
                    description: (d && d.description) || '',
                    tags: flattenTags(d && d.tags),
                    updateTime: (d && d.updateTime) || '',
                    chapters: chaptersOf(d).map(function (p) {
                        return [String(p[0]), chapterTitle(p[1])];
                    }),
                };
            }

            case 'chapters': {
                var cached = await loadInfoShared(src, key, args[0]);
                return {
                    chapters: chaptersOf(cached).map(function (p) {
                        return [String(p[0]), chapterTitle(p[1])];
                    }),
                };
            }

            case 'content': {
                var loadEp = (src.comic && src.comic.loadEp) || (src.comic && src.comic.loadChapter);
                if (typeof loadEp !== 'function') throw new Error('该源没有实现章节图片加载（comic.loadEp）');
                // 单话作品（见 chaptersOf 的兜底）用的是空 epId，源那边按「没有章节」处理，
                // 统一传 null —— 不少源写的是 `if (epId) …`，空串在部分源里会被当成真值。
                var epArg = (args[1] === '' || args[1] === undefined) ? null : args[1];
                var res = await loadEp.call(src, args[0], epArg);
                var imgs = res && (res.images || res);
                // v1.7.1.0：把 loadEp 的顶层 headers 一并下传（见 serializeImages 的说明）
                var topHeaders = res && res.headers && typeof res.headers === 'object'
                    ? res.headers : null;
                return await serializeImages(imgs, src, args[0], epArg, topHeaders);
            }

            // ===== 账号（v1.3.0：源管理页登录按钮）=====
            // Venera 约定：源可声明 account = { login(account, pwd), logout(), checkLogin?() }
            case 'accountInfo': {
                var acc = src.account;
                if (!acc) return { supported: false };
                var loggedIn = false;
                if (typeof acc.checkLogin === 'function') {
                    // 源自带登录态检测（如请求个人中心接口判断）
                    loggedIn = !!await acc.checkLogin.call(src);
                }
                return {
                    supported: typeof acc.login === 'function',
                    loggedIn: loggedIn,
                };
            }

            case 'login': {
                if (!src.account || typeof src.account.login !== 'function') {
                    throw new Error('该源不支持账号登录');
                }
                await src.account.login.call(src, String(args[0] || ''), String(args[1] || ''));
                return { ok: true };
            }

            case 'logout': {
                if (src.account && typeof src.account.logout === 'function') {
                    await src.account.logout.call(src);
                }
                return { ok: true };
            }

            default:
                throw new Error('未知操作：' + op);
        }
    }

    /**
     * 结果串的字符上限（v1.7.6.0）。
     *
     * `postResult` 的字符串要**穿过 JNI 边界**一次性转成 Java String —— 如果源造出
     * 几百 MB 的结果（例如按 `num_pages` 循环生成图片地址、或某个列表异常膨胀），
     * 这个转换本身就会 `OutOfMemoryError` 并**直接崩进程**：它跑在 WebView 的
     * JavaBridge 线程上，Kotlin 侧的 try/catch 与 `MAX_RESULT_CHARS` 检查都发生在
     * 转换**之后**，根本来不及拦（真机崩溃日志：单次申请 668351688 字节）。
     *
     * 因此必须在 JS 侧先按字符数卡一道，超限就回一个明确的错误，而不是把巨型串
     * 丢给 JNI。8M 字符 ≈ 16MB 的 UTF-16，远低于常见堆预算。
     */
    var MAX_PAYLOAD_CHARS = 8 * 1024 * 1024;

    /**
     * 正在跑的长操作（v1.8.9.5 看门狗）。
     *
     * 排查「点进去一直转圈」时最难区分的是三种情况：① 调用**根本没到** JS（卡在
     * Kotlin 侧的并发闸门 / 主线程派发）；② 到了 JS 但卡在 `loadInfo` 的网络 await；
     * ③ 到了 JS 但卡在等 init。Kotlin 侧只能看到「30 秒超时」，三种情况一模一样。
     *
     * 这里给交互操作挂一个看门狗：调用开始就记一条，超过 [WATCHDOG_REPORT_MS]
     * 还没结束就每 2 秒报一次当前阶段 —— 有「开始」日志说明到了 JS，
     * 有「卡住」日志能直接看出卡在哪一段，什么都没有就说明根本没到 JS。
     * 只盯 info / chapters / content（召回那种成百上千次调用不跟）。
     */
    var LIVE_CALLS = Object.create(null);
    var WATCHDOG_TICK_MS = 2000;
    var WATCHDOG_REPORT_MS = 6000;
    var WATCHDOG_MAX_REPORTS = 5;

    /**
     * 被放弃、但源函数**还在跑**的调用（v1.8.9.5）。
     *
     * JS 无法中断一个正在执行的源函数。某些源（逐页取图那类）的 `loadEp` 是
     * 「一页一次请求」的循环：Kotlin 侧 30 秒超时放弃之后，循环仍在继续 ——
     * 实测退出阅读器后网络请求还在一路发（10 秒 35 次），把共享 JS 线程和 HTTP
     * 闸门长期占住，于是**别的什么都加载不出来**。这里给放弃的调用留标记，
     * 它下一次发起网络请求时直接抛错，把循环打断。
     */
    var ABORTED_CALLS = Object.create(null);
    var ABORTED_ORDER = [];
    var ABORTED_MAX = 64;

    /**
     * 单次调用的硬上界：超过就自己放弃并打断。
     *
     * Kotlin 侧的上界更短（它会先发取消通知），这里只是兜底 —— 万一通知没到
     * （WebView 卡住等），源函数也不许在 JS 里无限跑。
     * `content` 放宽一档：逐页取图的源本来就是「一页一次请求」，一章几十页
     * 需要几十次往返，30 秒根本不够（那正是「章节加载不出内容」的来源）。
     */
    var CALL_HARD_CAP_MS = 45000;
    var CALL_HARD_CAP_CONTENT_MS = 90000;

    function callHardCap(op) {
        return op === 'content' ? CALL_HARD_CAP_CONTENT_MS : CALL_HARD_CAP_MS;
    }

    function watchOp(op) {
        return op === 'info' || op === 'chapters' || op === 'content';
    }

    function liveStart(callId, key, op) {
        LIVE_CALLS[String(callId)] = {
            op: op,
            key: String(key),
            t0: Date.now(),
            stage: '开始',
            stageAt: Date.now(),
            net0: netCalls,
            reported: 0,
        };
        if (watchOp(op)) log('debug', 'PROBE', op + ' 开始 key=' + String(key));
    }

    function liveStage(callId, stage) {
        var c = LIVE_CALLS[String(callId)];
        if (!c) return;
        c.stage = stage;
        c.stageAt = Date.now();
    }

    function liveEnd(callId) {
        delete LIVE_CALLS[String(callId)];
    }

    /** 标记某次调用已被放弃（上层不再等它的结果） */
    function liveAbort(callId) {
        var id = String(callId);
        delete LIVE_CALLS[id];
        if (!ABORTED_CALLS[id]) {
            ABORTED_CALLS[id] = true;
            ABORTED_ORDER.push(id);
            while (ABORTED_ORDER.length > ABORTED_MAX) delete ABORTED_CALLS[ABORTED_ORDER.shift()];
        }
    }

    /**
     * 当前这次网络调用是否属于「已经被放弃的孤儿循环」。
     *
     * 判据：一个还在等结果的调用都没有，但有被放弃的调用。此时唯一还会发请求的
     * 就是那些孤儿循环 —— 让它们抛错即可打断。（只要有任何一个正常调用在跑，
     * 就放行：没法区分请求是谁发的，宁可让孤儿多跑一会儿也不能误伤正常调用。）
     */
    function orphanedNetwork() {
        if (!LIVE_CALLS || !ABORTED_CALLS) return false;
        for (var k in LIVE_CALLS) return false;
        for (var a in ABORTED_CALLS) return true;
        return false;
    }

    setInterval(function () {
        var now = Date.now();
        Object.keys(LIVE_CALLS).forEach(function (id) {
            var c = LIVE_CALLS[id];
            var elapsed = now - c.t0;
            // 硬上界兜底：Kotlin 侧的取消通知万一没到（WebView 卡住等），
            // 这里自己收尾，避免源函数在 JS 里无限跑下去。
            var cap = callHardCap(c.op);
            if (elapsed > cap) {
                liveAbort(id);
                try {
                    B.postError(id, 'Venera 源执行超过 ' + (cap / 1000) + 's，已放弃');
                } catch (e) { /* ignore */ }
                return;
            }
            if (!watchOp(c.op)) return;
            if (c.reported >= WATCHDOG_MAX_REPORTS) return;
            if (elapsed < WATCHDOG_REPORT_MS) return;
            c.reported += 1;
            var st = INIT_STATE[c.key];
            // net / pending 是关键判据：net 一直是 0 说明源根本没发请求（卡在源自己的
            // 逻辑里）；pending > 0 说明在等我们自己的异步网络桥回话。
            log('debug', 'PROBE', '卡住 ' + c.op + ' ' + elapsed + 'ms' +
                ' 阶段=' + c.stage + '(' + (now - c.stageAt) + 'ms)' +
                ' init=' + ((st && st.state) || 'none') +
                ' net=' + (netCalls - c.net0) +
                ' pending=' + Object.keys(httpPending).length +
                ' inflightInfo=' + Object.keys(INFO_INFLIGHT).length);
        });
    }, WATCHDOG_TICK_MS);

    /** Kotlin 侧放弃某次调用时调用（见 VeneraEngine.abortJsCall） */
    window.__veneraAbort = function (callId) {
        liveAbort(callId);
    };

    async function runCall(callId, key, op, argsJson) {
        var args;
        try {
            args = argsJson ? JSON.parse(argsJson) : [];
        } catch (e) {
            B.postError(String(callId), '参数解析失败：' + msgOf(e));
            return;
        }
        liveStart(callId, key, op);
        try {
            await runCallCore(callId, key, op, args);
        } catch (e) {
            // 兜底：这里抛出去就是「Kotlin 侧一直等到 30 秒超时」，用户只看到转圈。
            // 任何意外（结果序列化失败、桥调用异常）都必须变成一个明确的错误回包。
            try { B.postError(String(callId), describeWithHint(key, e)); } catch (err) { /* ignore */ }
        } finally {
            liveEnd(callId);
        }
    }

    async function runCallCore(callId, key, op, args) {
        var startedAt = Date.now();

        // 已知只能同步取值的源：本次直接上同步桥，别再白试一遍异步
        var pinned = !!syncOnly[String(key)];
        var before = asyncHits;
        liveStage(callId, '源加载中');
        var first = await attempt(key, op, args, pinned);

        // init 还没完成就开跑 → 补等一次再重试（见 [initRetryWaitForOp]）。
        // 半初始化的源会在 `this.apiDomains[0]` 这类下标访问上毫秒级抛错，
        // 等 init 落地后重试一次基本都能成。
        if (!first.ok && !pinned) {
            liveStage(callId, '等初始化');
            var afterInit = await retryAfterInit(key, op, args);
            if (afterInit) first = afterInit;
        }

        if (first.ok) {
            // 异步桥跑通了 → 之前攒的「疑似只能用同步」作废
            if (!pinned) delete syncSuspect[String(key)];
            postOk(callId, first.value);
            return;
        }

        // 自愈：异步桥让「同步取值」的旧写法拿到 Promise（`JSON.parse(Network.get(u).body)`
        // 拿到 undefined、`res.body.xxx` 拿到 undefined）。这类源极少（实测 33 个源
        // 279 处 Network 调用里 272 处都是 await），但不能让它们直接不可用。
        //
        // **两级判据**（v1.8.9.5）：
        // - 要不要「这次用同步桥再试一遍」→ 宽判据（[mayNeedSyncBridge]），
        //   代价只是一次重复调用，宁宽勿窄；
        // - 要不要「把这个源钉成只能同步」→ 只认**连续两次、且报错文本完全相同**
        //   的异步失败（[SYNC_SUSPECT_THRESHOLD]）。旧实现靠一条宽正则一次定生死，
        //   还把结论落盘，于是普通业务报错 / 偶发网络抖动都能把源永久钉死在
        //   阻塞式同步桥上 —— 同步桥会占满共享 JS 线程，用户侧就是「点进详情一直转圈」。
        //   另外钉住之后**一旦失败就解钉**，让下一次调用重新试异步。
        if (!pinned && asyncHits > before && mayNeedSyncBridge(first.error)) {
            log('warn', '源疑似不兼容异步网络，回落同步桥重试');
            liveStage(callId, '同步重试');
            var second = await attempt(key, op, args, true);
            if (second.ok) {
                var firstMsg = msgOf(first.error);
                var suspect = syncSuspect[String(key)];
                if (suspect && suspect.message === firstMsg) {
                    suspect.hits += 1;
                } else {
                    suspect = { hits: 1, message: firstMsg };
                }
                syncSuspect[String(key)] = suspect;
                if (suspect.hits >= SYNC_SUSPECT_THRESHOLD) {
                    syncOnly[String(key)] = true;
                    log('warn', '源连续 ' + suspect.hits + ' 次同样报错，标记为仅同步桥');
                }
                postOk(callId, second.value);
                return;
            }
            diagFail(key, op, startedAt, '同步重试后仍失败', second.error);
            B.postError(String(callId), describeWithHint(key, second.error));
            return;
        }

        // 钉住之后又失败了 → 解钉，下次重新试异步桥。
        // 不加这条，「误钉一次」就是永久性的（这正是旧实现最难自查的地方）。
        if (pinned) {
            delete syncOnly[String(key)];
            delete syncSuspect[String(key)];
            log('warn', '同步桥也失败，解除仅同步桥标记');
        }
        diagFail(key, op, startedAt, pinned ? '同步桥' : '异步桥', first.error);
        B.postError(String(callId), describeWithHint(key, first.error));
    }

    /**
     * 失败后如果 init 还没完成，补等一会儿再重试一次。
     *
     * @returns 重试结果（`{ok, value|error}`），没补等 / 补等后 init 仍未完成则返回 null
     */
    async function retryAfterInit(key, op, args) {
        var budget = initRetryWaitForOp(op);
        if (budget <= 0) return null;
        var st = INIT_STATE[String(key)];
        if (!st || st.state !== 'pending') return null;
        await Promise.race([
            st.promise,
            new Promise(function (resolve) { setTimeout(resolve, budget); }),
        ]);
        // 只等来超时、或者 init 直接失败 → 不再重试（重试也是同一个错）
        if (st.state !== 'done') return null;
        // 上一次失败留下的负缓存会直接把重试打回同一个错，等于没重试（见 [attempt]）
        clearInfoFail(String(key));
        return await attempt(key, op, args, false);
    }

    /**
     * 交互操作失败时打一条**决定性**诊断（v1.8.9.5）。
     *
     * 「点进源里一直转圈 / 报个看不懂的错」排查起来最费时间的就是分不清卡在哪：
     * 等 init、发请求、还是源自己的代码抛了。这条日志把三件事一次说清 ——
     * 耗时、init 状态、异常首帧。只在 info / chapters / content 失败时打，
     * 召回那种成百上千次失败不会刷屏。
     *
     * 走 `PROBE` 前缀是为了让 Kotlin 侧（[VeneraEngine.Bridge.log]）原样输出：
     * 其它日志为了不泄露源与站点信息只打长度，而这条只含阶段名、耗时和栈帧。
     */
    function diagFail(key, op, startedAt, stage, e) {
        if (op !== 'info' && op !== 'chapters' && op !== 'content') return;
        try {
            var st = INIT_STATE[String(key)];
            var stack = (e && e.stack) ? String(e.stack).split('\n').slice(0, 3).join(' | ') : '';
            // 栈里可能带出源的内部地址 / 站点名：日志要能贴出来，先把 URL 抹掉
            stack = stack.replace(/https?:\/\/\S+/gi, '<url>').replace(/www\.\S+/gi, '<host>');
            log('debug', 'PROBE', op + ' 失败 ' + (Date.now() - startedAt) + 'ms ' +
                'init=' + ((st && st.state) || 'none') + ' 通道=' + stage +
                (stack ? ' ' + stack : ''));
        } catch (err) { /* ignore */ }
    }

    /**
     * 跑一次 doCall，把结果包成 {ok, value|error}。
     *
     * [forceSync] 期间置高 syncDepth，让本次调用内所有 Network.* 走同步桥
     * （同步桥是改动前的行为，慢但对任何写法都正确）。
     */
    async function attempt(key, op, args, forceSync) {
        if (forceSync) {
            syncDepth++;
            // 同步重试前必须清掉上一次（异步）失败留下的负缓存，否则
            // loadInfoShared 会直接抛回同一个错误，重试等于没试 ——
            // 这会连带把「这个源到底能不能用同步桥救回来」的判断全做错。
            clearInfoFail(String(key));
        }
        try {
            var out = await doCall(key, op, args || []);
            return { ok: true, value: out };
        } catch (e) {
            return { ok: false, error: e };
        } finally {
            if (forceSync) syncDepth--;
        }
    }

    /** 结果串落地：带体积上限，超限回明确错误而不是把巨型串丢过 JNI */
    function postOk(callId, out) {
        var payload = JSON.stringify(out === undefined ? null : out);
        if (typeof payload === 'string' && payload.length > MAX_PAYLOAD_CHARS) {
            B.postError(
                String(callId),
                '源返回的数据过大已丢弃（' + payload.length + ' 字符 > ' + MAX_PAYLOAD_CHARS + '）',
            );
            return;
        }
        B.postResult(String(callId), payload);
    }

    function msgOf(e) {
        return (e && (e.message || e.toString())) || String(e);
    }

    function describe(e) {
        var msg = msgOf(e);
        // window.__veneraDebug = true 时附带堆栈（离线验证台用），应用内保持简洁
        if (window.__veneraDebug && e && e.stack) msg += '\n' + e.stack;
        return msg;
    }

    /**
     * **宽判据**：这次失败要不要「用同步桥再试一遍」。
     *
     * 代价只有一次重复调用，所以宁宽勿窄 —— 漏判会让一个本来能救回来的源直接不可用。
     * 网络层错误（超时 / DNS 失败 / 连接重置）不含这些词，不会白重试。
     */
    function mayNeedSyncBridge(e) {
        var msg = msgOf(e);
        return /cannot read propert|of undefined|of null|is not a function|is not an object|is not valid json|unexpected token/i
            .test(msg);
    }

    /**
     * 失败消息 + 现场提示。
     *
     * `init()` 卡在网络上时源会停在半初始化状态，`loadInfo` 里
     * `this.someList[0]` 之类立刻抛 `Cannot read properties of undefined` ——
     * 那条消息对用户毫无信息量，看起来像「源坏了」。这里补一句真正的原因。
     */
    function describeWithHint(key, e) {
        var msg = describe(e);
        var st = INIT_STATE[String(key)];
        if (st && st.state === 'pending') {
            msg += '（源初始化尚未完成，可能是网络不通，稍后重试）';
        } else if (st && st.state === 'failed') {
            msg += '（源初始化失败，通常是源站当前不可达）';
        }
        return msg;
    }

    // 暴露给 Kotlin
    window.__veneraRegister = function (key) {
        try {
            // v1.8.9.5：旧版本把「只能用同步桥」的结论落盘过（`venera:net_sync:<key>`），
            // 而判定条件宽到普通业务报错也会命中 —— 那些机器上的源至今仍被钉死在
            // 阻塞式同步桥上。这里顺手删掉，且**不再回灌**（同步桥结论只保留在内存）。
            try { B.storageDelete(LEGACY_SYNC_FLAG_PREFIX + String(key)); } catch (e) { /* ignore */ }
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
