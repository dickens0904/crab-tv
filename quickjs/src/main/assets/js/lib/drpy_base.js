// drpy2 base parsers - injected into drpy2 module scope before defaultParser.
// pdfh/pdfa/pd/joinUrl are referenced as bare globals by drpy2.min.js but not
// defined in the engine. This module provides them using cheerio (imported by drpy2).

function joinUrl(from, to) {
  if (!to) return from || '';
  if (/^https?:\/\//i.test(to)) return to;
  if (!from) return to;
  // 站内绝对路径（/v/1）要拼到 origin，而不是简单拼当前目录
  let m = /^(https?:\/\/[^\/]+)/i.exec(from);
  let origin = m ? m[1] : '';
  if (to.charAt(0) === '/') return origin + to;
  let b = from.substring(0, from.lastIndexOf('/') + 1);
  return b + to;
}

// 引擎的调用形态是：items = pdfa(html, '.item')，随后对每个 item 调 pdfh(item, '.title&&Text')
// 所以 pdfa 必须返回"元素数组"，pdfh 必须能吃"元素"当输入。
function toHtml(html) {
  if (typeof html === 'string') return html;
  if (html === null || html === undefined) return '';
  // drpy 的元素包装对象带 rr/ele；原生 cheerio 节点则需要序列化
  try {
    if (html.rr && html.ele) {
      let s = html.rr(html.ele).toString();
      if (typeof s === 'string' && s) return s;
    }
  } catch (e) {}
  let cands = [];
  try { if (cheerio && cheerio.default && typeof cheerio.default.html === 'function') cands.push(cheerio.default); } catch (e) {}
  try { if (cheerio && typeof cheerio.html === 'function') cands.push(cheerio); } catch (e) {}
  for (let i = 0; i < cands.length; i++) {
    try {
      let s = cands[i].html(html);
      if (typeof s === 'string' && s) return s;
    } catch (e) {}
  }
  try {
    let s = String(html);
    if (s && s !== '[object Object]') return s;
  } catch (e) {}
  return '';
}

function pdfh(html, parse) {
  if (!html || !parse || !parse.trim()) return '';
  html = toHtml(html);
  if (!html) return '';
  let $ = cheerio.load(html);
  let ps = parse.split(';');
  // ps[0] 也带 &&，必须先把选择器与属性拆开再交给 cheerio，否则 "Unmatched selector: &&title"
  let head = ps[0].split('&&');
  let list = $(head[0]);
  if (list.length === 0) return '';
  if (ps.length === 1) {
    let attr = head[1];
    let el = list.first();
    if (!attr || attr.trim() === 'Text') return el.text().trim();
    return el.attr(attr) || '';
  }
  let fn = ['vod_name', 'vod_pic', 'vod_remarks', 'vod_id'];
  let r = [];
  list.each(function (j, el) {
    let o = {};
    for (let i = 1; i < ps.length; i++) {
      let pa = ps[i].split('&&');
      let sel = pa[0];
      let attr = pa[1] || 'Text';
      let k = pa[2] || fn[i - 1] || ('field' + i);
      // drpy 规则里选择器既可能是后代也可能是当前元素自身，两种都要能命中
      let e = $(el).find(sel);
      if (e.length === 0) e = $(el).filter(sel);
      let t = e.first();
      o[k] = attr === 'Text' ? t.text().trim() : (t.attr(attr) || '');
    }
    r.push(o);
  });
  return r;
}

function pdfa(html, parse) {
  if (!html || !parse || !parse.trim()) return [];
  html = toHtml(html);
  if (!html) return [];
  let $ = cheerio.load(html);
  // 只取列表选择器那一段（ps[0]），返回元素数组供引擎逐个调 pdfh
  let head = parse.split(';')[0].split('&&');
  let arr = [];
  $(head[0]).each(function (i, el) {
    arr.push(el);
  });
  return arr;
}

function pd(html, parse, uri) {
  let ret = pdfh(html, parse);
  if (!uri) uri = '';
  if (typeof ret === 'string') {
    if (ret && !/^https?:\/\//i.test(ret)) ret = joinUrl(uri, ret);
  } else if (Array.isArray(ret)) {
    ret.forEach(function (item) {
      if (item.vod_pic && !/^https?:\/\//i.test(item.vod_pic)) item.vod_pic = joinUrl(uri, item.vod_pic);
    });
  }
  return ret;
}

// 源脚本（ext）是独立模块，模块内注入的函数它看不到，而源脚本自身也常用 pdfh。
// 所有模块共享同一个 global 对象，因此同时挂到 globalThis。
try {
  globalThis.pdfh = pdfh;
  globalThis.pdfa = pdfa;
  globalThis.pd = pd;
  globalThis.joinUrl = joinUrl;
} catch (e) {}
