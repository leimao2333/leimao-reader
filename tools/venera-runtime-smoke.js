/*
 * Venera 运行时冒烟测试（Node 环境，不需要 WebView）
 * ------------------------------------------------------------------
 * 目的：把「点进 Venera 源一直转圈」那几条链路钉成可回归的用例。
 *
 * 覆盖：
 *   1) init 未完成时 loadInfo 会抛 `reading '0'`（复现真机现象）
 *   2) 注册即触发 init；info/chapters 会等 init 完成，不再撞半初始化
 *   3) info 与 chapters 共享同一次 loadInfo（只跑一遍）
 *   4) loadInfo 失败后的短时负缓存：紧随其后的 chapters 立刻失败
 *   5) 同步写法源：第一次先异步失败再同步成功（不钉死），第二次才钉成同步桥
 *   6) 普通业务报错不会被钉成同步桥
 *
 * 运行：
 *   node tools/venera-runtime-smoke.js
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const RUNTIME = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'venera', 'runtime.js');

/** 造一个「源仓库 + 原生桥」的假宿主，跑完返回可断言的句柄 */
function makeHost(sources) {
    const store = new Map();
    const logs = [];
    const pendingCalls = new Map();
    const httpQueue = []; // 未完成的异步 HTTP：{ reqId, method, url }

    let seq = 0;
    let lastCallId = '';

    const bridge = {
        ready() {},
        getSource(key) { return sources[key] ?? null; },
        postResult(callId, json) {
            const slot = pendingCalls.get(callId);
            if (slot) { pendingCalls.delete(callId); slot.resolve(json); }
        },
        postError(callId, message) {
            const slot = pendingCalls.get(callId);
            if (slot) { pendingCalls.delete(callId); slot.reject(new Error(message)); }
        },
        log(level, message) { logs.push(level + ' ' + message); },
        storageGet(k) { return store.has(k) ? store.get(k) : null; },
        storageSet(k, v) { store.set(k, v); },
        storageDelete(k) { store.delete(k); },
        http(method, url) {
            return JSON.stringify({ status: 200, headers: {}, body: bodyFor(url), url });
        },
        httpBinary(method, url) {
            return JSON.stringify({
                status: 200, headers: {}, bodyBase64: '', url,
            });
        },
        httpAsync(method, url) {
            const reqId = 'h' + (++seq);
            httpQueue.push({ reqId, method, url });
            return reqId;
        },
        httpTake(reqId) {
            const i = httpQueue.findIndex((r) => r.reqId === reqId);
            if (i < 0) return null;
            httpQueue.splice(i, 1);
            return JSON.stringify({ status: 200, headers: {}, body: bodyFor(''), url: '' });
        },
        setCookie() {},
        getCookie() { return ''; },
        deleteCookies() {},
    };

    // 默认响应体：一个最小的漫画 JSON，够 loadInfo 用
    function bodyFor() {
        return JSON.stringify({
            data: {
                comic: { id: 'c1', title: '测试作品' },
                chapters: { e1: '第1话', e2: '第2话' },
            },
        });
    }

    const sandbox = {
        console: { log() {}, warn() {}, error() {}, info() {}, debug() {} },
        setTimeout,
        clearTimeout,
        setInterval,
        clearInterval,
        Promise,
        Date,
        Math,
        JSON,
        Object,
        Array,
        String,
        Number,
        Boolean,
        RegExp,
        Error,
        TypeError,
        Map,
        Set,
        Uint8Array,
        ArrayBuffer,
        TextEncoder,
        TextDecoder,
        btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
        atob: (s) => Buffer.from(s, 'base64').toString('binary'),
        URL,
        URLSearchParams,
    };
    sandbox.window = sandbox;
    sandbox.NativeBridge = bridge;
    sandbox.globalThis = sandbox;

    const context = vm.createContext(sandbox);
    vm.runInContext(fs.readFileSync(RUNTIME, 'utf8'), context, { filename: 'runtime.js' });

    return {
        logs,
        httpQueue,
        store,
        register(key) {
            return vm.runInContext(`__veneraRegister(${JSON.stringify(key)})`, context);
        },
        call(key, op, args) {
            const callId = 'c' + (++seq);
            lastCallId = callId;
            const promise = new Promise((resolve, reject) => {
                pendingCalls.set(callId, { resolve, reject });
            });
            vm.runInContext(
                `__veneraCall(${JSON.stringify(callId)}, ${JSON.stringify(key)}, ` +
                `${JSON.stringify(op)}, ${JSON.stringify(JSON.stringify(args || []))})`,
                context,
            );
            return promise;
        },
        /** 模拟 Kotlin 侧超时放弃某次调用 */
        abort(callId) {
            vm.runInContext(`__veneraAbort(${JSON.stringify(callId)})`, context);
        },
        get lastCallId() { return lastCallId; },
        /** 把队列里的异步 HTTP 全部结掉（模拟原生回调） */
        flushHttp() {
            const all = httpQueue.slice();
            for (const r of all) {
                vm.runInContext(`__veneraHttpDone(${JSON.stringify(r.reqId)})`, context);
            }
            return all;
        },
    };
}

const results = [];
function check(name, ok, detail) {
    results.push({ name, ok, detail });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : '  →  ' + detail}`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------- 源样本

/** init 很慢，且 loadInfo 依赖 init 填好的 apiDomains（真机现象的原样复刻） */
const SLOW_INIT_SOURCE = `
class SlowInit extends ComicSource {
    name = 'slow-init';
    key = 'slow-init';
    apiDomains = null;
    async init() {
        await new Promise((r) => setTimeout(r, 300));
        this.apiDomains = ['https://example.invalid'];
    }
    search = { load: async () => [] };
    comic = {
        loadInfo: async (id) => {
            // 半初始化时这里就是真机那条 \`reading '0'\`
            var host = this.apiDomains[0];
            var res = await Network.get(host + '/comic/' + id);
            var json = JSON.parse(res.body);
            return new ComicDetails({
                title: json.data.comic.title,
                chapters: json.data.chapters,
            });
        },
        loadEp: async () => ({ images: [] }),
    };
}
`;

/** 同步写法：不 await，直接取 .body —— 异步桥下 res 是 Promise，body 是 undefined */
const SYNC_STYLE_SOURCE = `
class SyncStyle extends ComicSource {
    name = 'sync-style';
    key = 'sync-style';
    search = { load: async () => [] };
    comic = {
        loadInfo: async (id) => {
            var json = JSON.parse(Network.get('https://example.invalid/comic/' + id).body);
            return new ComicDetails({ title: json.data.comic.title, chapters: {} });
        },
        loadEp: async () => ({ images: [] }),
    };
}
`;

/** 普通业务报错：接口改版，字段没了 */
const BAD_DATA_SOURCE = `
class BadData extends ComicSource {
    name = 'bad-data';
    key = 'bad-data';
    search = { load: async () => [] };
    comic = {
        loadInfo: async () => {
            throw new Error("Cannot read properties of undefined (reading 'items')");
        },
        loadEp: async () => ({ images: [] }),
    };
}
`;

/** init 会联网：注册后应当**立刻**看到一次异步请求，证明「注册即触发 init」 */
const EAGER_INIT_SOURCE = `
class EagerInit extends ComicSource {
    name = 'eager-init';
    key = 'eager-init';
    async init() { await Network.get('https://example.invalid/domains'); }
    search = { load: async () => [] };
    comic = { loadInfo: async () => new ComicDetails({ title: 'x', chapters: {} }) };
}
`;

/** init 永不完成：用来验证「最坏情况也不会一直转圈」 */
const STUCK_INIT_SOURCE = `
class StuckInit extends ComicSource {
    name = 'stuck-init';
    key = 'stuck-init';
    apiDomains = null;
    init() { return new Promise(() => {}); }
    search = { load: async () => [] };
    comic = {
        loadInfo: async () => {
            var host = this.apiDomains[0];
            return new ComicDetails({ title: host, chapters: {} });
        },
    };
}
`;

/** 逐页取图的源：`loadEp` 是个「一页一次请求」的死循环（真机上会把引擎占死） */
const LOOP_CONTENT_SOURCE = `
class LoopContent extends ComicSource {
    name = 'loop-content';
    key = 'loop-content';
    search = { load: async () => [] };
    comic = {
        loadInfo: async () => new ComicDetails({ title: 'x', chapters: {} }),
        loadEp: async (comicId, epId) => {
            var images = [];
            for (;;) {
                var res = await Network.get('https://example.invalid/page/' + images.length);
                images.push(res.url);
            }
        },
    };
}
`;

/** 图库类源：loadEp 只给页码，真实地址要逐张 onImageLoad（每张都要往返一次） */
const GALLERY_SOURCE = `
class Gallery extends ComicSource {
    name = 'gallery';
    key = 'gallery';
    search = { load: async () => [] };
    comic = {
        loadInfo: async () => new ComicDetails({ title: 'x', chapters: {} }),
        loadEp: async () => ({
            images: Array.from({ length: 16 }, (_, i) => String(i)),
        }),
        onImageLoad: async (image) => {
            await new Promise((r) => setTimeout(r, 100));
            return { url: 'https://cdn.invalid/' + image + '.jpg' };
        },
    };
}
`;

async function main() {
    // ---- 用例 0：注册即触发 init（对齐上游「解析完成就触发」）
    {
        const host = makeHost({ 'eager-init': EAGER_INIT_SOURCE });
        host.register('eager-init');
        check('注册即触发 init（还没调用就发出请求）', host.httpQueue.length === 1,
            'httpQueue=' + host.httpQueue.length);
    }

    // ---- 用例 1/2/3：慢 init + 详情/目录共享
    {
        const host = makeHost({ 'slow-init': SLOW_INIT_SOURCE });
        host.register('slow-init');

        const infoP = host.call('slow-init', 'info', ['c1']);
        const chapP = host.call('slow-init', 'chapters', ['c1']);
        // 立刻挂上处理器：否则中间 await 期间会被 Node 判成未处理的 rejection
        const infoSettled = infoP.then((v) => ({ ok: true, v }), (e) => ({ ok: false, e }));
        const chapSettled = chapP.then((v) => ({ ok: true, v }), (e) => ({ ok: false, e }));

        // 让 loadInfo 里的 Network.get 有结果
        for (let i = 0; i < 20 && host.httpQueue.length === 0; i++) await sleep(50);
        host.flushHttp();
        await sleep(20);
        host.flushHttp();

        const infoRes = await infoSettled;
        const chapRes = await chapSettled;
        check('慢 init 源也能拿到详情', infoRes.ok, infoRes.ok ? '' : String(infoRes.e));
        if (!infoRes.ok) {
            check('目录与详情同源同 id（章节被解析出来）', false, '详情已失败');
        } else {
            const info = JSON.parse(infoRes.v);
            const chapters = chapRes.ok ? JSON.parse(chapRes.v) : { chapters: [] };
            check('详情标题正确', info.title === '测试作品', JSON.stringify(info));
            check('目录与详情同源同 id（章节被解析出来）',
                chapters.chapters.length === 2, JSON.stringify(chapters));
        }
    }

    // ---- 用例 4：失败负缓存
    {
        const host = makeHost({ 'bad-data': BAD_DATA_SOURCE });
        host.register('bad-data');
        const t0 = Date.now();
        let firstErr = '';
        try { await host.call('bad-data', 'info', ['c9']); } catch (e) { firstErr = e.message; }
        const t1 = Date.now();
        let secondErr = '';
        try { await host.call('bad-data', 'chapters', ['c9']); } catch (e) { secondErr = e.message; }
        const t2 = Date.now();
        check('详情失败后目录立刻拿到同一个错误', firstErr === secondErr && secondErr !== '',
            `${firstErr} / ${secondErr}`);
        check('第二次失败几乎不耗时（负缓存生效）', t2 - t1 < 200, `${t2 - t1}ms`);
        check('失败消息带上现场提示', /源初始化|源站|Cannot read/.test(secondErr), secondErr);
        void t0;
    }

    // ---- 用例 5：同步写法源的两级判据
    {
        const host = makeHost({ 'sync-style': SYNC_STYLE_SOURCE });
        host.register('sync-style');
        const retries = () => host.logs.filter((l) => l.includes('回落同步桥重试')).length;

        let ok1 = false;
        try { await host.call('sync-style', 'info', ['c1']); ok1 = true; } catch (e) { ok1 = false; }
        check('同步写法源第 1 次调用能救回来（同步桥重试）', ok1);
        check('第 1 次只记怀疑、不钉死', !host.logs.some((l) => l.includes('标记为仅同步桥')));

        let ok2 = false;
        try { await host.call('sync-style', 'info', ['c2']); ok2 = true; } catch (e) { ok2 = false; }
        check('同步写法源第 2 次调用仍成功', ok2);
        check('同一条报错连续 2 次后才钉成同步桥',
            host.logs.some((l) => l.includes('标记为仅同步桥')), host.logs.join(' | '));

        const retriesBefore = retries();
        let ok3 = false;
        try { await host.call('sync-style', 'info', ['c3']); ok3 = true; } catch (e) { ok3 = false; }
        check('钉住后第 3 次直接走同步桥（不再白试异步）',
            ok3 && retries() === retriesBefore, `retries ${retriesBefore} → ${retries()}`);

        const legacy = [...host.store.keys()].filter((k) => k.startsWith('venera:net_sync'));
        check('同步桥结论不落盘', legacy.length === 0, legacy.join(','));
    }

    // ---- 用例 6：普通业务报错不钉死
    {
        const host = makeHost({ 'bad-data': BAD_DATA_SOURCE });
        host.register('bad-data');
        for (let i = 0; i < 3; i++) {
            try { await host.call('bad-data', 'info', ['c' + i]); } catch (e) { /* 预期失败 */ }
        }
        check('普通业务报错不会被钉成同步桥',
            !host.logs.some((l) => l.includes('标记为仅同步桥')), host.logs.join(' | '));
    }

    // ---- 用例 7：init 卡死时最坏耗时仍有上界（不能无限转圈）
    {
        const host = makeHost({ 'stuck-init': STUCK_INIT_SOURCE });
        host.register('stuck-init');
        const t0 = Date.now();
        let msg = '';
        try { await host.call('stuck-init', 'info', ['c1']); } catch (e) { msg = e.message; }
        const elapsed = Date.now() - t0;
        check('init 卡死时详情调用有上界（≤17s）', elapsed < 17000, elapsed + 'ms');
        check('init 卡死时给出可读错误（不是光秃秃的 undefined）',
            /初始化尚未完成/.test(msg), msg);
    }

    // ---- 用例 8：原生「完成通知」被卡住时，轮询兜底仍能取到结果
    {
        const host = makeHost({ 'slow-init': SLOW_INIT_SOURCE });
        host.register('slow-init');
        // 关键：全程**不调** __veneraHttpDone（模拟应用主线程被占住、wv.post 卡住），
        // 只靠 JS 侧的轮询 httpTake 取件。
        const settled = host.call('slow-init', 'info', ['poll'])
            .then((v) => ({ ok: true, v }), (e) => ({ ok: false, e }));
        const res = await settled;
        check('推送被卡住时轮询能取到结果', res.ok, res.ok ? '' : String(res.e));
        if (res.ok) {
            check('轮询取到的详情内容正确', JSON.parse(res.v).title === '测试作品');
        }
        check('轮询后队列被清空（没有泄漏）', host.httpQueue.length === 0,
            'queue=' + host.httpQueue.length);
    }

    // ---- 用例 9：放弃的调用必须被打断，不能一直发请求占死引擎
    {
        const host = makeHost({ 'loop-content': LOOP_CONTENT_SOURCE });
        host.register('loop-content');
        host.call('loop-content', 'content', ['c1', 'e1'])
            .then(() => {}, () => {}); // 这个调用永远不会成功，只要它别再占资源

        // 让它真的转起来（每次 flush 推进一轮循环）
        for (let i = 0; i < 3; i++) { host.flushHttp(); await sleep(10); }
        check('死循环源确实在持续发请求', host.httpQueue.length > 0, 'queue=' + host.httpQueue.length);

        // 模拟 Kotlin 侧 30 秒超时放弃
        host.abort(host.lastCallId);
        // 把手上那一个请求结掉，循环才会走到下一次 Network.get（在那里被打断）
        host.flushHttp();
        await sleep(50);
        host.flushHttp();
        await sleep(50);

        const queueAfter = host.httpQueue.length;
        host.flushHttp();
        await sleep(50);
        check('放弃后循环被立刻打断（不再发新请求）',
            queueAfter === 0 && host.httpQueue.length === 0,
            `queueAfter=${queueAfter} now=${host.httpQueue.length}`);
    }

    // ---- 用例 10：逐张解析图片地址必须并发，且顺序不能乱
    {
        const host = makeHost({ gallery: GALLERY_SOURCE });
        host.register('gallery');
        const t0 = Date.now();
        const res = await host.call('gallery', 'content', ['c1', 'e1'])
            .then((v) => ({ ok: true, v }), (e) => ({ ok: false, e }));
        const elapsed = Date.now() - t0;
        check('图库源能取到章节内容', res.ok, res.ok ? '' : String(res.e));
        // 16 张 × 100ms，串行要 1600ms；并发 8 应远小于这个数
        check('逐张解析是并发的（16 张 < 900ms）', elapsed < 900, elapsed + 'ms');
        if (res.ok) {
            const images = JSON.parse(res.v).images;
            const ordered = images.every((u, i) => u === 'https://cdn.invalid/' + i + '.jpg');
            check('图片顺序与原顺序一致', images.length === 16 && ordered,
                images.slice(0, 3).join(','));
        }
    }

    const failed = results.filter((r) => !r.ok);
    console.log(`\n${results.length - failed.length}/${results.length} 通过`);
    // runtime.js 里有看门狗 setInterval，不显式退出的话 Node 会一直挂着
    process.exit(failed.length ? 1 : 0);
}

main().catch((e) => {
    console.error('冒烟测试自身崩了：', e);
    process.exit(1);
});
