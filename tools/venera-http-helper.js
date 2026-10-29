/*
 * venera-source-probe 的 HTTP 传输层（子进程 / 常驻工作进程）
 * ------------------------------------------------------------------
 * 为什么不用 curl：Windows 上 `curl` 是 **schannel** 后端，对部分源站
 * （实测 wnacg.com / godamh.com）会 `SEC_E_ILLEGAL_MESSAGE` 握手失败，而真机上的
 * OkHttp 走的是自带的 TLS 栈、完全没有这个问题 —— 于是「探针报错、真机正常」，
 * 把工具变成了误报源。Node 自带 OpenSSL，直接用它发请求才和真机可比。
 *
 * 代理：Node 22.10+ 支持 `NODE_USE_ENV_PROXY=1` + `HTTPS_PROXY`，走内置
 * EnvHttpProxyAgent（CONNECT 隧道），不额外装依赖。
 *
 * 协议：**NDJSON**，一行一个请求 / 一行一个响应。这样同一个进程可以连续处理
 * 多个请求 —— Windows 上 Node 启动要 ~380ms，hitomi 搜索一次 34 个请求，
 * 「一请求一进程」光启动就吃掉 13 秒，量出来的耗时全是假的。
 *
 *   请求  { id, method, url, headers, body, timeoutMs, redirect }
 *   响应  { id, status, headers, bodyBase64, url }  或  { id, error }
 *
 * stdin 关闭时进程退出（单发模式：探针的同步桥用 execFileSync 喂一行就跑）。
 */
'use strict';

const readline = require('readline');

const FORBIDDEN = new Set([
    // fetch 自己管这些；硬塞会抛错或破坏自动解压
    'host', 'content-length', 'connection', 'accept-encoding', 'transfer-encoding',
]);

function cleanHeaders(raw) {
    const out = {};
    for (const [k, v] of Object.entries(raw || {})) {
        if (v === undefined || v === null) continue;
        if (FORBIDDEN.has(String(k).toLowerCase())) continue;
        out[k] = String(v);
    }
    return out;
}

async function handle(req) {
    const id = req.id;
    const method = String(req.method || 'GET').toUpperCase();
    const init = {
        method,
        headers: cleanHeaders(req.headers),
        redirect: req.redirect || 'follow',
        signal: AbortSignal.timeout(req.timeoutMs || 60000),
    };
    if (method !== 'GET' && method !== 'HEAD' && req.body !== undefined && req.body !== null) {
        init.body = String(req.body);
    }
    try {
        const res = await fetch(req.url, init);
        const buf = Buffer.from(await res.arrayBuffer());
        const headers = {};
        res.headers.forEach((v, k) => { headers[k.toLowerCase()] = v; });
        return { id, status: res.status, headers, bodyBase64: buf.toString('base64'), url: res.url || req.url };
    } catch (e) {
        // undici 把真实原因放在 cause 里（TLS / DNS / 连接重置都在那儿）
        const cause = e && e.cause;
        const detail = cause ? `${cause.code || cause.name || ''} ${cause.message || ''}`.trim() : '';
        return { id, error: (e && e.message ? e.message : String(e)) + (detail ? ' | ' + detail : '') };
    }
}

/** 串行处理，保证同一 worker 内请求不乱序（探针侧靠 id 配对，其实也无所谓） */
let chain = Promise.resolve();

function write(obj) {
    process.stdout.write(JSON.stringify(obj) + '\n');
}

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
rl.on('line', (line) => {
    const text = line.trim();
    if (!text) return;
    let req;
    try {
        req = JSON.parse(text);
    } catch (e) {
        write({ id: null, error: '入参不是合法 JSON: ' + e.message });
        return;
    }
    chain = chain.then(() => handle(req)).then(write, (e) => write({ id: req && req.id, error: String(e) }));
});
rl.on('close', () => {
    // 等最后一条请求写完再退出，否则 execFileSync 的同步模式会拿到空输出
    chain.then(() => process.exit(0), () => process.exit(0));
});
