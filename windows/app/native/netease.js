'use strict';

/**
 * 网易云音乐接口层（Node.js / CommonJS，无第三方依赖）
 *
 * 逐行移植自 Android Java 版：
 *   Http.java        —— httpPostForm / get / getRange（UA、Referer、Cookie、gzip、3 次重试）
 *   Weapi.java       —— AES-128-CBC(NoPadding + 手写 PKCS7) + Base64 + RSA(BigInt)
 *   Eapi.java        —— AES-128-ECB(PKCS7) + hex + md5 摘要
 *   NeteaseApi.java  —— 搜索 / 详情 / 播放地址 / 探测 / 音频头 / 歌词 / 歌单 / 专辑 / 链接解析
 *   NeteaseLogin.java—— 扫码 / 短信验证码登录（eapi 优先，失败回退 weapi）
 *   ApiBridge.java   —— testCookie / levels / parse / qrCreate / qrCheck / loginByCode 的调用点
 *
 * 与 Java 的差异（均已在对应方法上注明）：
 *   - 返回值是普通 JS 对象/数组，而不是 JSON 字符串。
 *   - qrCreate() 额外返回 img（PNG data URL）与 qrContent（二维码内容），
 *     同时保留 Java 原生的 code / unikey 字段。
 *   - qrCheck() 保留了一个防御性兜底：weapi 返回空 body / 非 JSON 时回退到明文 api
 *     接口（正常网络下不会触发；Java 原实现没有这个兜底）。
 *   - sendLoginCode / loginByCode 的 ctcode 参数可覆盖 Java 里写死的 "86"（传空仍为 86）。
 */

const crypto = require('crypto');
const http = require('http');
const https = require('https');
const zlib = require('zlib');
const { URL } = require('url');

// ===========================================================================
// 常量（与 Java 完全一致）
// ===========================================================================

const UA =
  'Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/91.0.4472.164 NeteaseMusicDesktop/2.10.2.200154';
const REFERER = 'https://music.163.com/';

const CONNECT_TIMEOUT = 15000;
const READ_TIMEOUT = 25000;

const SONG_URL_V1 = 'https://interface3.music.163.com/eapi/song/enhance/player/url/v1';
const SONG_DETAIL_V3 = 'https://interface3.music.163.com/api/v3/song/detail';
const LYRIC_API = 'https://interface3.music.163.com/api/song/lyric';
const SEARCH_API = 'https://music.163.com/api/cloudsearch/pc';
const PLAYLIST_DETAIL_API = 'https://music.163.com/api/v6/playlist/detail';
const ALBUM_DETAIL_API = 'https://music.163.com/api/v1/album/';
const ACCOUNT_API = 'https://music.163.com/api/nuser/account/get';

const LEVEL_CHAIN = ['jymaster', 'hires', 'lossless', 'exhigh', 'standard'];
const LEVEL_KEYS = [
  'standard', 'exhigh', 'lossless', 'hires', 'jyeffect', 'sky', 'jymaster', 'dolby'
];
const LEVEL_NAMES = [
  '标准 128K', '极高 320K', '无损 FLAC', 'Hi-Res', '高清环绕声', '沉浸环绕声', '超清母带', '杜比全景声'
];

const APP_VER = '8.10.42';
const OS_VER = '14';
const DEVICE_ID = '7f3a9c2e5b1d4f8a9c6e2b5d8f1a4c7e';
const PROBE_SONG_ID = 1330348068;

// Weapi
const W_NONCE = '0CoJUm6Qyw8W8jud';
const W_IV = '0102030405060708';
const W_PUBKEY = '010001';
const W_MODULUS =
  '00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4' +
  'ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d81' +
  '3cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7';
const W_CHARS = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';

// Eapi
const EAPI_KEY = Buffer.from('e82ckenh8dichen8', 'utf8');
const EAPI_SEP = '-36cd479b6b5-';

// 登录
const SMS_EAPI = 'https://music.163.com/eapi/sms/captcha/sent';
const LOGIN_EAPI = 'https://music.163.com/eapi/login/cellphone';
const SMS_WEAPI = 'https://music.163.com/weapi/sms/captcha/sent';
const LOGIN_WEAPI = 'https://music.163.com/weapi/login/cellphone';
const QR_UNIKEY_WEAPI = 'https://music.163.com/weapi/login/qrcode/unikey';
const QR_UNIKEY_API = 'https://music.163.com/api/login/qrcode/unikey';
const QR_LOGIN_WEAPI = 'https://music.163.com/weapi/login/qrcode/client/login';
const QR_LOGIN_API = 'https://music.163.com/api/login/qrcode/client/login';

// ===========================================================================
// Java 兼容小工具
// ===========================================================================

/** java.net.URLEncoder.encode(s, "UTF-8")：空格 -> +，字母数字与 . - * _ 不转义 */
function javaUrlEncode(value) {
  const bytes = Buffer.from(String(value), 'utf8');
  let out = '';
  for (let i = 0; i < bytes.length; i++) {
    const b = bytes[i];
    if ((b >= 0x30 && b <= 0x39) || (b >= 0x41 && b <= 0x5a) || (b >= 0x61 && b <= 0x7a) ||
        b === 0x2e || b === 0x2d || b === 0x2a || b === 0x5f) {
      out += String.fromCharCode(b);
    } else if (b === 0x20) {
      out += '+';
    } else {
      out += '%' + b.toString(16).toUpperCase().padStart(2, '0');
    }
  }
  return out;
}

/** Java String.trim()：只去掉 <= U+0020 的首尾字符 */
function javaTrim(value) {
  const s = String(value);
  let start = 0;
  let end = s.length;
  while (start < end && s.charCodeAt(start) <= 0x20) start++;
  while (end > start && s.charCodeAt(end - 1) <= 0x20) end--;
  return s.slice(start, end);
}

/**
 * org.json (Android libcore JSONStringer) 风格的序列化。
 * 键顺序 = 插入顺序；控制字符 / U+2028 / U+2029 转义。
 * eapi 的 md5 是对这个字符串算的，所以必须自己控制格式，不能依赖 JSON.stringify。
 */
function jstr(value) {
  if (value === null || value === undefined) return 'null';
  const t = typeof value;
  if (t === 'boolean') return value ? 'true' : 'false';
  if (t === 'number') {
    if (!Number.isFinite(value)) throw new Error('Forbidden numeric value: ' + value);
    return String(value);
  }
  if (t === 'string') return jquote(value);
  if (Array.isArray(value)) return '[' + value.map(jstr).join(',') + ']';
  if (t === 'object') {
    return '{' + Object.keys(value).map((k) => jquote(k) + ':' + jstr(value[k])).join(',') + '}';
  }
  return 'null';
}

function jquote(s) {
  let out = '"';
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    switch (c) {
      case 0x22: out += '\\"'; break;
      case 0x5c: out += '\\\\'; break;
      case 0x09: out += '\\t'; break;
      case 0x08: out += '\\b'; break;
      case 0x0a: out += '\\n'; break;
      case 0x0d: out += '\\r'; break;
      case 0x0c: out += '\\f'; break;
      case 0x2028:
      case 0x2029:
        out += '\\u' + c.toString(16).padStart(4, '0');
        break;
      default:
        if (c <= 0x1f) out += '\\u' + c.toString(16).padStart(4, '0');
        else out += s.charAt(i);
    }
  }
  return out + '"';
}

function opt(obj, key) {
  return obj && Object.prototype.hasOwnProperty.call(obj, key) ? obj[key] : null;
}
function has(obj, key) {
  const v = opt(obj, key);
  return v !== null && v !== undefined;
}
function optString(obj, key, fallback) {
  if (fallback === undefined) fallback = '';
  const v = opt(obj, key);
  if (v === null || v === undefined) return fallback;
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return String(v);
}
function optLong(obj, key, fallback) {
  if (fallback === undefined) fallback = 0;
  const v = opt(obj, key);
  if (typeof v === 'number') return Number.isFinite(v) ? Math.trunc(v) : fallback;
  if (typeof v === 'string') {
    const s = javaTrim(v);
    if (/^[+-]?\d+$/.test(s)) {
      const n = Number(s);
      return Number.isFinite(n) ? Math.trunc(n) : fallback;
    }
  }
  return fallback;
}
function optInt(obj, key, fallback) {
  if (fallback === undefined) fallback = 0;
  const n = optLong(obj, key, fallback);
  // Java intValue()：这里只用于很小的值，按 32 位截断
  return n | 0;
}
function optBoolean(obj, key, fallback) {
  if (fallback === undefined) fallback = false;
  const v = opt(obj, key);
  if (v === null || v === undefined) return fallback;
  if (typeof v === 'boolean') return v;
  if (typeof v === 'string') return javaTrim(v).toLowerCase() === 'true';
  return false;
}
/** 读取封面 pic id：大整数保持十进制字符串，避免 2^53 精度丢失；0/缺失返回 '' */
function optPicId(obj, key) {
  const v = opt(obj, key);
  if (typeof v === 'string') {
    const t = javaTrim(v);
    if (!/^[+]?\d+$/.test(t)) return '';
    const digits = t.replace(/^0+/, '');
    return digits === '' ? '' : t;
  }
  if (typeof v === 'number' && Number.isFinite(v) && v > 0) {
    return String(Math.trunc(v));
  }
  return '';
}
function optObject(obj, key) {
  const v = opt(obj, key);
  return v !== null && typeof v === 'object' && !Array.isArray(v) ? v : null;
}
function optArray(obj, key) {
  const v = opt(obj, key);
  return Array.isArray(v) ? v : null;
}

function previewText(s) {
  const one = String(s).replace(/\s+/g, ' ').trim();
  return one.length > 24 ? one.slice(0, 24) : one;
}

/** new JSONObject(text) 的近似错误语义 */
function parseJsonObject(text) {
  const s = String(text == null ? '' : text);
  if (s.length === 0) throw new Error('End of input');
  // 封面 ID（pic）可以到 1e17，超过 JS Number 的 2^53 精度。
  // 先把大整数 pic 值转成字符串，避免 optLong 舍入后生成错误的封面 URL。
  const prepared = s.replace(/("pic"\s*:\s*)(-?\d{15,})/g, '$1"$2"');
  let v;
  try {
    v = JSON.parse(prepared);
  } catch (e) {
    if (javaTrim(s).charAt(0) !== '{') {
      throw new Error('Value ' + previewText(s) + ' of type java.lang.String cannot be converted to JSONObject');
    }
    throw new Error(e.message);
  }
  if (v === null || typeof v !== 'object' || Array.isArray(v)) {
    throw new Error('Value ' + previewText(s) + ' of type java.lang.String cannot be converted to JSONObject');
  }
  return v;
}

/** Java optLong 语义：非法/缺省 -> 0 */
function toLong(value) {
  if (value === null || value === undefined) return 0;
  if (typeof value === 'number') return Number.isFinite(value) ? Math.trunc(value) : 0;
  const s = javaTrim(value);
  if (/^[+-]?\d+$/.test(s)) {
    const n = Number(s);
    return Number.isFinite(n) ? Math.trunc(n) : 0;
  }
  return 0;
}
function toInt(value, fallback) {
  if (fallback === undefined) fallback = 0;
  if (value === null || value === undefined) return fallback;
  if (typeof value === 'number') return Number.isFinite(value) ? (Math.trunc(value) | 0) : fallback;
  const s = javaTrim(value);
  if (/^[+-]?\d+$/.test(s)) {
    const n = Number(s);
    return Number.isFinite(n) ? (Math.trunc(n) | 0) : fallback;
  }
  return fallback;
}

// ===========================================================================
// HTTP（对应 Http.java / NeteaseLogin.post）
// ===========================================================================

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function joinSetCookie(values) {
  const out = [];
  for (const v of values || []) {
    const kv = String(v).split(';')[0].trim();
    if (kv) out.push(kv);
  }
  return out.join('; ');
}

/**
 * 单次请求（不重试）。HTTP >= 400 时仍然把响应体返回，这一点和 Java
 * Http.readAll() 一致（Java 只在拿不到流时才抛 IOException）。
 */
function requestOnce(urlStr, method, body, cookie, opts) {
  opts = opts || {};
  return new Promise((resolve, reject) => {
    let u;
    try {
      u = new URL(urlStr);
    } catch (e) {
      reject(new Error('非法 URL：' + urlStr));
      return;
    }
    const lib = u.protocol === 'http:' ? http : https;
    const headers = {
      'User-Agent': UA,
      'Referer': REFERER,
      'Accept': '*/*',
      'Accept-Encoding': 'gzip'
    };
    if (cookie !== null && cookie !== undefined && String(cookie).length > 0) {
      headers.Cookie = String(cookie);
    }
    if (opts.contentType) headers['Content-Type'] = opts.contentType;
    if (opts.range !== undefined && opts.range !== null) {
      headers.Range = 'bytes=0-' + (opts.range - 1);
    }
    const payload = body === null || body === undefined ? null : Buffer.from(String(body), 'utf8');
    if (payload !== null) headers['Content-Length'] = String(payload.length);

    let settled = false;
    let ended = false;
    const done = (fn, value) => {
      if (settled) return;
      settled = true;
      fn(value);
    };

    const req = lib.request({
      protocol: u.protocol,
      hostname: u.hostname,
      port: u.port || undefined,
      path: u.pathname + u.search,
      method: method,
      headers: headers
    }, (res) => {
      // 连接建立后换成读超时
      req.setTimeout(opts.readTimeout || READ_TIMEOUT, () => req.destroy(new Error('Read timed out')));

      const chunks = [];
      let total = 0;
      const finish = () => {
        if (ended) return;
        ended = true;
        let out = Buffer.concat(chunks);
        if (opts.maxBytes !== undefined && opts.maxBytes !== null && out.length > opts.maxBytes) {
          out = out.subarray(0, opts.maxBytes);
        }
        if (!opts.noGzip) {
          const enc = String(res.headers['content-encoding'] || '').toLowerCase();
          if (enc.indexOf('gzip') >= 0) {
            try {
              out = zlib.gunzipSync(out);
            } catch (e) {
              done(reject, new Error('gzip 解压失败：' + e.message));
              try { req.destroy(); } catch (ignore) {}
              return;
            }
          }
        }
        done(resolve, {
          status: res.statusCode,
          headers: res.headers || {},
          body: out.toString('utf8'),
          buffer: out,
          setCookie: (res.headers && res.headers['set-cookie']) || []
        });
        try { req.destroy(); } catch (ignore) {}
      };

      res.on('data', (chunk) => {
        if (settled) return;
        chunks.push(chunk);
        total += chunk.length;
        if (opts.maxBytes !== undefined && opts.maxBytes !== null && total >= opts.maxBytes) {
          // 和 Java getRange 一样：读够上限就主动断开，不拖整首
          try { res.destroy(); } catch (ignore) {}
          finish();
        }
      });
      res.on('end', finish);
      res.on('close', finish);
      res.on('error', (e) => done(reject, e));
    });

    req.setTimeout(opts.connectTimeout || CONNECT_TIMEOUT, () => req.destroy(new Error('connect timed out')));
    req.on('error', (e) => done(reject, e));
    if (payload !== null) req.write(payload);
    req.end();
  });
}

/** 跟随重定向（最多 20 次，和 HttpURLConnection 默认值一致） */
async function httpRequest(url, method, body, cookie, opts) {
  const options = Object.assign({}, opts);
  let current = url;
  let currentMethod = method;
  let currentBody = body;
  for (let i = 0; i < 20; i++) {
    const r = await requestOnce(current, currentMethod, currentBody, cookie, options);
    const location = r.headers.location || r.headers.Location;
    const redirect = r.status === 301 || r.status === 302 || r.status === 303 ||
      r.status === 307 || r.status === 308;
    if (redirect && location) {
      let next;
      try {
        next = new URL(location, current).toString();
      } catch (e) {
        return r;
      }
      if (r.status !== 307 && r.status !== 308) {
        currentMethod = 'GET';
        currentBody = null;
        options.contentType = null;
      }
      current = next;
      continue;
    }
    return r;
  }
  throw new Error('重定向次数过多');
}

/** Http.postForm：3 次重试，间隔 400/800/1200ms（和 Java 一样最后一次失败也 sleep） */
async function httpPostForm(url, body, cookie) {
  let lastErr = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const r = await httpRequest(url, 'POST', body, cookie, {
        contentType: 'application/x-www-form-urlencoded'
      });
      return r.body;
    } catch (e) {
      lastErr = e;
      await sleep(400 * (attempt + 1));
    }
  }
  throw lastErr || new Error('请求失败');
}

/** Http.get：3 次重试 */
async function httpGet(url, cookie) {
  let lastErr = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const r = await httpRequest(url, 'GET', null, cookie, {});
      return r.body;
    } catch (e) {
      lastErr = e;
      await sleep(400 * (attempt + 1));
    }
  }
  throw lastErr || new Error('请求失败');
}

/** Http.getRange：不重试，读够 maxSize 主动断开 */
async function httpGetRange(url, maxSize) {
  const r = await httpRequest(url, 'GET', null, null, {
    range: maxSize,
    maxBytes: maxSize,
    noGzip: true,
    readTimeout: 20000   // Http.getRange 的 readTimeout 是 20000
  });
  return r.buffer || Buffer.alloc(0);
}

/** NeteaseLogin.post：不重试，同时收集 Set-Cookie */
async function loginPost(url, body, cookie) {
  const r = await httpRequest(url, 'POST', body, cookie, {
    contentType: 'application/x-www-form-urlencoded'
  });
  return {
    body: r.body,
    cookie: joinSetCookie(r.setCookie),
    status: r.status,
    headers: r.headers
  };
}

// ===========================================================================
// Weapi（对应 Weapi.java）
// ===========================================================================

function aesCbcBase64(text, key) {
  const data = Buffer.from(text, 'utf8');
  const pad = 16 - (data.length % 16);
  const padded = Buffer.concat([data, Buffer.alloc(pad, pad)]);
  const cipher = crypto.createCipheriv(
    'aes-128-cbc',
    Buffer.from(key, 'utf8'),
    Buffer.from(W_IV, 'utf8')
  );
  cipher.setAutoPadding(false);
  const out = Buffer.concat([cipher.update(padded), cipher.final()]);
  return out.toString('base64');
}

function randomWeapiKey() {
  let out = '';
  for (let i = 0; i < 16; i++) out += W_CHARS.charAt(crypto.randomInt(W_CHARS.length));
  return out;
}

function modPow(base, exponent, modulus) {
  let result = 1n;
  let b = base % modulus;
  let e = exponent;
  while (e > 0n) {
    if (e & 1n) result = (result * b) % modulus;
    b = (b * b) % modulus;
    e >>= 1n;
  }
  return result;
}

function rsaEncrypt(text) {
  // Java: new StringBuilder(text).reverse().toString()
  const reversed = text.split('').reverse().join('');
  const modulus = BigInt('0x' + W_MODULUS);
  const exponent = BigInt('0x' + W_PUBKEY);
  // new BigInteger(1, reversed.getBytes(UTF_8))
  const msg = BigInt('0x' + Buffer.from(reversed, 'utf8').toString('hex'));
  let hex = modPow(msg, exponent, modulus).toString(16);
  while (hex.length < 256) hex = '0' + hex;
  return hex;
}

/**
 * params = base64(aes(aes(json, nonce), secretKey))
 * encSecKey = rsa(secretKey 反转)
 */
function weapiEncrypt(data) {
  const text = jstr(data);
  const secretKey = randomWeapiKey();
  const params = aesCbcBase64(aesCbcBase64(text, W_NONCE), secretKey);
  return {
    params: params,
    encSecKey: rsaEncrypt(secretKey),
    secretKey: secretKey,
    text: text
  };
}

// ===========================================================================
// Eapi（对应 Eapi.java）
// ===========================================================================

function md5Hex(text) {
  return crypto.createHash('md5').update(Buffer.from(text, 'utf8')).digest('hex');
}

/** 把 /eapi/ 换成 /api/ —— 摘要算法要求 */
function eapiPath(url) {
  let path;
  try {
    path = new URL(url).pathname;
  } catch (e) {
    const q = url.indexOf('://');
    const rest = q >= 0 ? url.slice(q + 3) : url;
    const slash = rest.indexOf('/');
    path = slash >= 0 ? rest.slice(slash) : '/';
  }
  const qm = path.indexOf('?');
  if (qm >= 0) path = path.slice(0, qm);
  return path.split('/eapi/').join('/api/');
}

function eapiEncrypt(url, jsonPayload) {
  const path = eapiPath(url);
  const digest = md5Hex('nobody' + path + 'use' + jsonPayload + 'md5forencrypt');
  const plain = path + EAPI_SEP + jsonPayload + EAPI_SEP + digest;
  const cipher = crypto.createCipheriv('aes-128-ecb', EAPI_KEY, null);
  return Buffer.concat([cipher.update(Buffer.from(plain, 'utf8')), cipher.final()]).toString('hex');
}

// ===========================================================================
// 二维码（只用于 qrCreate 返回 img；字节模式 + EC M + version 1~10）
// 已用 qrcode-generator 的矩阵逐模块比对通过。
// ===========================================================================

// [count, totalCodewords, dataCodewords]
const QR_RS_M = [
  null,
  [[1, 26, 16]],
  [[1, 44, 28]],
  [[1, 70, 44]],
  [[2, 50, 32]],
  [[2, 67, 43]],
  [[4, 43, 27]],
  [[4, 49, 31]],
  [[2, 60, 38], [2, 61, 39]],
  [[3, 58, 36], [2, 59, 37]],
  [[4, 69, 43], [1, 70, 44]]
];
const QR_ALIGN = [
  [], [], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34], [6, 22, 38], [6, 24, 42], [6, 26, 46], [6, 28, 50]
];
const QR_EXP = new Uint8Array(512);
const QR_LOG = new Uint8Array(256);
(function initQrGalois() {
  let x = 1;
  for (let i = 0; i < 255; i++) {
    QR_EXP[i] = x;
    QR_LOG[x] = i;
    x <<= 1;
    if (x & 0x100) x ^= 0x11d;
  }
  for (let i = 255; i < 512; i++) QR_EXP[i] = QR_EXP[i - 255];
})();
function qrMul(a, b) {
  return (a === 0 || b === 0) ? 0 : QR_EXP[QR_LOG[a] + QR_LOG[b]];
}
function qrGeneratorPoly(degree) {
  let poly = [1];
  for (let i = 0; i < degree; i++) {
    const next = new Array(poly.length + 1).fill(0);
    for (let j = 0; j < poly.length; j++) {
      next[j] ^= poly[j];
      next[j + 1] ^= qrMul(poly[j], QR_EXP[i]);
    }
    poly = next;
  }
  return poly;
}
function qrRsEncode(data, degree) {
  const gen = qrGeneratorPoly(degree);
  const rem = new Array(degree).fill(0);
  for (let k = 0; k < data.length; k++) {
    const factor = data[k] ^ rem[0];
    rem.shift();
    rem.push(0);
    for (let i = 0; i < degree; i++) rem[i] ^= qrMul(gen[i + 1], factor);
  }
  return rem;
}
function qrBitLength(n) {
  let c = 0;
  while (n) { c++; n >>>= 1; }
  return c;
}
function qrBch15(data) {
  let d = data << 10;
  while (qrBitLength(d) - qrBitLength(0x537) >= 0) {
    d ^= 0x537 << (qrBitLength(d) - qrBitLength(0x537));
  }
  return ((data << 10) | d) ^ 0x5412;
}
function qrBch18(version) {
  let d = version << 12;
  while (qrBitLength(d) - qrBitLength(0x1f25) >= 0) {
    d ^= 0x1f25 << (qrBitLength(d) - qrBitLength(0x1f25));
  }
  return (version << 12) | d;
}
function qrMask(mask, i, j) {
  switch (mask) {
    case 0: return (i + j) % 2 === 0;
    case 1: return i % 2 === 0;
    case 2: return j % 3 === 0;
    case 3: return (i + j) % 3 === 0;
    case 4: return (Math.floor(i / 2) + Math.floor(j / 3)) % 2 === 0;
    case 5: return (i * j) % 2 + (i * j) % 3 === 0;
    case 6: return ((i * j) % 2 + (i * j) % 3) % 2 === 0;
    case 7: return ((i * j) % 3 + (i + j) % 2) % 2 === 0;
    default: return false;
  }
}

function qrBuildCodewords(bytes, version) {
  const groups = QR_RS_M[version];
  const totalData = groups.reduce((n, g) => n + g[0] * g[2], 0);
  const bits = [];
  const put = (val, len) => {
    for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1);
  };
  put(4, 4); // byte mode
  put(bytes.length, version < 10 ? 8 : 16);
  for (const b of bytes) put(b, 8);
  const capacity = totalData * 8;
  for (let i = 0; i < 4 && bits.length < capacity; i++) bits.push(0);
  while (bits.length % 8 !== 0) bits.push(0);
  const data = [];
  for (let i = 0; i < bits.length; i += 8) {
    let b = 0;
    for (let j = 0; j < 8; j++) b = (b << 1) | bits[i + j];
    data.push(b);
  }
  const pads = [0xec, 0x11];
  let pi = 0;
  while (data.length < totalData) data.push(pads[pi++ % 2]);

  const blocks = [];
  let offset = 0;
  for (const g of groups) {
    for (let k = 0; k < g[0]; k++) {
      const blockData = data.slice(offset, offset + g[2]);
      offset += g[2];
      blocks.push({ data: blockData, ec: qrRsEncode(blockData, g[1] - g[2]) });
    }
  }
  const maxData = Math.max.apply(null, blocks.map((b) => b.data.length));
  const maxEc = Math.max.apply(null, blocks.map((b) => b.ec.length));
  const out = [];
  for (let i = 0; i < maxData; i++) {
    for (const b of blocks) if (i < b.data.length) out.push(b.data[i]);
  }
  for (let i = 0; i < maxEc; i++) {
    for (const b of blocks) if (i < b.ec.length) out.push(b.ec[i]);
  }
  return out;
}

function qrMatrix(text) {
  const bytes = Array.from(Buffer.from(String(text), 'utf8'));
  let version = 0;
  for (let v = 1; v <= 10; v++) {
    const totalData = QR_RS_M[v].reduce((n, g) => n + g[0] * g[2], 0);
    const need = 4 + (v < 10 ? 8 : 16) + bytes.length * 8;
    if (need <= totalData * 8) { version = v; break; }
  }
  if (!version) throw new Error('二维码内容过长');
  const codewords = qrBuildCodewords(bytes, version);
  const size = version * 4 + 17;

  function makeBase() {
    const m = [];
    for (let r = 0; r < size; r++) m.push(new Array(size).fill(null));
    const set = (r, c, v) => {
      if (r >= 0 && r < size && c >= 0 && c < size) m[r][c] = !!v;
    };
    const probe = (row, col) => {
      for (let r = -1; r <= 7; r++) {
        for (let c = -1; c <= 7; c++) {
          if (row + r < 0 || row + r >= size || col + c < 0 || col + c >= size) continue;
          const dark = (r >= 0 && r <= 6 && (c === 0 || c === 6)) ||
            (c >= 0 && c <= 6 && (r === 0 || r === 6)) ||
            (r >= 2 && r <= 4 && c >= 2 && c <= 4);
          set(row + r, col + c, dark);
        }
      }
    };
    probe(0, 0);
    probe(size - 7, 0);
    probe(0, size - 7);
    const pos = QR_ALIGN[version];
    for (let i = 0; i < pos.length; i++) {
      for (let j = 0; j < pos.length; j++) {
        const row = pos[i];
        const col = pos[j];
        if (m[row][col] !== null) continue;
        for (let r = -2; r <= 2; r++) {
          for (let c = -2; c <= 2; c++) {
            set(row + r, col + c,
              r === -2 || r === 2 || c === -2 || c === 2 || (r === 0 && c === 0));
          }
        }
      }
    }
    for (let r = 8; r < size - 8; r++) if (m[r][6] === null) m[r][6] = r % 2 === 0;
    for (let c = 8; c < size - 8; c++) if (m[6][c] === null) m[6][c] = c % 2 === 0;
    // 预留格式信息区（测试掩码时保持 false，与参考实现一致）
    for (let i = 0; i < 9; i++) {
      if (i === 6) continue;
      if (m[8][i] === null) m[8][i] = false;
      if (m[i][8] === null) m[i][8] = false;
    }
    for (let i = 0; i < 8; i++) {
      if (m[8][size - 1 - i] === null) m[8][size - 1 - i] = false;
      if (m[size - 1 - i][8] === null) m[size - 1 - i][8] = false;
    }
    if (m[8][8] === null) m[8][8] = false;
    // 预留版本信息区
    if (version >= 7) {
      for (let i = 0; i < 18; i++) {
        const r = Math.floor(i / 3);
        const c = i % 3 + size - 11;
        if (m[r][c] === null) m[r][c] = false;
        if (m[c][r] === null) m[c][r] = false;
      }
    }
    return m;
  }

  function placeFormat(m, mask) {
    const bits = qrBch15((0 << 3) | mask); // EC level M = 00
    for (let i = 0; i < 15; i++) {
      const dark = ((bits >> i) & 1) === 1;
      if (i < 6) m[i][8] = dark;
      else if (i < 8) m[i + 1][8] = dark;
      else m[size - 15 + i][8] = dark;

      if (i < 8) m[8][size - i - 1] = dark;
      else if (i < 9) m[8][15 - i - 1 + 1] = dark;
      else m[8][15 - i - 1] = dark;
    }
    m[size - 8][8] = true;
  }

  function placeVersion(m) {
    if (version < 7) return;
    const bits = qrBch18(version);
    for (let i = 0; i < 18; i++) {
      const dark = ((bits >> i) & 1) === 1;
      m[Math.floor(i / 3)][i % 3 + size - 8 - 3] = dark;
      m[i % 3 + size - 8 - 3][Math.floor(i / 3)] = dark;
    }
  }

  function mapData(m, mask) {
    let inc = -1;
    let row = size - 1;
    let bitIndex = 7;
    let byteIndex = 0;
    for (let col = size - 1; col > 0; col -= 2) {
      if (col === 6) col -= 1;
      for (;;) {
        for (let c = 0; c < 2; c++) {
          if (m[row][col - c] === null) {
            let dark = false;
            if (byteIndex < codewords.length) {
              dark = ((codewords[byteIndex] >>> bitIndex) & 1) === 1;
            }
            if (qrMask(mask, row, col - c)) dark = !dark;
            m[row][col - c] = dark;
            bitIndex--;
            if (bitIndex === -1) { byteIndex++; bitIndex = 7; }
          }
        }
        row += inc;
        if (row < 0 || row >= size) {
          row -= inc;
          inc = -inc;
          break;
        }
      }
    }
  }

  function penalty(m) {
    let score = 0;
    for (let row = 0; row < size; row++) {
      for (let col = 0; col < size; col++) {
        let same = 0;
        const dark = m[row][col];
        for (let r = -1; r <= 1; r++) {
          if (row + r < 0 || row + r >= size) continue;
          for (let c = -1; c <= 1; c++) {
            if (col + c < 0 || col + c >= size) continue;
            if (r === 0 && c === 0) continue;
            if (dark === m[row + r][col + c]) same++;
          }
        }
        if (same > 5) score += 3 + same - 5;
      }
    }
    for (let row = 0; row < size - 1; row++) {
      for (let col = 0; col < size - 1; col++) {
        let count = 0;
        if (m[row][col]) count++;
        if (m[row + 1][col]) count++;
        if (m[row][col + 1]) count++;
        if (m[row + 1][col + 1]) count++;
        if (count === 0 || count === 4) score += 3;
      }
    }
    for (let row = 0; row < size; row++) {
      for (let col = 0; col < size - 6; col++) {
        if (m[row][col] && !m[row][col + 1] && m[row][col + 2] && m[row][col + 3] &&
            m[row][col + 4] && !m[row][col + 5] && m[row][col + 6]) {
          score += 40;
        }
      }
    }
    for (let col = 0; col < size; col++) {
      for (let row = 0; row < size - 6; row++) {
        if (m[row][col] && !m[row + 1][col] && m[row + 2][col] && m[row + 3][col] &&
            m[row + 4][col] && !m[row + 5][col] && m[row + 6][col]) {
          score += 40;
        }
      }
    }
    let darkCount = 0;
    for (let r = 0; r < size; r++) {
      for (let c = 0; c < size; c++) if (m[r][c]) darkCount++;
    }
    score += (Math.abs(100 * darkCount / size / size - 50) / 5) * 10;
    return score;
  }

  let bestMask = 0;
  let minPenalty = Infinity;
  for (let mask = 0; mask < 8; mask++) {
    const test = makeBase();
    mapData(test, mask);
    const p = penalty(test);
    if (mask === 0 || p < minPenalty) {
      minPenalty = p;
      bestMask = mask;
    }
  }

  const modules = makeBase();
  placeFormat(modules, bestMask);
  placeVersion(modules);
  mapData(modules, bestMask);
  return { size: size, modules: modules, version: version, mask: bestMask };
}

const QR_CRC_TABLE = (function buildCrcTable() {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = QR_CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crc]);
}

/** 1-bit 灰度 PNG（scale 像素/模块，四周 4 模块静区） */
function qrPng(text, scale, quiet) {
  const qr = qrMatrix(text);
  scale = scale || 4;
  quiet = quiet === undefined ? 4 : quiet;
  const n = qr.size;
  const dim = (n + quiet * 2) * scale;
  const rowBytes = Math.ceil(dim / 8);
  const raw = Buffer.alloc((rowBytes + 1) * dim);
  for (let y = 0; y < dim; y++) {
    const off = y * (rowBytes + 1);
    raw[off] = 0;
    raw.fill(0xff, off + 1, off + 1 + rowBytes);
  }
  for (let r = 0; r < n; r++) {
    for (let c = 0; c < n; c++) {
      if (!qr.modules[r][c]) continue;
      const x0 = (quiet + c) * scale;
      const y0 = (quiet + r) * scale;
      for (let dy = 0; dy < scale; dy++) {
        for (let dx = 0; dx < scale; dx++) {
          const x = x0 + dx;
          const y = y0 + dy;
          raw[y * (rowBytes + 1) + 1 + (x >> 3)] &= ~(0x80 >> (x & 7));
        }
      }
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(dim, 0);
  ihdr.writeUInt32BE(dim, 4);
  ihdr[8] = 1;   // bit depth
  ihdr[9] = 0;   // grayscale
  ihdr[10] = 0;  // compression
  ihdr[11] = 0;  // filter
  ihdr[12] = 0;  // interlace
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0))
  ]);
}

function qrDataUrl(text, scale) {
  return 'data:image/png;base64,' + qrPng(text, scale).toString('base64');
}

// ===========================================================================
// 歌词辅助（对应 NeteaseApi 里的静态方法）
// ===========================================================================

const TIMESTAMP_RE = /^\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?(?:-\d+)?\]/;
const TIMESTAMP_FIND_RE = /\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?(?:-\d+)?\]/;
const WS = '[ \\t\\r\\f\\u000B]';
const META_CN_RE = new RegExp(
  '^(作词|作曲|编曲|制作人|混音|母带|和声|监制|出品|演唱|原唱|词|曲|吉他|贝斯|鼓|键盘|弦乐|录音|配唱|人声|后期|封面|设计)' +
  WS + '*[:：].*$'
);
const META_EN_RE = new RegExp(
  '^(Lyrics|Composed|Produced|Arranged|Written|Music|Mixed|Mastered|Performed)' + WS + '+by\\b.*$'
);
const META_BARE_RE = new RegExp('^(作词|作曲|编曲|制作人)' + WS + '*$');

function realLyricLines(lrc) {
  if (lrc === null || lrc === undefined || lrc === '') return 0;
  let count = 0;
  const lines = String(lrc).split('\n');
  for (let i = 0; i < lines.length; i++) {
    const t = javaTrim(lines[i].replace(TIMESTAMP_RE, ''));
    if (t === '') continue;
    if (META_CN_RE.test(t)) continue;
    if (META_EN_RE.test(t)) continue;
    if (META_BARE_RE.test(t)) continue;
    count++;
  }
  return count;
}

function hasTimestamp(lrc) {
  if (lrc === null || lrc === undefined || lrc === '') return false;
  return TIMESTAMP_FIND_RE.test(String(lrc));
}

function lyricLines(lrc) {
  const set = new Set();
  if (lrc === null || lrc === undefined) return set;
  const lines = String(lrc).split('\n');
  for (let i = 0; i < lines.length; i++) {
    const t = javaTrim(lines[i].replace(TIMESTAMP_RE, '')).toLowerCase();
    if (t.length >= 2) set.add(t);
  }
  return set;
}

function similarity(a, b) {
  const sa = lyricLines(a);
  const sb = lyricLines(b);
  if (sa.size === 0 || sb.size === 0) return 0;
  let inter = 0;
  for (const line of sa) if (sb.has(line)) inter++;
  const union = sa.size + sb.size - inter;
  return union === 0 ? 0 : inter / union;
}

function pad2(n) {
  return (n >= 0 && n < 10 ? '0' : '') + String(n);
}

/** 逐字歌词 -> 标准 LRC */
function yrcToLrc(src) {
  if (src === null || src === undefined || src === '') return '';
  let out = '';
  const lines = String(src).split('\n');
  for (let li = 0; li < lines.length; li++) {
    const line = javaTrim(lines[li]);
    if (line === '' || line.charAt(0) !== '[') continue;
    const close = line.indexOf(']');
    if (close <= 1) continue;
    const head = line.slice(1, close);
    const comma = head.indexOf(',');
    if (comma <= 0) continue;
    const startText = javaTrim(head.slice(0, comma));
    if (!/^[+-]?\d+$/.test(startText)) continue;
    const start = Number(startText);
    const body = line.slice(close + 1);
    let text = '';
    let i = 0;
    while (i < body.length) {
      const c = body.charAt(i);
      if (c === '(') {
        const end = body.indexOf(')', i);
        if (end < 0) break;
        i = end + 1;
      } else {
        text += c;
        i++;
      }
    }
    const t = javaTrim(text);
    if (t === '') continue;
    const sec = Math.trunc(start / 1000);
    const ms = start % 1000;
    out += '[' + pad2(Math.trunc(sec / 60)) + ':' + pad2(sec % 60) + '.' +
      pad2(Math.trunc(ms / 10)) + ']' + t + '\n';
  }
  return out;
}

function lyricText(root, key) {
  const o = optObject(root, key);
  return o === null ? '' : optString(o, 'lyric', '');
}

// ===========================================================================
// 图片封面 URL（对应 NeteaseApi.picUrl / encryptPicId）
// ===========================================================================

const PIC_MAGIC = Buffer.from('3go8&$8*3*3h0k(2)2', 'utf8');

function encryptPicId(id) {
  const idStr = String(id);
  const xored = Buffer.alloc(idStr.length);
  for (let i = 0; i < idStr.length; i++) {
    xored[i] = idStr.charCodeAt(i) ^ PIC_MAGIC[i % PIC_MAGIC.length];
  }
  const md5 = crypto.createHash('md5').update(xored).digest();
  return md5.toString('base64').split('/').join('_').split('+').join('-');
}

function picUrl(picId, size) {
  const encId = encryptPicId(picId);
  return 'https://p3.music.126.net/' + encId + '/' + picId + '.jpg?param=' + size + 'y' + size;
}

// ===========================================================================
// 歌曲精简 / 链接解析
// ===========================================================================

function levelName(key) {
  for (let i = 0; i < LEVEL_KEYS.length; i++) {
    if (LEVEL_KEYS[i] === key) return LEVEL_NAMES[i];
  }
  return key;
}

/** 把原始歌曲对象精简成前端需要的字段（字段名/顺序照 Java brief） */
function brief(song) {
  const o = {};
  try {
    o.id = optLong(song, 'id');
    o.name = optString(song, 'name');

    let ars = optArray(song, 'ar');
    if (ars === null) ars = optArray(song, 'artists');
    let artists = '';
    if (ars !== null) {
      for (let i = 0; i < ars.length; i++) {
        if (i > 0) artists += '/';
        artists += optString(ars[i], 'name');
      }
    }
    o.artists = artists;

    let al = optObject(song, 'al');
    if (al === null) al = optObject(song, 'album');
    if (al !== null) {
      o.album = optString(al, 'name');
      let pic = optString(al, 'picUrl', '');
      if (pic === '') {
        const picId = optPicId(al, 'pic');
        if (picId !== '') pic = picUrl(picId, 300);
      }
      o.picUrl = pic;
    } else {
      o.album = '';
      o.picUrl = '';
    }

    const dt = optLong(song, 'dt', optLong(song, 'duration', 0));
    o.duration = dt;
    o.fee = optInt(song, 'fee', 0);

    const privilege = optObject(song, 'privilege');
    if (privilege !== null) {
      o.plLevel = optString(privilege, 'plLevel', '');
      o.maxbr = optInt(privilege, 'maxbr', 0);
      o.dlLevel = optString(privilege, 'dlLevel', '');
      if (has(privilege, 'fl')) o.br = optInt(privilege, 'fl', 0);
    }
  } catch (e) {
    // Java 里也是 catch (Exception ignored)
  }
  return o;
}

function parseLink(input) {
  if (input === null || input === undefined) return null;
  const s = javaTrim(String(input));
  if (s === '') return null;
  const lower = s.toLowerCase();

  let type = null;
  if (lower.indexOf('/song') >= 0 || lower.indexOf('song?id') >= 0 ||
      lower.indexOf('music.163.com/song') >= 0) {
    type = 'song';
  } else if (lower.indexOf('playlist') >= 0 || lower.indexOf('歌单') >= 0) {
    type = 'playlist';
  } else if (lower.indexOf('album') >= 0 || lower.indexOf('专辑') >= 0) {
    type = 'album';
  }

  let found = 0;
  const re = /(?:id=|\/)(\d{4,})/g;
  let m;
  while ((m = re.exec(lower)) !== null) {
    const v = Number(m[1]);
    if (v > 999) { found = v; break; }
  }
  if (found === 0) {
    const m2 = /(\d{4,})/.exec(lower);
    if (m2) found = Number(m2[1]);
  }
  if (found === 0) return null;
  return { type: type === null ? 'song' : type, id: found };
}

// ===========================================================================
// NeteaseApi
// ===========================================================================

class NeteaseApi {
  constructor(cookie) {
    this.cookie = cookie === null || cookie === undefined ? '' : String(cookie);
  }

  setCookie(cookie) {
    this.cookie = cookie === null || cookie === undefined ? '' : String(cookie);
    return this;
  }

  // ------------------------------------------------------------- 静态工具

  static levelName(key) {
    return levelName(key);
  }

  levelName(key) {
    return levelName(key);
  }

  static picUrl(picId, size) {
    return picUrl(picId, size);
  }

  picUrl(picId, size) {
    return picUrl(picId, size);
  }

  static parseLink(text) {
    return parseLink(text);
  }

  parseLink(text) {
    return parseLink(text);
  }

  /** ApiBridge 的 levels 路由 */
  levels() {
    return LEVEL_KEYS.map((key, i) => ({ key: key, name: LEVEL_NAMES[i] }));
  }

  /** ApiBridge 的 parse 路由：解析失败时抛错 */
  parse(text) {
    const link = parseLink(text);
    if (link === null) throw new Error('没有识别出链接里的歌曲/歌单/专辑 ID');
    return { type: link.type, id: link.id };
  }

  // ------------------------------------------------------------------ 搜索

  /** 搜索歌曲，返回精简后的数组 */
  async search(keyword, limit) {
    const kw = keyword === null || keyword === undefined ? '' : String(keyword);
    const lim = limit === null || limit === undefined ? 100 : toInt(limit, 100);
    const body = 's=' + javaUrlEncode(kw) + '&type=1&limit=' + lim + '&offset=0';
    const resp = await httpPostForm(SEARCH_API, body, this.cookie);
    const root = parseJsonObject(resp);
    const code = optInt(root, 'code', -1);
    if (code !== 200) {
      throw new Error('搜索失败：' + optString(root, 'message', 'code=' + code));
    }
    const result = optObject(root, 'result');
    const songs = result === null ? null : optArray(result, 'songs');
    const out = [];
    if (songs !== null) {
      for (let i = 0; i < songs.length; i++) out.push(brief(songs[i]));
    }
    return out;
  }

  // ------------------------------------------------------------------ 详情

  async songDetail(id) {
    const sid = toLong(id);
    const body = 'c=' + javaUrlEncode('[{"id":' + sid + '}]');
    const resp = await httpPostForm(SONG_DETAIL_V3, body, this.cookie);
    const root = parseJsonObject(resp);
    const songs = optArray(root, 'songs');
    if (songs === null || songs.length === 0) {
      throw new Error('没有找到这首歌（ID: ' + sid + '）');
    }
    return brief(songs[0]);
  }

  /** 批量取歌曲详情（每批 100 条） */
  async songDetailBatch(ids) {
    const out = [];
    const list = Array.isArray(ids) ? ids.map((v) => toLong(v)) : [];
    if (list.length === 0) return out;
    for (let i = 0; i < list.length; i += 100) {
      const batch = list.slice(i, i + 100);
      const pieces = ['['];
      for (let k = 0; k < batch.length; k++) {
        if (k > 0) pieces.push(',');
        pieces.push('{"id":' + batch[k] + '}');
      }
      pieces.push(']');
      const resp = await httpPostForm(SONG_DETAIL_V3, 'c=' + javaUrlEncode(pieces.join('')), this.cookie);
      const root = parseJsonObject(resp);
      const songs = optArray(root, 'songs');
      if (songs === null) continue;
      const map = new Map();
      for (let k = 0; k < songs.length; k++) {
        const s = songs[k];
        map.set(optLong(s, 'id'), brief(s));
      }
      // 保持歌单原始顺序
      for (let k = 0; k < batch.length; k++) {
        const s = map.get(batch[k]);
        if (s !== undefined) out.push(s);
      }
    }
    return out;
  }

  // ------------------------------------------------------------ 播放地址

  /** 给 Cookie 补上设备字段；用户自己填了 deviceId 就不覆盖 */
  _audioCookie() {
    const cookie = this.cookie;
    if (cookie === null || cookie === undefined || javaTrim(cookie) === '') return cookie;
    if (cookie.indexOf('deviceId=') >= 0) return cookie;
    return javaTrim(cookie) + '; os=android; appver=' + APP_VER +
      '; osver=' + OS_VER + '; deviceId=' + DEVICE_ID;
  }

  /** 原始请求：指定音质取播放地址 */
  async _rawSongUrl(id, level) {
    const payload = {};
    payload.ids = [id];
    payload.level = level;
    payload.encodeType = level === 'dolby' ? 'mp4' : 'flac';
    const header = {
      os: 'android',
      appver: APP_VER,
      osver: OS_VER,
      deviceId: DEVICE_ID,
      requestId: String(Date.now())
    };
    payload.header = jstr(header);
    if (level === 'sky') payload.immerseType = 'c51';

    const params = eapiEncrypt(SONG_URL_V1, jstr(payload));
    const resp = await httpPostForm(SONG_URL_V1, 'params=' + javaUrlEncode(params), this._audioCookie());
    const root = parseJsonObject(resp);
    if (optInt(root, 'code', -1) !== 200) {
      throw new Error('解析失败：' + optString(root, 'message', 'code=' + optInt(root, 'code', -1)));
    }
    const data = optArray(root, 'data');
    if (data === null || data.length === 0) throw new Error('接口未返回数据');
    return data[0];
  }

  /** 取播放地址，失败时按音质链自动降级 */
  async songUrl(id, level) {
    const sid = toLong(id);
    const requested = level === null || level === undefined ? 'exhigh' : String(level);

    const chain = [requested];
    let found = false;
    for (const lv of LEVEL_CHAIN) {
      if (lv === requested) { found = true; continue; }
      if (found) chain.push(lv);
    }
    if (!found) {
      for (const lv of LEVEL_CHAIN) {
        if (chain.indexOf(lv) < 0) chain.push(lv);
      }
    }

    let last = null;
    let lastErr = null;
    for (const lv of chain) {
      let d;
      try {
        d = await this._rawSongUrl(sid, lv);
      } catch (e) {
        lastErr = e.message;
        continue;
      }
      last = d;
      const url = optString(d, 'url', '');
      if (url !== '' && url !== 'null') {
        // 响应里的 level 才是真实等级（服务端会按账号权益静默回落）
        let actual = javaTrim(optString(d, 'level', ''));
        if (actual === '' || actual === 'none') actual = lv;
        const o = {};
        o.id = sid;
        // CDN 同时支持 https，优先走 https
        o.url = url.indexOf('http://') === 0 ? 'https://' + url.slice(7) : url;
        o.urlHttp = url;
        o.br = optInt(d, 'br', 0);
        o.sr = optInt(d, 'sr', 0);
        o.size = optLong(d, 'size', 0);
        o.type = optString(d, 'type', 'mp3');
        o.md5 = optString(d, 'md5', '');
        o.requested = requested;
        o.requestedName = levelName(requested);
        o.actual = actual;
        o.levelName = levelName(actual);
        o.degraded = actual !== requested;
        o.fee = optInt(d, 'fee', 0);
        return o;
      }
      const code = optInt(d, 'code', 0);
      if (code === 200 && (url === '' || url === 'null')) {
        lastErr = '当前账号无此音质权限或该歌曲无版权';
      }
    }
    if (last !== null) {
      const code = optInt(last, 'code', -1);
      if (code !== 200) throw new Error('解析失败（code=' + code + '）');
    }
    throw new Error(lastErr !== null ? lastErr : '无法获取播放地址');
  }

  /**
   * 批量探测每首歌当前账号能拿到的最高音质（按「超清母带」请求，服务端回落）。
   * 一次最多 50 首，返回原始 data 数组。
   */
  async probeLevels(ids) {
    const out = [];
    let list = [];
    if (Array.isArray(ids)) list = ids.map((v) => toLong(v));
    else if (ids !== null && ids !== undefined && typeof ids.length === 'number') {
      list = Array.prototype.map.call(ids, (v) => toLong(v));
    }
    if (list.length === 0) return out;

    for (let start = 0; start < list.length; start += 50) {
      const batch = list.slice(start, start + 50);
      const payload = {};
      payload.ids = batch;
      payload.level = 'jymaster';
      payload.encodeType = 'flac';
      const header = {
        os: 'android',
        appver: APP_VER,
        osver: OS_VER,
        deviceId: DEVICE_ID,
        requestId: String(Date.now())
      };
      payload.header = jstr(header);

      const params = eapiEncrypt(SONG_URL_V1, jstr(payload));
      const resp = await httpPostForm(SONG_URL_V1, 'params=' + javaUrlEncode(params), this._audioCookie());
      try {
        const root = parseJsonObject(resp);
        const data = optArray(root, 'data');
        if (data !== null) {
          for (let i = 0; i < data.length; i++) out.push(data[i]);
        }
      } catch (e) {
        // 单批失败不影响整体
      }
    }
    return out;
  }

  // ------------------------------------------------------------ 音频头

  /** 读音频文件头，解析真实的采样率 / 声道数 / 位深 */
  async audioHeader(url) {
    const o = {};
    if (url === null || url === undefined || url === '') return o;
    const head = await httpGetRange(String(url), 131072);
    if (head === null || head.length < 4) return o;

    // ---------- FLAC ----------
    if (head[0] === 0x66 && head[1] === 0x4c && head[2] === 0x61 && head[3] === 0x43) {
      o.format = 'FLAC';
      let pos = 4;
      while (pos + 4 <= head.length) {
        const type = head[pos] & 0x7f;
        const len = ((head[pos + 1] & 0xff) << 16) | ((head[pos + 2] & 0xff) << 8) | (head[pos + 3] & 0xff);
        const d = pos + 4;
        if (type === 0 && d + 18 <= head.length) { // STREAMINFO
          let b = 0n;
          for (let i = 0; i < 8; i++) b = (b << 8n) | BigInt(head[d + 10 + i]);
          const sampleRate = Number((b >> 44n) & 0xfffffn);
          const channels = Number((b >> 41n) & 0x7n) + 1;
          const bits = Number((b >> 36n) & 0x1fn) + 1;
          const totalSamples = Number(b & 0xfffffffffn);
          if (sampleRate > 0) {
            o.sampleRate = sampleRate;
            o.duration = Math.floor(totalSamples * 1000 / sampleRate);
          }
          o.channels = channels;
          o.bits = bits;
          break;
        }
        pos = d + len;
        if (type === 0 || pos <= d) break;
      }
      return o;
    }

    // ---------- MP3 ----------
    const limit = Math.min(head.length, 8192);
    for (let i = 0; i + 4 <= limit; i++) {
      if ((head[i] & 0xff) !== 0xff || (head[i + 1] & 0xe0) !== 0xe0) continue;
      const h1 = head[i + 1] & 0xff;
      const h2 = head[i + 2] & 0xff;
      const h3 = head[i + 3] & 0xff;
      const verBits = (h1 >> 3) & 0x3;
      const layerBits = (h1 >> 1) & 0x3;
      if (verBits === 1 || layerBits === 0) continue;
      const brIdx = (h2 >> 4) & 0xf;
      const srIdx = (h2 >> 2) & 0x3;
      if (brIdx === 0 || brIdx === 15 || srIdx === 3) continue;
      const sr1 = [44100, 48000, 32000];
      const sr2 = [22050, 24000, 16000];
      const sr25 = [11025, 12000, 8000];
      const sr = verBits === 3 ? sr1[srIdx] : (verBits === 2 ? sr2[srIdx] : sr25[srIdx]);
      const br1 = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0];
      const br2 = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0];
      const br = (verBits === 3 ? br1[brIdx] : br2[brIdx]) * 1000;
      const chMode = (h3 >> 6) & 0x3;
      o.format = 'MP3';
      o.sampleRate = sr;
      o.channels = chMode === 3 ? 1 : 2;
      o.bits = 16;
      if (br > 0) o.bitrate = br;
      break;
    }
    return o;
  }

  // ------------------------------------------------------------------ 歌词

  async _requestLyric(id, lv) {
    const body = 'id=' + id + '&lv=' + lv + '&kv=1&tv=-1&rv=1';
    return parseJsonObject(await httpPostForm(LYRIC_API, body, this.cookie));
  }

  async _requestLyricFull(id) {
    const body = 'id=' + id + '&lv=-1&kv=-1&tv=-1&rv=-1&yv=1&ytv=-1&yrv=-1';
    return parseJsonObject(await httpPostForm(LYRIC_API, body, this.cookie));
  }

  async lyric(id) {
    const sid = toLong(id);
    // lv=-1 先取服务端默认版本，空再退回 lv=1
    let root = await this._requestLyric(sid, -1);
    if (lyricText(root, 'lrc') === '') {
      root = await this._requestLyric(sid, 1);
    }
    let lrc = lyricText(root, 'lrc');
    let tlyric = lyricText(root, 'tlyric');
    const romalrc = lyricText(root, 'romalrc');
    let yrcRaw = lyricText(root, 'yrc');
    const noRealLyric = realLyricLines(lrc) === 0;
    if (yrcRaw === '' && (lrc === '' || noRealLyric)) {
      const full = await this._requestLyricFull(sid);
      yrcRaw = lyricText(full, 'yrc');
      if (yrcRaw === '') yrcRaw = lyricText(full, 'klyric');
      if ((lrc === '' || noRealLyric) && yrcRaw !== '') {
        const converted = yrcToLrc(yrcRaw);
        if (realLyricLines(converted) > 0) lrc = converted;
      }
      if (tlyric === '') tlyric = lyricText(full, 'tlyric');
    }
    // 没有正文 或 纯文本（无时间轴）：借用同名/相似歌曲的带轴歌词
    if (realLyricLines(lrc) === 0 || !hasTimestamp(lrc)) {
      const alt = await this._similarLyric(sid, lrc);
      if (alt !== null && realLyricLines(alt) > 0) lrc = alt;
    }
    // 极端情况：只有翻译歌词，用翻译兜底
    if (lrc === '' && tlyric !== '') lrc = tlyric;
    const pure = optBoolean(root, 'pureMusic', false) || optBoolean(root, 'nolyric', false);
    return {
      pure: pure,
      lyric: lrc,
      yrc: yrcRaw,
      tlyric: tlyric,
      romalrc: romalrc
    };
  }

  async _similarLyric(id, plain) {
    try {
      const detail = await this.songDetail(id);
      const name = optString(detail, 'name', '');
      if (name === '') return null;
      const arr = await this.search(name, 4);
      let best = null;
      let bestScore = 0;
      for (let i = 0; i < arr.length; i++) {
        const s = arr[i];
        const sid = optLong(s, 'id', 0);
        if (sid === 0 || sid === id) continue;
        try {
          const l = lyricText(await this._requestLyric(sid, -1), 'lrc');
          if (!hasTimestamp(l)) continue;
          const score = similarity(plain, l);
          if (score > bestScore) {
            bestScore = score;
            best = l;
          }
        } catch (e) {
          // 单首失败忽略
        }
      }
      return best !== null && bestScore >= 0.55 ? best : null;
    } catch (e) {
      return null;
    }
  }

  // ------------------------------------------------------------------ 歌单

  /** 只取歌单前几首 */
  async playlistTop(id, limit) {
    const lim = limit === null || limit === undefined ? 3 : toInt(limit, 3);
    const body = 'id=' + toLong(id) + '&n=' + Math.max(lim, 5);
    const resp = await httpPostForm(PLAYLIST_DETAIL_API, body, this.cookie);
    const root = parseJsonObject(resp);
    const out = [];
    const pl = optObject(root, 'playlist');
    if (pl === null) return out;
    const tracks = optArray(pl, 'tracks');
    if (tracks === null) return out;
    for (let i = 0; i < tracks.length && i < lim; i++) {
      out.push(brief(tracks[i]));
    }
    return out;
  }

  /** ApiBridge 的路由名 */
  async toplist(id, limit) {
    return this.playlistTop(id, limit);
  }

  async playlist(id) {
    const body = 'id=' + toLong(id) + '&n=1000&s=8';
    const resp = await httpPostForm(PLAYLIST_DETAIL_API, body, this.cookie);
    const root = parseJsonObject(resp);
    if (optInt(root, 'code', -1) !== 200) {
      throw new Error('歌单获取失败：code=' + optInt(root, 'code', -1));
    }
    const pl = optObject(root, 'playlist');
    if (pl === null) throw new Error('歌单不存在');

    const out = {};
    out.id = optLong(pl, 'id');
    out.name = optString(pl, 'name');
    out.coverImgUrl = optString(pl, 'coverImgUrl', '');
    const creator = optObject(pl, 'creator');
    out.creator = creator === null ? '' : optString(creator, 'nickname', '');
    out.trackCount = optInt(pl, 'trackCount', 0);
    out.description = optString(pl, 'description', '');
    out.playCount = optLong(pl, 'playCount', 0);

    const ids = [];
    const tids = optArray(pl, 'trackIds');
    if (tids !== null) {
      for (let i = 0; i < tids.length; i++) ids.push(optLong(tids[i], 'id'));
    }
    const tracks = optArray(pl, 'tracks');
    if (ids.length === 0 && tracks !== null) {
      const arr = [];
      for (let i = 0; i < tracks.length; i++) arr.push(brief(tracks[i]));
      out.tracks = arr;
      return out;
    }
    out.tracks = await this.songDetailBatch(ids);
    return out;
  }

  // ------------------------------------------------------------------ 专辑

  async album(id) {
    const resp = await httpGet(ALBUM_DETAIL_API + toLong(id), this.cookie);
    const root = parseJsonObject(resp);
    if (optInt(root, 'code', -1) !== 200) {
      throw new Error('专辑获取失败：code=' + optInt(root, 'code', -1));
    }
    const al = optObject(root, 'album');
    if (al === null) throw new Error('专辑不存在');

    const out = {};
    out.id = optLong(al, 'id');
    out.name = optString(al, 'name');
    const coverPicId = optPicId(al, 'pic');
    out.coverImgUrl = optString(al, 'picUrl', picUrl(coverPicId === '' ? '0' : coverPicId, 300));
    const artist = optObject(al, 'artist');
    out.artist = artist === null ? '' : optString(artist, 'name', '');
    out.description = optString(al, 'description', '');
    out.size = optInt(al, 'size', 0);
    const songs = optArray(root, 'songs');
    const arr = [];
    if (songs !== null) {
      for (let i = 0; i < songs.length; i++) arr.push(brief(songs[i]));
    }
    out.tracks = arr;
    return out;
  }

  // ------------------------------------------------------------------ 账号

  /**
   * 校验 Cookie 是否有效，并返回账号信息（昵称 / VIP 类型 / 能解析到的最高音质）。
   * 对应 ApiBridge.testCookie。
   */
  async testCookie(cookie) {
    const ck = cookie === null || cookie === undefined ? this.cookie : String(cookie);
    const resp = await httpPostForm(ACCOUNT_API, '', ck);
    const root = parseJsonObject(resp);
    const out = {};
    const profile = optObject(root, 'profile');
    if (profile === null) {
      out.valid = false;
      out.message = 'Cookie 无效或已过期（未取到账号信息）';
      return out;
    }
    out.valid = true;
    out.nickname = optString(profile, 'nickname', '');
    out.userId = optLong(profile, 'userId', 0);
    const vipType = optInt(profile, 'vipType', 0);
    out.vipType = vipType;
    let vipName;
    if (vipType === 0) vipName = '普通账号';
    else if (vipType === 11) vipName = '黑胶 VIP';
    else if (vipType === 10) vipName = '音乐包';
    else vipName = '会员类型 ' + vipType;
    out.vipName = vipName;

    try {
      const probe = await new NeteaseApi(ck).songUrl(PROBE_SONG_ID, 'hires');
      const maxLv = optString(probe, 'actual', '');
      out.maxLevel = maxLv;
      out.maxLevelName = optString(probe, 'levelName', maxLv);
    } catch (e) {
      out.maxLevel = '';
    }

    let msg = 'Cookie 有效，当前账号：' + out.nickname + '（' + vipName + '）';
    const ml = optString(out, 'maxLevelName', '');
    if (ml !== '') {
      msg += '\n该账号最高可解析：' + ml;
      const lv = optString(out, 'maxLevel', '');
      if (lv === 'standard' || lv === 'exhigh') {
        msg += '\n（无损及以上需要黑胶 VIP，把会员账号的 Cookie 填进来即可）';
      }
    }
    out.message = msg;
    return out;
  }

  // ------------------------------------------------------------------ 登录

  _headerJson() {
    return jstr({
      os: 'pc',
      appver: '8.9.75',
      osver: '',
      deviceId: 'pyncm!',
      requestId: String(Date.now())
    });
  }

  _qrResult(raw) {
    const root = parseJsonObject(raw);
    const key = optString(root, 'unikey', '');
    if (key === '') {
      const msg = optString(root, 'message', '');
      throw new Error(msg !== '' ? '获取二维码失败：' + msg
        : '获取二维码失败：' + previewText(raw));
    }
    const content = 'https://music.163.com/login?codekey=' + key;
    let img = '';
    try {
      img = qrDataUrl(content, 4);
    } catch (e) {
      img = '';
    }
    return {
      key: key,
      img: img,
      unikey: key,
      code: optInt(root, 'code', 0),
      qrContent: content,
      raw: raw
    };
  }

  /** 生成扫码登录的 unikey；weapi 失败时回退到 api 明文接口 */
  async qrCreate() {
    let raw = '';
    try {
      const enc = weapiEncrypt({ type: 1 });
      raw = (await loginPost(
        QR_UNIKEY_WEAPI,
        'params=' + javaUrlEncode(enc.params) + '&encSecKey=' + javaUrlEncode(enc.encSecKey),
        ''
      )).body;
      if (raw && raw.indexOf('unikey') >= 0) return this._qrResult(raw);
    } catch (e) {
      // 忽略，走明文接口
    }
    try {
      raw = (await loginPost(QR_UNIKEY_API, 'type=1', '')).body;
      if (raw && raw.indexOf('unikey') >= 0) return this._qrResult(raw);
    } catch (e) {
      // 最后一次调用让错误抛出去
    }
    raw = (await loginPost(QR_UNIKEY_API, 'type=1', '')).body;
    return this._qrResult(raw);
  }

  /** 轮询扫码状态；success(803) 时带 cookie */
  async qrCheck(key) {
    const k = key === null || key === undefined ? '' : javaTrim(String(key));
    let res = null;
    try {
      const enc = weapiEncrypt({ key: k, type: 1 });
      res = await loginPost(
        QR_LOGIN_WEAPI,
        'params=' + javaUrlEncode(enc.params) + '&encSecKey=' + javaUrlEncode(enc.encSecKey),
        ''
      );
    } catch (e) {
      res = null;
    }
    let root = null;
    if (res !== null && res.body !== '') {
      try { root = parseJsonObject(res.body); } catch (e) { root = null; }
    }
    if (root === null) {
      // weapi 返回空 body / 非 JSON（网关异常或风控）时回退到明文 api 通道，
      // 否则扫码登录会永远停在“等待扫码”。
      try {
        const r2 = await loginPost(
          QR_LOGIN_API + '?key=' + javaUrlEncode(k) + '&type=1',
          '',
          ''
        );
        res = r2;
        try { root = parseJsonObject(r2.body); } catch (e) { root = null; }
      } catch (e) {
        // 保留 weapi 的空响应
      }
    }
    const body = res === null ? '' : res.body;
    return {
      code: root === null ? 0 : optInt(root, 'code', 0),
      message: root === null ? (body ? previewText(body) : '') : optString(root, 'message', ''),
      cookie: res === null ? '' : res.cookie,
      body: body
    };
  }

  /** 发送短信验证码（eapi 优先，失败回退 weapi）；返回解析后的响应对象 */
  async sendLoginCode(phone, ctcode) {
    const p = phone === null || phone === undefined ? '' : javaTrim(String(phone));
    if (p === '') throw new Error('请先填写手机号');
    const ct = ctcode === null || ctcode === undefined || String(ctcode) === ''
      ? '86' : String(ctcode);

    try {
      const data = { cellphone: p, ctcode: ct, header: this._headerJson() };
      const params = eapiEncrypt(SMS_EAPI, jstr(data));
      const r = await loginPost(SMS_EAPI, 'params=' + javaUrlEncode(params), '');
      return parseBodyOrRaw(r.body);
    } catch (e) {
      // 回退 weapi
      const w = { cellphone: p, ctcode: ct };
      const enc = weapiEncrypt(w);
      const r = await loginPost(
        SMS_WEAPI,
        'params=' + javaUrlEncode(enc.params) + '&encSecKey=' + javaUrlEncode(enc.encSecKey),
        ''
      );
      return parseBodyOrRaw(r.body);
    }
  }

  /** 用验证码登录，返回 {body, cookie, code?, message?}；eapi 失败回退 weapi */
  async loginByCode(phone, code, ctcode) {
    const p = phone === null || phone === undefined ? '' : javaTrim(String(phone));
    const c = code === null || code === undefined ? '' : javaTrim(String(code));
    if (p === '') throw new Error('请先填写手机号');
    if (c === '') throw new Error('请先填写验证码');
    const ct = ctcode === null || ctcode === undefined || String(ctcode) === ''
      ? '86' : String(ctcode);

    try {
      const data = {
        phone: p,
        countrycode: ct,
        captcha: c,
        rememberLogin: 'true',
        header: this._headerJson()
      };
      const params = eapiEncrypt(LOGIN_EAPI, jstr(data));
      const r = await loginPost(LOGIN_EAPI, 'params=' + javaUrlEncode(params), '');
      if (r.cookie && r.cookie.indexOf('MUSIC_U=') >= 0) return loginResult(r);
      // eapi 没拿到 cookie，再试 weapi
    } catch (e) {
      // 回退 weapi
    }

    const w = {
      phone: p,
      countrycode: ct,
      captcha: c,
      rememberLogin: 'true'
    };
    const enc = weapiEncrypt(w);
    const r = await loginPost(
      LOGIN_WEAPI,
      'params=' + javaUrlEncode(enc.params) + '&encSecKey=' + javaUrlEncode(enc.encSecKey),
      ''
    );
    return loginResult(r);
  }
}

function parseBodyOrRaw(body) {
  const s = body === null || body === undefined ? '' : String(body);
  try {
    const v = JSON.parse(s);
    if (v !== null && typeof v === 'object') return v;
  } catch (e) {
    // 非 JSON，原样包一层（同时把正文放进 message，前端可以直接提示）
  }
  return { raw: s, message: s };
}

function loginResult(r) {
  const out = { body: r.body, cookie: r.cookie };
  try {
    const v = JSON.parse(r.body);
    if (v !== null && typeof v === 'object' && !Array.isArray(v)) {
      out.code = optInt(v, 'code', 0);
      out.message = optString(v, 'message', '');
    }
  } catch (e) {
    // 保持 Java 的 {body, cookie} 结构
  }
  return out;
}

// 常量挂到类上，方便调用方复用
NeteaseApi.SONG_URL_V1 = SONG_URL_V1;
NeteaseApi.SONG_DETAIL_V3 = SONG_DETAIL_V3;
NeteaseApi.LYRIC_API = LYRIC_API;
NeteaseApi.SEARCH_API = SEARCH_API;
NeteaseApi.PLAYLIST_DETAIL_API = PLAYLIST_DETAIL_API;
NeteaseApi.ALBUM_DETAIL_API = ALBUM_DETAIL_API;
NeteaseApi.LEVEL_CHAIN = LEVEL_CHAIN;
NeteaseApi.LEVEL_KEYS = LEVEL_KEYS;
NeteaseApi.LEVEL_NAMES = LEVEL_NAMES;
NeteaseApi.UA = UA;
NeteaseApi.REFERER = REFERER;
NeteaseApi.qrPng = qrPng;
NeteaseApi.qrDataUrl = qrDataUrl;
NeteaseApi.qrMatrix = qrMatrix;

module.exports = { NeteaseApi };
