// Venera 运行时纯逻辑校验（开发用，Node 运行，不需要模拟器）
// ------------------------------------------------------------------
// `app/src/main/assets/venera/runtime.js` 里的目录摊平与 tags 归一都是**纯函数**，
// 但在 Android 上要看到结果得先打包、装机、翻页 —— 反馈环太长，
// 于是出现过「目录整列显示 [object Map]」这种一眼就能测出来的低级问题。
//
// 用法（在仓库根目录）：
//   node app/testsite/venera_pure_check.js app/src/main/assets/venera/runtime.js
// 退出码非 0 表示有用例失败。
//
// 做法：按函数名把小节从 runtime.js 里抠出来，在沙箱里 eval 后跑断言
// （runtime.js 是 WebView 内的大闭包，无法直接 require）。
const fs = require('fs');

const SRC = process.argv[2];
const text = fs.readFileSync(SRC, 'utf8');

/** 按 `function NAME(` 定位并吃掉配对大括号，抠出函数源码 */
function extract(name) {
    const start = text.indexOf('function ' + name + '(');
    if (start < 0) throw new Error('找不到函数 ' + name);
    let i = text.indexOf('{', start);
    let depth = 0;
    for (let j = i; j < text.length; j++) {
        if (text[j] === '{') depth++;
        else if (text[j] === '}') {
            depth--;
            if (depth === 0) return text.slice(start, j + 1);
        }
    }
    throw new Error('函数 ' + name + ' 大括号不配对');
}

/** 抠出顶层 var 声明（支持跨多行的数组/对象字面量） */
function extractVar(name) {
    const head = new RegExp('^\\s*var\\s+' + name + '\\s*=', 'm').exec(text);
    if (!head) throw new Error('找不到变量 ' + name);
    let depth = 0;
    for (let j = head.index + head[0].length - 1; j < text.length; j++) {
        const c = text[j];
        if (c === '[' || c === '{' || c === '(') depth++;
        else if (c === ']' || c === '}' || c === ')') depth--;
        else if (c === ';' && depth === 0) return text.slice(head.index, j + 1).trim();
    }
    throw new Error('变量 ' + name + ' 结尾分号找不到');
}

const parts = [
    extractVar('GENERIC_GROUP_NAMES'),
    extractVar('TAG_GROUP_SKIP'),
    extractVar('VOLUME_NAME_RES'),
    extract('entriesOf'),
    extract('isChapterGroup'),
    extract('groupLabel'),
    extract('chapterTitle'),
    extract('labelChapter'),
    extract('chaptersToPairs'),
    extract('tagName'),
    extract('flattenTags'),
];

const sandbox = {};
new Function('S', parts.join('\n') + `
    S.entriesOf = entriesOf;
    S.isChapterGroup = isChapterGroup;
    S.groupLabel = groupLabel;
    S.chapterTitle = chapterTitle;
    S.labelChapter = labelChapter;
    S.chaptersToPairs = chaptersToPairs;
    S.flattenTags = flattenTags;
`)(sandbox);

let pass = 0;
const fails = [];
function eq(label, actual, expected) {
    const a = JSON.stringify(actual);
    const e = JSON.stringify(expected);
    if (a === e) { pass++; } else { fails.push(label + '\n   期望 ' + e + '\n   实际 ' + a); }
}

// ===== chaptersToPairs =====
eq('扁平 Map<epId,标题>',
    sandbox.chaptersToPairs(new Map([['u1', '第1话'], ['u2', '第2话']])),
    [['u1', '第1话'], ['u2', '第2话']]);

eq('普通对象',
    sandbox.chaptersToPairs({ a: '第1话', b: '第2话' }),
    [['a', '第1话'], ['b', '第2话']]);

// 漫画人 manhuaren 的形态：Map<分组名, Map<href, 标题>>
{
    const inner1 = new Map([['h1', '第 01 话'], ['h2', '第 02 话']]);
    const inner2 = new Map([['h3', '第 03 话']]);
    const nested = new Map([['连载', inner1], ['第 2 卷', inner2]]);
    const pairs = sandbox.chaptersToPairs(nested);
    eq('两层 Map - 摊平条数', pairs.length, 3);
    eq('两层 Map - 通用分组名不拼前缀', pairs[0][1], '第 01 话');
    eq('两层 Map - 卷名拼前缀', pairs[2][1], '第 2 卷 第 03 话');
    eq('两层 Map - 用内层键当 epId', pairs.map(p => p[0]), ['h1', 'h2', 'h3']);
}

// 临界：绝不能再出现 [object Map]
{
    const nested = new Map([['卷1', new Map([['h1', '第1话']])]]);
    const pairs = sandbox.chaptersToPairs(nested);
    const rendered = pairs.map(p => String(p[1]));
    eq('不出现 [object Map]',
        rendered.filter(t => t.indexOf('[object') >= 0), []);
}

eq('数组对形态 [[epId,标题],…]',
    sandbox.chaptersToPairs([['h1', '第1话'], ['h2', '第2话']]),
    [['h1', '第1话'], ['h2', '第2话']]);

eq('空值安全', sandbox.chaptersToPairs(null), []);
eq('空 Map 安全', sandbox.chaptersToPairs(new Map()), []);

eq('单章值是对象(老式写法)不被当成分组',
    sandbox.chaptersToPairs(new Map([['h1', { title: '第1话', url: 'x' }]]))
        .map(p => sandbox.chapterTitle(p[1])),
    ['第1话']);

eq('标题已含分组名时不重复拼',
    sandbox.chaptersToPairs(new Map([['第 2 卷', new Map([['h3', '第 2 卷 第 03 话']])]]))[0][1],
    '第 2 卷 第 03 话');

// ===== flattenTags =====
eq('tags 数组', sandbox.flattenTags(['玄幻', '热血', '玄幻']), ['玄幻', '热血']);
eq('tags 对象(包子/kavita 写法)',
    sandbox.flattenTags({ 作者: ['张三'], 类型: ['热血', '格斗'], 标签: ['本地'] }),
    ['热血', '格斗', '本地']);
eq('tags 对象-单串值', sandbox.flattenTags({ 标签: '都市' }), ['都市']);
eq('tags 空', sandbox.flattenTags(null), []);
eq('tags 全是元信息分组', sandbox.flattenTags({ 作者: ['张三'] }), []);

console.log('通过 ' + pass + ' 项，失败 ' + fails.length + ' 项');
if (fails.length) {
    console.log('\n失败明细：\n- ' + fails.join('\n- '));
    process.exit(1);
}
