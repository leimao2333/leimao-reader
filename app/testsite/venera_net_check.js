// Venera 网络层离线校验（开发用，Node 运行，不需要模拟器也不需要真站点）
// ------------------------------------------------------------------
// `runtime.js` 的网络桥在 v1.8.8.8 从「同步阻塞」改成了「异步回调」：
//   同步：B.http() 直接返回响应 JSON  → 一次请求把 JS 线程占满整个网络往返
//   异步：B.httpAsync() 立刻返回 reqId → 原生在 IO 线程跑，完成时回调
//         window.__veneraHttpDone(reqId)，JS 再同步 B.httpTake() 取结果
//
// 这套改动的两个关键性质光看代码看不出来，而这个脚本专门盯它们：
//   1. **真的并行** —— N 个请求必须在同一轮里全部发出，总耗时约等于一次往返；
//      如果哪一行退化成同步调用，总耗时会变成 N 倍，用例立刻失败。
//   2. **老写法不会废** —— 不走 await、直接同步取 `Network.get().body` 的源，
//      在异步桥下会失败；此时必须自动回落同步桥重试，并把该源标记为 sync-only。
//
// 用法（在仓库根目录）：
//   node app/testsite/venera_net_check.js app/src/main/assets/venera/runtime.js
// 退出码非 0 表示有用例失败。
const fs = require('fs');
const vm = require('vm');

const runtimePath = process.argv[2] || 'app/src/main/assets/venera/runtime.js';
const runtime = fs.readFileSync(runtimePath, 'utf8');

/** 源：故意不 await，直接同步取值（老写法，用来验证自愈回落） */
const LEGACY_SOURCE = `
class Legacy extends ComicSource {
  name = "legacy"; key = "legacy"; version = "1.0";
  search = {
    load: async function (keyword, options, page) {
      const res = Network.get('https://a.test/search');   // 故意不 await
      const data = JSON.parse(res.body);
      return data;
    }
  };
}
`;

const LEGACY_BODY = JSON.stringify({ comics: [{ id: 'c1', title: '来自同步桥' }] });

/**
 * 造一个假 WebView 环境。
 *
 * @param {object} opts
 *   - source:   getSource 返回的源码（null = 不用源，直接调 Network）
 *   - body:     响应体字符串
 *   - latency:  模拟网络往返（毫秒）
 */
function makeHost(opts) {
    const host = {
        asyncCalls: 0,
        syncCalls: 0,
        issued: [],
        results: new Map(),
        pending: new Map(),
        storage: {},
        callSeq: 0,
        httpSeq: 0,
    };

    const sandbox = {
        console: { log: () => {}, warn: () => {}, error: () => {} },
        setTimeout, clearTimeout, setInterval, clearInterval,
        Promise, Map, Set, JSON, Math, Date, RegExp, String, Number, Array, Object,
        ArrayBuffer, Uint8Array, TextDecoder, TextEncoder, btoa, atob,
        __veneraDebug: true,
    };
    sandbox.window = sandbox;
    sandbox.globalThis = sandbox;

    const okBody = JSON.stringify({ status: 200, headers: {}, body: opts.body, url: '' });

    sandbox.NativeBridge = {
        ready() {},
        getSource() { return opts.source || null; },
        postResult(callId, payload) {
            const p = host.pending.get(String(callId));
            if (p) { host.pending.delete(String(callId)); p.resolve(payload); }
        },
        postError(callId, msg) {
            const p = host.pending.get(String(callId));
            if (p) { host.pending.delete(String(callId)); p.reject(new Error(msg)); }
        },
        storageGet(k) { return Object.prototype.hasOwnProperty.call(host.storage, k) ? host.storage[k] : null; },
        storageSet(k, v) { host.storage[k] = String(v); },
        storageDelete(k) { delete host.storage[k]; },
        setCookie() {}, getCookie() { return ''; }, deleteCookies() {},
        log() {},

        // 同步桥：被调用说明走的是老路径
        http() { host.syncCalls++; return okBody; },
        httpBinary() { host.syncCalls++; return JSON.stringify({ status: 200, headers: {}, bodyBase64: '', url: '' }); },

        // 异步桥：立刻返回 reqId，延后回调（与原生 OkHttp 在 IO 线程完成一致）
        httpAsync() {
            host.asyncCalls++;
            const reqId = 'h' + (++host.httpSeq);
            host.issued.push(Date.now());
            setTimeout(() => {
                host.results.set(reqId, okBody);
                if (typeof sandbox.__veneraHttpDone === 'function') sandbox.__veneraHttpDone(reqId);
            }, opts.latency);
            return reqId;
        },
        httpTake(reqId) {
            if (!host.results.has(reqId)) return null;
            const v = host.results.get(reqId);
            host.results.delete(reqId);
            return v;
        },
    };

    vm.createContext(sandbox);
    vm.runInContext(runtime, sandbox, { filename: 'runtime.js' });
    host.sandbox = sandbox;

    /** 按 App 的方式发起一次 __veneraCall */
    host.call = function (op, args) {
        const id = String(++host.callSeq);
        return new Promise((resolve, reject) => {
            host.pending.set(id, { resolve, reject });
            sandbox.__veneraCall(id, 'legacy', op, JSON.stringify(args || []));
            setTimeout(() => {
                if (host.pending.has(id)) { host.pending.delete(id); reject(new Error('超时')); }
            }, 8000);
        });
    };
    return host;
}

let failed = 0;
function check(name, ok, detail) {
    console.log((ok ? 'PASS ' : 'FAIL ') + name + (ok ? '' : ' — ' + (detail === undefined ? '' : detail)));
    if (!ok) failed++;
}

async function caseAsyncBridge() {
    console.log('\n== 异步网络桥 ==');
    const host = makeHost({ body: 'payload', latency: 60 });
    const N = host.sandbox.Network;

    const p = N.get('https://a.test/1');
    check('Network.get 返回 Promise', p instanceof Promise);
    await p;

    const r1 = await N.get('https://a.test/one');
    check('单请求 body 正确', r1.body === 'payload', JSON.stringify(r1));
    check('单请求走异步桥（同步桥 0 次）', host.syncCalls === 0, 'syncCalls=' + host.syncCalls);

    // 5 个请求：并发时总耗时 ≈ 一次往返；退化成串行则是 5 倍
    host.issued.length = 0;
    const t0 = Date.now();
    const all = await Promise.all([
        N.get('https://x.test/1'), N.get('https://x.test/2'), N.get('https://x.test/3'),
        N.get('https://x.test/4'), N.get('https://x.test/5'),
    ]);
    const elapsed = Date.now() - t0;
    check('5 个请求全部成功', all.length === 5 && all.every(r => r.body === 'payload'));
    check('5 个请求是并发的（总耗时 ≈ 单次往返）', elapsed < 200, 'elapsed=' + elapsed + 'ms（串行应约 300ms）');
    check('5 次 httpAsync 在同一轮里发出', new Set(host.issued).size <= 2, JSON.stringify(host.issued));

    let crashed = false;
    try { host.sandbox.__veneraHttpDone('h-not-exist'); } catch (e) { crashed = true; }
    check('未知 reqId 回调不崩', !crashed);

    // 错误响应必须变成异常，而不是当成空 body
    host.sandbox.NativeBridge.httpAsync = function () {
        const reqId = 'e' + (++host.httpSeq);
        setTimeout(() => {
            host.results.set(reqId, JSON.stringify({ error: '连不上' }));
            host.sandbox.__veneraHttpDone(reqId);
        }, 5);
        return reqId;
    };
    let errMsg = '';
    try { await N.get('https://bad.test/'); } catch (e) { errMsg = e.message; }
    check('错误响应抛 Error', errMsg === '连不上', errMsg);
}

async function caseSelfHeal() {
    console.log('\n== 同步取值的老源自愈 ==');
    const host = makeHost({ source: LEGACY_SOURCE, body: LEGACY_BODY, latency: 5 });
    const reg = JSON.parse(host.sandbox.__veneraRegister('legacy'));
    check('源能注册', reg.ok === true, reg.error);
    if (!reg.ok) return;

    const r1 = JSON.parse(await host.call('search', ['kw', [], 1]));
    check('自愈回落：调用成功', !!(r1 && r1.comics && r1.comics.length === 1), JSON.stringify(r1));
    check('自愈回落：结果来自同步桥', JSON.stringify(r1).indexOf('来自同步桥') >= 0);
    check('第一次确实先试过异步桥', host.asyncCalls === 1, 'asyncCalls=' + host.asyncCalls);
    check('第一次回落用了同步桥', host.syncCalls === 1, 'syncCalls=' + host.syncCalls);
    check('已持久化 sync-only 标记', host.storage['venera:net_sync:legacy'] === '1', JSON.stringify(host.storage));

    const r2 = JSON.parse(await host.call('search', ['kw', [], 1]));
    check('第二次直接走同步桥', !!(r2 && r2.comics && r2.comics.length === 1), JSON.stringify(r2));
    check('第二次没有多余的异步尝试', host.asyncCalls === 1, 'asyncCalls=' + host.asyncCalls);
}

(async () => {
    await caseAsyncBridge();
    await caseSelfHeal();
    console.log('\n' + (failed === 0 ? '全部通过' : '失败 ' + failed + ' 项'));
    process.exit(failed === 0 ? 0 : 1);
})();
