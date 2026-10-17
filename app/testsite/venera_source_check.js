// Venera 源离线走查（开发用，Node 运行，不需要模拟器也不需要真站点）
// ------------------------------------------------------------------
// Venera 源是「可执行 JS」，Android 上要验证一次得打包、装机、点进漫画源 ——
// 反馈环太长。这个脚本用 Node 的 vm 起一个假 WebView：注入 NativeBridge，
// 用 fixtures 里的 HTML 顶替真实网络，然后按 App 的方式调 `__veneraRegister`
// 和 `__veneraCall`，把源的返回结果原样打出来。
//
// 用途：确认「源规则本身有没有坏」——注册得起来吗、分类表读得出来吗、
// 搜索/详情/目录的解析对得上页面结构吗。**它不能证明站点还活着**，
// 站点是否可达要另外测。
//
// 用法（在仓库根目录）：
//   node app/testsite/venera_source_check.js \
//        app/src/main/assets/venera/runtime.js \
//        <源.js> <fixture.json>
//
// fixture.json 形如 { "https://h-comic.com/comics/": "<html>…" }，
// key 是 URL 子串，命中第一个即返回该 HTML；没命中返回 404 空页。
// 退出码非 0 = 有用例失败。
const fs = require('fs');
const vm = require('vm');

const runtimePath = process.argv[2];
const sourcePath = process.argv[3];
const fixturePath = process.argv[4];

if (!runtimePath || !sourcePath) {
    console.error('用法：node venera_source_check.js <runtime.js> <源.js> [fixture.json]');
    process.exit(2);
}

const runtime = fs.readFileSync(runtimePath, 'utf8');
const source = fs.readFileSync(sourcePath, 'utf8');
const fixtures = fixturePath && fs.existsSync(fixturePath)
    ? JSON.parse(fs.readFileSync(fixturePath, 'utf8'))
    : {};

/** 取 fixture 里第一个 URL 子串命中的 HTML */
function bodyFor(url) {
    for (const k of Object.keys(fixtures)) {
        if (url.indexOf(k) >= 0) return fixtures[k];
    }
    return '';
}

const pending = new Map();
const storage = {};
let callSeq = 0;

const sandbox = {
    console: console,
    setTimeout, clearTimeout, setInterval, clearInterval,
    Promise, Map, Set, JSON, Math, Date, RegExp, String, Number, Array, Object,
    ArrayBuffer, Uint8Array, TextDecoder, TextEncoder,
    __requests: [],
};
sandbox.window = sandbox;
sandbox.globalThis = sandbox;
// 让 runtime 的错误回传带上堆栈，离线排查用
sandbox.__veneraDebug = true;

sandbox.NativeBridge = {
    ready() {},
    getSource() { return source; },
    http(method, url, headersJson, data) {
        sandbox.__requests.push(method + ' ' + url);
        const body = bodyFor(url);
        return JSON.stringify({
            status: body ? 200 : 404,
            headers: {},
            body: body,
            url: url,
        });
    },
    httpBinary(method, url) {
        sandbox.__requests.push(method + ' ' + url);
        const body = bodyFor(url);
        return JSON.stringify({ status: body ? 200 : 404, headers: {}, bodyBase64: '', url: url });
    },
    postResult(callId, payload) {
        const p = pending.get(String(callId));
        if (p) { pending.delete(String(callId)); p.resolve(payload); }
    },
    postError(callId, msg) {
        const p = pending.get(String(callId));
        if (p) { pending.delete(String(callId)); p.reject(new Error(msg)); }
    },
    // 源设置 / 登录态都存在这里（App 侧是 DataStore，这里用内存顶替）
    storageGet(key) { return Object.prototype.hasOwnProperty.call(storage, key) ? storage[key] : null; },
    storageSet(key, value) { storage[key] = String(value); },
    storageDelete(key) { delete storage[key]; },
    // 加解密只做「不报错」的占位：离线走查关注的是解析链路，不是密文正确性
    digest() { return ''; },
    digestHex() { return ''; },
    hmac() { return ''; },
    aesDecrypt() { return ''; },
    aesEncrypt() { return ''; },
    setCookie() {}, getCookie() { return ''; }, deleteCookies() {},
    log(level, tag, msg) { console.log('[bridge:' + level + ']', tag, msg); },
};

vm.createContext(sandbox);
vm.runInContext(runtime, sandbox, { filename: 'runtime.js' });

function call(op, args) {
    const id = ++callSeq;
    return new Promise((resolve, reject) => {
        pending.set(String(id), {
            // 原生侧 postResult 传的是 JSON 字符串，这里直接解成对象方便断言
            resolve: (payload) => {
                try {
                    resolve(typeof payload === 'string' ? JSON.parse(payload) : payload);
                } catch (e) {
                    resolve(payload);
                }
            },
            reject,
        });
        sandbox.__veneraCall(String(id), 'src', op, JSON.stringify(args || []));
        setTimeout(() => {
            if (pending.has(String(id))) {
                pending.delete(String(id));
                reject(new Error('超时：' + op));
            }
        }, 15000);
    });
}

(async () => {
    let failed = 0;
    const check = (name, cond, detail) => {
        if (cond) {
            console.log('PASS ' + name);
        } else {
            failed++;
            console.log('FAIL ' + name + (detail ? ' — ' + detail : ''));
        }
    };

    const reg = JSON.parse(sandbox.__veneraRegister('src'));
    check('源能注册', reg.ok === true, reg.error);
    if (!reg.ok) process.exit(1);
    console.log('  源名：' + reg.name + ' v' + reg.version);

    // 分类表
    try {
        const cats = await call('categories', []);
        const parts = (cats && cats.parts) || [];
        const total = parts.reduce((n, p) => n + (p.items || []).length, 0);
        console.log('  分类 parts=' + parts.length + ' 项=' + total);
        check('分类表非空', parts.length > 0 && total > 0);
    } catch (e) {
        check('分类表', false, e.message);
    }

    // 分类浏览
    let categoryItems = 0;
    try {
        const cc = await call('categoryComics', ['全部', null, ['latest-最近更新'], 1]);
        const n = ((cc && cc.comics) || []).length;
        categoryItems = n;
        console.log('  categoryComics 返回 ' + n + ' 条');
        check('分类浏览能取到作品', n > 0, '返回 0 条（fixture 里没有对应页面？）');
        if (n > 0) console.log('    样本：' + JSON.stringify(cc.comics[0]).slice(0, 200));
    } catch (e) {
        check('分类浏览', false, e.message);
    }

    // 发现页（explore，v1.7.8.0）：只有 explore、没有 category 的源靠它浏览
    try {
        const ex = await call('explore', [0, 1]);
        const sections = (ex && ex.sections) || [];
        const total = sections.reduce((n, s) => n + (s.comics || []).length, 0);
        console.log('  explore[0] 分组=' + sections.length + ' 作品=' + total +
            (sections.length ? ' 组名=' + sections.map(s => s.title).join('/') : ''));
        if (categoryItems === 0) {
            check('发现页能取到作品', total > 0, 'explore 也没内容');
        }
    } catch (e) {
        if (categoryItems === 0) check('发现页', false, e.message);
        else console.log('  （该源无 explore，跳过）');
    }

    // 搜索
    try {
        const s = await call('search', ['test', 1, []]);
        const n = ((s && s.comics) || []).length;
        console.log('  search 返回 ' + n + ' 条');
        check('搜索能取到作品', n > 0);
    } catch (e) {
        check('搜索', false, e.message);
    }

    // 详情 + 目录
    try {
        const info = await call('info', ['1|x']);
        console.log('  详情：' + JSON.stringify(info).slice(0, 260));
        const chapters = (info && info.chapters) || [];
        check('详情能取到目录', chapters.length > 0, '目录 0 章');
        if (chapters.length > 0) {
            const ep = chapters[0];
            console.log('    首章：' + JSON.stringify(ep).slice(0, 160));
            // 正文：content 收 (comicId, epId)
            const content = await call('content', ['1|x', ep[0]]);
            const imgs = (content && (content.images || content.comicImages)) || [];
            console.log('  正文图片 ' + imgs.length + ' 张');
            check('正文能取到图片', imgs.length > 0);
        }
    } catch (e) {
        check('详情/正文', false, e.message);
    }

    console.log('\n请求记录：\n  ' + sandbox.__requests.join('\n  '));
    console.log(failed === 0 ? '\n全部通过' : '\n失败 ' + failed + ' 项');
    process.exit(failed === 0 ? 0 : 1);
})().catch((e) => {
    console.error('harness 异常：', e);
    process.exit(1);
});
