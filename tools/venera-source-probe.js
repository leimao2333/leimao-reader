/*
 * Venera 源真网络探针（Node，不需要 WebView / 手机）
 * ------------------------------------------------------------------
 * 目的：把「某个源在真机上到底哪一步坏了」变成一条可复现的命令。
 *
 * 用法：
 *   node tools/venera-source-probe.js <源.js> <场景> [参数...]
 *
 * 场景：
 *   info                 → init + comic.loadInfo（参数：作品 id）
 *   search   <关键词>    → init + search.load(keyword, page=1)
 *   category <url>       → init + category.load(url, page=1)
 *   chapters <id>        → init + comic.loadInfo + comic.loadEp（第一话）
 *   images   <id> [ep]   → init + loadInfo + loadEp + comic.loadImgUrls
 *   raw      <op>        → 直接调 op（调试用）
 *
 * 环境变量：
 *   PROXY=http://127.0.0.1:7890   走代理（这些站点在国内直连不通）
 *   VERBOSE=1                     打印每个 HTTP 请求 / 源日志
 *   SEED_SETTINGS="key:name=value;..."  预置源设置项
 *   HOST_UA="host=desktop;..."    按域名覆盖 UA（排查「站点按 UA 发不同版式」）
 *
 * 与真机的差别（重要）：
 *   - HTTP 走 Node 自带 fetch（OpenSSL + 内置环境代理），**不再用 curl** ——
 *     Windows 的 curl 是 schannel 后端，对部分源站握手失败，会制造假报错；
 *   - 解码 UTF-8 失败且声明了 gbk 时回退 GBK；
 *   - Cookie 存在内存里，不持久化；
 *   - 图片只取 URL，不下载。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');
const { execFileSync, spawn } = require('child_process');

/**
 * DOM 后端：真机上是 WebView 自带的 DOMParser，Node 里用 jsdom 顶上。
 *
 * 需要先装（一次性）：
 *   cd C:/Users/win10/.workbuddy-ai/binaries/node/workspace && npm i jsdom
 * 然后带 NODE_PATH 跑：
 *   NODE_PATH=C:/Users/win10/.workbuddy-ai/binaries/node/workspace/node_modules node tools/venera-source-probe.js ...
 */
function loadDomParser() {
    try {
        const { JSDOM } = require('jsdom');
        const win = new JSDOM('').window;
        return win.DOMParser;
    } catch (e) {
        console.error('缺少 jsdom（DOM 解析不了）：见文件头注释的安装命令');
        process.exit(2);
    }
}

const RUNTIME = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'venera', 'runtime.js');
const PROXY = process.env.PROXY || '';
/**
 * 与 `UserAgents.APP` 一致（`venera/v<应用版本>`）：源没写 UA 时补这个（真机行为）。
 * 版本号直接从 `AppVersion.kt` 里读，避免手抄后过期。
 */
const DEFAULT_UA = 'venera/v' + (function readAppVersion() {
    try {
        const kt = fs.readFileSync(path.join(__dirname, '..', 'app', 'src', 'main',
            'java', 'com', 'novelreader', 'core', 'version', 'AppVersion.kt'), 'utf8');
        const m = /CURRENT_VERSION\s*=\s*"([^"]+)"/.exec(kt);
        if (m) return m[1];
    } catch (e) { /* 读不到就用占位 */ }
    return '1.0.0';
})();
/** SEED_SETTINGS="key:name=value;key:name=value"：预置源的设置项（模拟用户改过设置） */
const SEED = process.env.SEED_SETTINGS || '';
/**
 * HOST_UA="wnacg.com=desktop;foo.com=..."：按域名改 UA。
 *
 * v1.9.2.3 起真机默认 UA 已改成 venera 官方的 `venera/v<版本>`（非移动端），
 * 桌面版式是默认行为了，这个开关现在只用于「怀疑某站又按 UA 分流」时做对照实验。
 */
const DESKTOP_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
    + '(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36';
const HOST_UA = new Map();
for (const item of (process.env.HOST_UA || '').split(';')) {
    const i = item.indexOf('=');
    if (i > 0) {
        const host = item.slice(0, i).trim();
        const ua = item.slice(i + 1).trim();
        HOST_UA.set(host, ua === 'desktop' ? DESKTOP_UA : ua);
    }
}
const VERBOSE = process.env.VERBOSE === '1';

// ---------------------------------------------------------------- HTTP

/**
 * HTTP 走 [venera-http-helper.js] 子进程（Node 自带 OpenSSL + 内置环境代理）。
 *
 * 历史教训：此前用 `curl` 子进程。Windows 上 curl 是 **schannel** 后端，对
 * wnacg.com / godamh.com 会 `SEC_E_ILLEGAL_MESSAGE` 握手失败 —— 而真机上的 OkHttp
 * 走自己的 TLS 栈完全正常，于是探针报的错**不是真机的错**。换成 Node 之后这两个源
 * 立刻能测，且与真机的 TLS 行为更接近。
 */
const HELPER = path.join(__dirname, 'venera-http-helper.js');

/** 子进程环境：打开 Node 内置环境代理（22.10+），并把 PROXY 映射成标准变量 */
function helperEnv() {
    const env = Object.assign({}, process.env, { NODE_USE_ENV_PROXY: '1' });
    if (PROXY) {
        env.HTTPS_PROXY = PROXY;
        env.HTTP_PROXY = PROXY;
        env.https_proxy = PROXY;
        env.http_proxy = PROXY;
    }
    // 关掉那条 experimental 警告，免得混进 stderr 干扰排查
    env.NODE_NO_WARNINGS = '1';
    return env;
}

/** 解析 helper 的一条响应（NDJSON 的一行），失败时抛错 */
function parseHelper(obj) {
    if (!obj) throw new Error('helper 没有返回内容');
    if (obj.error) throw new Error(obj.error);
    return {
        status: obj.status,
        headers: obj.headers || {},
        bytes: Buffer.from(obj.bodyBase64 || '', 'base64'),
        url: obj.url,
    };
}

// ---- 常驻工作池 -------------------------------------------------------
//
// Windows 上 Node 启动 ~380ms，hitomi 一次搜索 34 个请求 ——「一请求一进程」
// 光启动就 13 秒，量出来的耗时全是假的。这里起 [POOL_SIZE] 个常驻进程，
// 按空闲状态派活，请求之间不再付启动成本。

/** 默认 24 = 真机 `VeneraEngine.MAX_CONCURRENT_HTTP`，让探针的并发和真机一致 */
const POOL_SIZE = Number(process.env.POOL_SIZE || 24);
const workers = [];
let workerSeq = 0;

function spawnWorker() {
    const child = spawn(process.execPath, [HELPER], {
        env: helperEnv(),
        stdio: ['pipe', 'pipe', 'pipe'],
    });
    const w = { child, busy: false, waiters: new Map(), buf: '', dead: false };
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
        w.buf += chunk;
        let i;
        while ((i = w.buf.indexOf('\n')) >= 0) {
            const line = w.buf.slice(0, i).trim();
            w.buf = w.buf.slice(i + 1);
            if (!line) continue;
            let obj;
            try {
                obj = JSON.parse(line);
            } catch (e) {
                continue;
            }
            const waiter = w.waiters.get(obj.id);
            if (waiter) {
                w.waiters.delete(obj.id);
                waiter(obj);
            }
        }
    });
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', () => { /* 有错会走 stdout 的 error 字段 */ });
    child.on('error', () => { w.dead = true; });
    child.on('close', () => {
        w.dead = true;
        // 进程没了：把还挂着的请求全部判失败，别让调用方永远等
        for (const [, waiter] of w.waiters) waiter({ error: 'helper 进程意外退出' });
        w.waiters.clear();
        const idx = workers.indexOf(w);
        if (idx >= 0) workers.splice(idx, 1);
    });
    workers.push(w);
    return w;
}

/** 取一个空闲 worker；全忙就临时再起一个（上限 [POOL_SIZE] 之后排队） */
function pickWorker() {
    for (const w of workers) {
        if (!w.busy && !w.dead) return w;
    }
    if (workers.length < POOL_SIZE) return spawnWorker();
    return null;
}

/** 等一个空闲 worker（全忙时轮询；池子有上限，避免一次打出上百个进程） */
function waitForWorker() {
    return new Promise((resolve) => {
        const tick = () => {
            const w = pickWorker();
            if (w) resolve(w);
            else setTimeout(tick, 15);
        };
        tick();
    });
}

/** 异步发一次请求（走工作池） */
function httpAsyncRaw(method, url, headers, body, cb) {
    waitForWorker().then((w) => {
        const id = 'q' + (++workerSeq);
        w.busy = true;
        const done = (obj) => {
            w.busy = false;
            try {
                cb(null, parseHelper(obj));
            } catch (e) {
                cb(e);
            }
        };
        w.waiters.set(id, done);
        if (w.dead) {
            w.waiters.delete(id);
            w.busy = false;
            cb(new Error('helper 进程不可用'));
            return;
        }
        w.child.stdin.write(JSON.stringify({ id, method, url, headers, body }) + '\n');
    });
}

/** 探针结束时收掉工作池 */
function shutdownPool() {
    for (const w of workers.slice()) {
        try { w.child.stdin.end(); } catch (e) { /* ignore */ }
    }
}

/** 同步发一次请求（给同步桥用：单发一个进程，喂一行就跑） */
function httpSync(method, url, headers, body) {
    const payload = JSON.stringify({ method, url, headers, body }) + '\n';
    let out;
    try {
        out = execFileSync(process.execPath, [HELPER], {
            input: payload,
            maxBuffer: 128 * 1024 * 1024,
            encoding: 'utf8',
            env: helperEnv(),
        });
    } catch (e) {
        const msg = e.stderr ? e.stderr.toString('utf8').slice(0, 300) : String(e);
        throw new Error('http 失败: ' + msg);
    }
    const line = String(out).split('\n').find((l) => l.trim());
    if (!line) throw new Error('helper 没有返回内容');
    return parseHelper(JSON.parse(line));
}

/** 解码正文：优先 UTF-8，出现替换字符且声明了 gbk 时回退 GBK */
function decode(bytes, contentType) {
    const utf8 = new TextDecoder('utf-8').decode(bytes);
    const bad = (utf8.match(/\uFFFD/g) || []).length;
    const ct = String(contentType || '').toLowerCase();
    if (bad > 0 && /gbk|gb2312|gb18030/.test(ct)) {
        try {
            return new TextDecoder('gbk').decode(bytes);
        } catch (e) { /* 没编进 ICU 就算了 */ }
    }
    return utf8;
}

// ---------------------------------------------------------------- 源码补丁

/**
 * 从 `SourceRepairs.kt` 里解析出源码补丁表，按源 key 索引。
 *
 * 为什么解析 Kotlin 而不是在探针里抄一份：抄一份必然会漂 —— 补丁改了探针没改，
 * 于是「探针测过没问题、装机还是坏的」（这个项目在 APK 资源与磁盘文件不一致上
 * 已经踩过一次）。解析失败只告警、按「无补丁」跑，不会让探针挂掉。
 *
 * 只认 `CodeFix(name = "...", from = """...""", to = """...""")` 这种三引号写法。
 */
function loadCodeFixes() {
    const file = path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'com',
        'novelreader', 'source', 'venera', 'SourceRepairs.kt');
    const out = new Map();
    let kt;
    try {
        kt = fs.readFileSync(file, 'utf8');
    } catch (e) {
        console.warn('读不到 SourceRepairs.kt，按「无补丁」跑：' + e.message);
        return out;
    }
    // 不按「块」切（嵌套括号 + 尾逗号让括号配对很脆），改成按**位置归属**：
    // 先记下每个 `"key" to listOf(` 的偏移，再把每条 CodeFix 归给「它之前最近的那个 key」。
    const keyRe = /"([A-Za-z0-9_.-]+)"\s+to\s+listOf\(/g;
    const fixRe = /CodeFix\(\s*name\s*=\s*"([^"]*)"\s*,\s*from\s*=\s*"""([\s\S]*?)"""\s*,\s*to\s*=\s*"""([\s\S]*?)"""\s*,?\s*\)/g;
    const keys = [];
    let m;
    while ((m = keyRe.exec(kt)) !== null) keys.push({ at: m.index, key: m[1] });
    const fixes = [];
    while ((m = fixRe.exec(kt)) !== null) {
        fixes.push({ at: m.index, name: m[1], from: m[2], to: m[3] });
    }
    for (const f of fixes) {
        let owner = null;
        for (const k of keys) {
            if (k.at < f.at) owner = k.key;
            else break;
        }
        if (!owner) continue;
        if (!out.has(owner)) out.set(owner, []);
        out.get(owner).push(f);
    }
    return out;
}

const CODE_FIXES = loadCodeFixes();

/** 与 SourceRepairs.patchCode 等价：字面量替换，未命中只告警 */
function applyCodeFixes(key, code) {
    const fixes = CODE_FIXES.get(key);
    if (!fixes || !fixes.length) return code;
    let out = code;
    for (const fix of fixes) {
        if (out.includes(fix.to)) continue;
        if (!out.includes(fix.from)) {
            console.warn(`源码补丁未命中（上游可能已改版）：${key}/${fix.name}`);
            continue;
        }
        out = out.split(fix.from).join(fix.to);
        if (VERBOSE) console.log(`  已应用源码补丁：${key}/${fix.name}`);
    }
    return out;
}

// ---------------------------------------------------------------- 假宿主

function makeHost(sourcePath, key) {
    const sourceCode = applyCodeFixes(key, fs.readFileSync(sourcePath, 'utf8'));
    const store = new Map();
    const cookies = new Map(); // host -> { name: value }
    const pending = new Map();
    const asyncResults = new Map();
    const logs = [];
    const requests = [];
    let seq = 0;
    /** 由 context 创建后回填：异步请求完成时回调 JS 的 __veneraHttpDone(reqId) */
    let notifyDone = () => {};

    function cookieHeaderFor(url) {
        const host = new URL(url).host;
        const jar = cookies.get(host);
        if (!jar) return '';
        return Object.entries(jar).map(([k, v]) => `${k}=${v}`).join('; ');
    }

    /** 补默认 UA 与 Cookie（与原生 HttpUrl 那一层一致） */
    function withDefaults(headers, url) {
        const has = (n) => Object.keys(headers).some((k) => k.toLowerCase() === n);
        if (!has('user-agent')) {
            let ua = DEFAULT_UA;
            try {
                const host = new URL(url).hostname;
                for (const [h, v] of HOST_UA) {
                    if (host === h || host.endsWith('.' + h)) { ua = v; break; }
                }
            } catch (e) { /* 相对 URL 之类，用默认 */ }
            headers['User-Agent'] = ua;
        }
        if (!has('cookie')) {
            const c = cookieHeaderFor(url);
            if (c) headers['Cookie'] = c;
        }
        return headers;
    }

    function doHttp(method, url, headersJson, data) {
        const headers = withDefaults(JSON.parse(headersJson || '{}'), url);
        requests.push({ method, url });
        let r;
        try {
            r = httpSync(method, url, headers, data);
        } catch (e) {
            // 失败也要带上 URL：不然「TLS 失败」根本不知道打的是哪个域名
            throw new Error(`${method} ${url} → ${e.message}`);
        }
        const body = decode(r.bytes, r.headers['content-type']);
        if (VERBOSE) {
            console.log(`  → ${method} ${url}  [${r.status}, ${body.length} 字符]`);
        }
        return JSON.stringify({
            status: r.status,
            headers: r.headers,
            body,
            url,
        });
    }

    function doHttpBinary(method, url, headersJson, data) {
        const headers = withDefaults(JSON.parse(headersJson || '{}'), url);
        requests.push({ method, url });
        const r = httpSync(method, url, headers, data);
        if (VERBOSE) {
            console.log(`  → ${method} ${url}  [${r.status}, ${r.bytes.length} 字节]`);
        }
        return JSON.stringify({
            status: r.status,
            headers: r.headers,
            bodyBase64: r.bytes.toString('base64'),
            url,
        });
    }

    const bridge = {
        ready() {},
        getSource() { return sourceCode; },
        postResult(callId, json) {
            const slot = pending.get(callId);
            if (slot) { pending.delete(callId); slot.resolve(json); }
        },
        postError(callId, message) {
            const slot = pending.get(callId);
            if (slot) { pending.delete(callId); slot.reject(new Error(message)); }
        },
        log(level, message) {
            logs.push(`${level} ${message}`);
            if (VERBOSE) console.log(`  [${level}] ${message}`);
        },
        storageGet(k) { return store.has(k) ? store.get(k) : null; },
        storageSet(k, v) { store.set(k, v); },
        storageDelete(k) { store.delete(k); },
        http(method, url, headersJson, data) {
            return doHttp(method, url, headersJson, data);
        },
        httpBinary(method, url, headersJson, data) {
            return doHttpBinary(method, url, headersJson, data);
        },
        /**
         * 真异步：起一个 curl 子进程就返回 reqId，完成后回调 JS。
         *
         * 这里必须真异步 —— 之前用 execFileSync 会把「并发」测成串行，
         * 一章 72 图能测出 100 秒，完全是假象。
         */
        httpAsync(method, url, headersJson, data, binary) {
            const reqId = 'r' + (++seq);
            const headers = withDefaults(JSON.parse(headersJson || '{}'), url);
            requests.push({ method, url });
            httpAsyncRaw(method, url, headers, data, (err, r) => {
                if (err) {
                    asyncResults.set(reqId, JSON.stringify({
                        error: `${method} ${url} → ${err.message}`,
                    }));
                } else if (binary) {
                    if (VERBOSE) console.log(`  → ${method} ${url}  [${r.status}, ${r.bytes.length} 字节]`);
                    asyncResults.set(reqId, JSON.stringify({
                        status: r.status, headers: r.headers,
                        bodyBase64: r.bytes.toString('base64'), url,
                    }));
                } else {
                    const body = decode(r.bytes, r.headers['content-type']);
                    if (VERBOSE) console.log(`  → ${method} ${url}  [${r.status}, ${body.length} 字符]`);
                    asyncResults.set(reqId, JSON.stringify({
                        status: r.status, headers: r.headers, body, url,
                    }));
                }
                notifyDone(reqId);
            });
            return reqId;
        },
        httpTake(reqId) {
            const v = asyncResults.get(reqId);
            if (v === undefined) return null;
            asyncResults.delete(reqId);
            return v;
        },
        setCookie(url, cookieLines) {
            const host = new URL(url).host;
            const jar = cookies.get(host) || {};
            for (const line of String(cookieLines).split('\n')) {
                const first = line.split(';')[0];
                const i = first.indexOf('=');
                if (i > 0) jar[first.slice(0, i).trim()] = first.slice(i + 1).trim();
            }
            cookies.set(host, jar);
        },
        getCookie(url) {
            const host = new URL(url).host;
            const jar = cookies.get(host) || {};
            return Object.entries(jar).map(([k, v]) => `${k}=${v}`).join('\n');
        },
        deleteCookies(url) {
            cookies.delete(new URL(url).host);
        },
        digest(alg, b64) {
            const crypto = require('crypto');
            const h = crypto.createHash(alg).update(Buffer.from(b64, 'base64')).digest();
            return h.toString('hex');
        },
        hmac(alg, keyB64, dataB64) {
            const crypto = require('crypto');
            return crypto.createHmac(alg, Buffer.from(keyB64, 'base64'))
                .update(Buffer.from(dataB64, 'base64')).digest('hex');
        },
        aesDecrypt(mode, dataB64, keyB64, ivB64) {
            const crypto = require('crypto');
            const alg = mode === 'cbc' ? 'aes-128-cbc' : 'aes-128-ecb';
            const d = crypto.createDecipheriv(alg, Buffer.from(keyB64, 'base64'),
                mode === 'cbc' ? Buffer.from(ivB64, 'base64') : null);
            return Buffer.concat([d.update(Buffer.from(dataB64, 'base64')), d.final()])
                .toString('base64');
        },
    };

    const sandbox = {
        console: {
            log: (...a) => { logs.push('console.log ' + a.join(' ')); if (VERBOSE) console.log('  [js]', ...a); },
            warn: (...a) => { logs.push('console.warn ' + a.join(' ')); },
            error: (...a) => { logs.push('console.error ' + a.join(' ')); if (VERBOSE) console.log('  [js!]', ...a); },
            info() {}, debug() {},
        },
        setTimeout, clearTimeout, setInterval, clearInterval,
        Promise, Date, Math, JSON, Object, Array, String, Number, Boolean,
        RegExp, Error, TypeError, Map, Set, Uint8Array, ArrayBuffer,
        TextEncoder, TextDecoder,
        btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
        atob: (s) => Buffer.from(s, 'base64').toString('binary'),
        URL, URLSearchParams,
        DOMParser: loadDomParser(),
    };
    sandbox.window = sandbox;
    sandbox.NativeBridge = bridge;
    sandbox.globalThis = sandbox;

    const context = vm.createContext(sandbox);
    notifyDone = (reqId) => {
        try {
            vm.runInContext(`__veneraHttpDone(${JSON.stringify(reqId)})`, context);
        } catch (e) { /* 调用已结束，忽略 */ }
    };
    vm.runInContext(fs.readFileSync(RUNTIME, 'utf8'), context, { filename: 'runtime.js' });

    return {
        logs, requests, store, cookies,
        register() {
            return JSON.parse(vm.runInContext(`__veneraRegister(${JSON.stringify(key)})`, context));
        },
        call(op, args) {
            const callId = 'c' + (++seq);
            const p = new Promise((resolve, reject) => pending.set(callId, { resolve, reject }));
            vm.runInContext(
                `__veneraCall(${JSON.stringify(callId)}, ${JSON.stringify(key)}, ` +
                `${JSON.stringify(op)}, ${JSON.stringify(JSON.stringify(args || []))})`,
                context,
            );
            return p;
        },
        /** 源注册后会异步 init：把定时器 / 微任务推进若干轮 */
        async settle(rounds = 30) {
            for (let i = 0; i < rounds; i++) {
                await new Promise((r) => setImmediate(r));
            }
        },
    };
}

// ---------------------------------------------------------------- 主流程

function pretty(v, maxLen = 1200) {
    const s = typeof v === 'string' ? v : JSON.stringify(v, null, 2);
    return s.length > maxLen ? s.slice(0, maxLen) + ` …(+${s.length - maxLen})` : s;
}

async function main() {
    const [srcArg, scenario, ...rest] = process.argv.slice(2);
    if (!srcArg || !scenario) {
        console.error('用法: node tools/venera-source-probe.js <源.js> <info|search|category|chapters|images|raw> [参数]');
        process.exit(2);
    }
    const srcPath = path.resolve(srcArg);
    const key = path.basename(srcPath, '.js');

    const host = makeHost(srcPath, key);
    if (SEED) {
        for (const item of SEED.split(';')) {
            const eq = item.indexOf('=');
            if (eq < 0) continue;
            const left = item.slice(0, eq);
            const colon = left.lastIndexOf(':');
            if (colon < 0) continue;
            const k = left.slice(0, colon);
            const n = left.slice(colon + 1);
            host.store.set(`venera:setting:${k}:${n}`, item.slice(eq + 1));
            console.log(`预置设置 ${k}.${n} = ${item.slice(eq + 1)}`);
        }
    }
    const reg = host.register();
    console.log(`注册: ${JSON.stringify(reg)}`);
    await host.settle();

    const t0 = Date.now();
    let out;
    try {
        switch (scenario) {
            case 'info':
                out = await host.call('info', [rest[0]]);
                break;
            case 'search':
                out = await host.call('search', [rest[0], Number(rest[1] || 1)]);
                break;
            case 'categories':
                out = await host.call('categories', []);
                break;
            case 'category':
                // category <分类名> <param> [optionsJSON] [page]
                out = await host.call('categoryComics', [
                    rest[0], rest[1] || '', rest[2] ? JSON.parse(rest[2]) : [], Number(rest[3] || 1),
                ]);
                break;
            case 'chapters': {
                const info = JSON.parse(await host.call('info', [rest[0]]));
                const ch = info.chapters || [];
                console.log('作品:', info.title, '| 章节数:', ch.length, '| 首话:', JSON.stringify(ch[0]));
                out = JSON.stringify(ch.slice(0, 5));
                break;
            }
            case 'images': {
                const info = JSON.parse(await host.call('info', [rest[0]]));
                const ch = info.chapters || [];
                const epId = rest[1] || (ch[0] && ch[0][0]);
                console.log('作品:', info.title, '| 章节数:', ch.length, '| 本话:', epId);
                const imgs = await host.call('content', [rest[0], epId]);
                const obj = JSON.parse(imgs) || {};
                // content 返回 { images, headers, scripts }（图片是页码 / 地址数组）
                const list = Array.isArray(obj) ? obj : (obj.images || []);
                console.log('图片数:', list.length);
                console.log('前两张:', JSON.stringify(list.slice(0, 2)));
                console.log('请求头:', JSON.stringify(obj.headers || {}));
                console.log('乱序脚本:', Object.keys(obj.scripts || {}).length, '张');
                // 惰性解析（v1.9.2.3）：超出预解析上限的图片是 venera-image: 占位地址
                const lazy = list.filter((u) => typeof u === 'string' && u.startsWith('venera-image:'));
                console.log('惰性占位:', lazy.length, '张', lazy.length ? '| 样本 ' + lazy[0] : '');
                if (lazy.length) {
                    const one = JSON.parse(await host.call('retryImage', [lazy[0]]));
                    console.log('惰性解析结果:', one ? JSON.stringify({
                        url: String(one.url || '').slice(0, 120),
                        headerKeys: Object.keys(one.headers || {}),
                        hasScript: !!one.script,
                    }) : 'null');
                }
                out = JSON.stringify({ count: list.length, lazy: lazy.length });
                break;
            }
            case 'raw':
                out = await host.call(rest[0], rest.slice(1));
                break;
            default:
                console.error('未知场景: ' + scenario);
                process.exit(2);
        }
        console.log(`\n耗时 ${Date.now() - t0}ms，请求 ${host.requests.length} 次`);
        console.log('结果:\n' + pretty(out));
    } catch (e) {
        console.log(`\n失败（${Date.now() - t0}ms，请求 ${host.requests.length} 次）:`);
        console.log('  ' + (e && e.message ? e.message : String(e)));
        if (host.logs.length) console.log('源日志:\n  ' + host.logs.slice(-12).join('\n  '));
        process.exitCode = 1;
    }
}

main().then(
    () => { shutdownPool(); process.exit(process.exitCode || 0); },
    (e) => { shutdownPool(); console.error(e); process.exit(1); },
);
