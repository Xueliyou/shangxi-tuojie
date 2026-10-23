'use strict';

/**
 * TagWriter.java（Android 端）的 Node.js 逐行移植版。
 *
 * 支持 MP3（ID3v2.3：TIT2 / TPE1 / TALB / TRCK / TSSE / USLT / TXXX /
 * SYLT / APIC）与 FLAC（VORBIS_COMMENT + PICTURE）的原地标签写入。
 *
 * 字节级行为与 Java 原版保持一致：
 *   - 文本帧一律 UTF-16LE + BOM（Java 的 utf16le() 就是逐 code unit 小端写出）
 *   - ID3 帧大小用 big-endian 的 32 位，整个标签大小用 syncsafe
 *   - FLAC 保留 STREAMINFO，丢弃其余旧 metadata block，再按
 *     STREAMINFO → VORBIS_COMMENT → PICTURE(isLast) 重建
 *   - 所有字符串的 trim / 编码（ISO-8859-1、US-ASCII、UTF-8）都按
 *     java.lang.String 的语义实现（见下方 javaTrim / javaLatin1 / javaUtf8）
 *
 * 仅依赖 Node 内置模块。
 */

const fs = require('fs');

// ================================================================ 通用小工具

/** 单字节 Buffer */
function byte(v) {
  return Buffer.from([v & 0xFF]);
}

/** Java 的 String.trim()：去掉首尾 code unit <= 0x20 的字符（不是 JS 的 Unicode 空白） */
function javaTrim(s) {
  let start = 0;
  let end = s.length;
  while (start < end && s.charCodeAt(start) <= 0x20) start++;
  while (end > start && s.charCodeAt(end - 1) <= 0x20) end--;
  return s.slice(start, end);
}

/** Java 的 notEmpty()：非 null 且 trim 后不是空串 */
function notEmpty(s) {
  return typeof s === 'string' && javaTrim(s).length > 0;
}

/** Java replaceAll(CharSequence, CharSequence) 的字面量全量替换 */
function replaceAllLiteral(s, from, to) {
  return s.split(from).join(to);
}

/** Java 的 Long.parseLong：非法或越界返回 null（对应 Java 抛 NumberFormatException） */
const LONG_MAX = 9223372036854775807n;
const LONG_MIN = -9223372036854775808n;
function parseJavaLong(str) {
  if (typeof str !== 'string' || str.length === 0) return null;
  if (!/^[+-]?\d+$/.test(str)) return null;
  try {
    const b = BigInt(str);
    if (b > LONG_MAX || b < LONG_MIN) return null;
    return Number(b);
  } catch (e) {
    return null;
  }
}

/** Java String.getBytes("ISO-8859-1")：按码点，>0xFF 的码点替换成 '?'（代理对算一个码点） */
function javaLatin1(s) {
  // Java 的 CharsetEncoder 以「码点」为单位：代理对算一个码点，整体替换成一个 '?'
  const out = [];
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length) {
      const d = s.charCodeAt(i + 1);
      if (d >= 0xDC00 && d <= 0xDFFF) {
        out.push(0x3F);
        i++;
        continue;
      }
    }
    out.push(c <= 0xFF ? c : 0x3F);
  }
  return Buffer.from(out);
}

/** Java String.getBytes("US-ASCII")：按码点，>0x7F 的码点替换成 '?'（代理对算一个码点） */
function javaAscii(s) {
  // 同上：以码点为单位替换
  const out = [];
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length) {
      const d = s.charCodeAt(i + 1);
      if (d >= 0xDC00 && d <= 0xDFFF) {
        out.push(0x3F);
        i++;
        continue;
      }
    }
    out.push(c <= 0x7F ? c : 0x3F);
  }
  return Buffer.from(out);
}

/**
 * Java String.getBytes("UTF-8")：
 * 正常代理对编码成 4 字节；孤立代理项在 Java 里会被替换成 '?'（0x3F），
 * 而 Node 的 Buffer.from(str, 'utf8') 会替换成 U+FFFD，所以自己编码。
 */
function javaUtf8(s) {
  const out = [];
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c < 0x80) {
      out.push(c);
    } else if (c < 0x800) {
      out.push(0xC0 | (c >> 6), 0x80 | (c & 0x3F));
    } else if (c >= 0xD800 && c <= 0xDBFF) {
      const d = i + 1 < s.length ? s.charCodeAt(i + 1) : 0;
      if (d >= 0xDC00 && d <= 0xDFFF) {
        const cp = 0x10000 + ((c - 0xD800) << 10) + (d - 0xDC00);
        out.push(
          0xF0 | (cp >> 18),
          0x80 | ((cp >> 12) & 0x3F),
          0x80 | ((cp >> 6) & 0x3F),
          0x80 | (cp & 0x3F)
        );
        i++;
      } else {
        out.push(0x3F);
      }
    } else if (c >= 0xDC00 && c <= 0xDFFF) {
      out.push(0x3F);
    } else {
      out.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F));
    }
  }
  return Buffer.from(out);
}

/** big-endian 32 位（Java 的 int 语义） */
function be32(v) {
  const b = Buffer.allocUnsafe(4);
  b.writeInt32BE(v | 0, 0);
  return b;
}

/** little-endian 32 位（Java 的 int 语义） */
function le32(v) {
  const b = Buffer.allocUnsafe(4);
  b.writeInt32LE(v | 0, 0);
  return b;
}

/** ID3 的 syncsafe 编码；Java 对超界值直接截断，这里也一样 */
function syncSafe(v) {
  v = v | 0;
  return Buffer.from([
    (v >> 21) & 0x7F,
    (v >> 14) & 0x7F,
    (v >> 7) & 0x7F,
    v & 0x7F,
  ]);
}

/** Java utf16le()：BOM（FF FE）+ 每个 code unit 小端 */
function utf16le(s) {
  const b = Buffer.allocUnsafe(2 + s.length * 2);
  b[0] = 0xFF;
  b[1] = 0xFE;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    b[2 + i * 2] = c & 0xFF;
    b[3 + i * 2] = (c >> 8) & 0xFF;
  }
  return b;
}

/** CRLF / CR → LF（Java 里反复出现的 replace("\r\n","\n").replace('\r','\n')） */
function toLf(s) {
  return s.split('\r\n').join('\n').split('\r').join('\n');
}

/** 取出可用的封面 Buffer（Java Meta.hasCover() == cover != null && cover.length > 0） */
function coverBuffer(meta) {
  const c = meta.cover;
  if (c == null || c.length === 0) return null;
  return Buffer.isBuffer(c) ? c : Buffer.from(c);
}

/** 写出整个 Buffer，处理部分写 */
function writeAll(fd, buf) {
  let off = 0;
  while (off < buf.length) {
    const n = fs.writeSync(fd, buf, off, buf.length - off);
    if (n <= 0) throw new Error('write failed');
    off += n;
  }
}

/** 从 start 开始把输入文件剩余部分原样拷到输出文件（64KB 分块，与 Java 一致） */
function copyFrom(inFd, outFd, start) {
  const buf = Buffer.allocUnsafe(64 * 1024);
  let pos = start;
  for (;;) {
    const n = fs.readSync(inFd, buf, 0, buf.length, pos);
    if (n <= 0) break;
    writeAll(outFd, buf.subarray(0, n));
    pos += n;
  }
}

/** 读到 want 字节或 EOF；返回实际读到的字节数（缓冲前 n 字节有效） */
function readUpTo(fd, want, position) {
  const buf = Buffer.allocUnsafe(want);
  let off = 0;
  while (off < want) {
    const n = fs.readSync(fd, buf, off, want - off, position + off);
    if (n <= 0) break;
    off += n;
  }
  return { buf, n: off };
}

/** 精确读 len 字节；不够就抛错（对应 Java RandomAccessFile.readFully 的 EOFException） */
function readExact(fd, len, position) {
  const r = readUpTo(fd, len, position);
  if (r.n !== len) throw new Error('unexpected EOF');
  return r.buf;
}

// ================================================================ 歌词处理

/** 清洗歌词：HTML 实体、UTF-8 误码、零宽/控制字符 */
function sanitizeLrc(s) {
  if (s == null) return '';
  let o = String(s);
  o = replaceAllLiteral(o, '&apos;', "'");
  o = replaceAllLiteral(o, '&#39;', "'");
  o = replaceAllLiteral(o, '&quot;', '"');
  o = replaceAllLiteral(o, '&amp;', '&');
  o = replaceAllLiteral(o, '&lt;', '<');
  o = replaceAllLiteral(o, '&gt;', '>');
  o = replaceAllLiteral(o, '&nbsp;', ' ');
  // UTF-8 被当作 Latin-1 解析后的典型乱码（字符码点与 Java 字符串字面量逐一对应）
  o = replaceAllLiteral(o, '\u00e2\u20ac\u2122', "'");
  o = replaceAllLiteral(o, '\u00e2\u20ac\u02dc', "'");
  o = replaceAllLiteral(o, '\u00e2\u20ac\u0153', '\u201c');
  o = replaceAllLiteral(o, '\u00e2\u20ac\u009d', '\u201d');
  o = replaceAllLiteral(o, '\u00e2\u20ac\u201c', '\u2013');
  o = replaceAllLiteral(o, '\u00e2\u20ac\u201d', '\u2014');
  o = replaceAllLiteral(o, '\u00e2\u20ac\u00a6', '\u2026');
  o = replaceAllLiteral(o, '\u00c2\u00a9', '\u00a9');
  o = replaceAllLiteral(o, '\u00c3\u00a9', '\u00e9');
  // 零宽字符
  o = replaceAllLiteral(o, '\uFEFF', '');
  o = replaceAllLiteral(o, '\u200B', '');
  o = replaceAllLiteral(o, '\u200C', '');
  o = replaceAllLiteral(o, '\u200D', '');
  // 控制字符（保留 \t \n \r）
  o = o.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F]/g, '');
  return o;
}

/** 歌词里是否已经带标准时间戳 */
const TIMESTAMP_RE = /\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?(?:-\d+)?\]/;
const LEADING_TIMESTAMP_RE = /^\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?(?:-\d+)?\]/;
function hasTimestamp(s) {
  return s != null && TIMESTAMP_RE.test(String(s));
}

/**
 * 纯文本歌词按歌曲时长均匀分配时间轴。
 * 注意：不满足条件时原样返回传入的 plain（Java 行为）。
 */
function estimateLrc(plain, durationMs) {
  if (plain == null) return '';
  const raw = toLf(String(plain)).split('\n');
  const lines = [];
  for (const r of raw) {
    const t = javaTrim(r);
    if (t.length > 0) lines.push(t);
  }
  const dur = Number.isFinite(Number(durationMs)) ? Number(durationMs) : 0;
  if (lines.length < 2 || dur <= 10000) return plain;

  const start = 800;
  const end = dur - 4000;
  if (end <= start) return plain;
  const step = Math.trunc((end - start) / (lines.length - 1));

  let sb = '';
  for (let i = 0; i < lines.length; i++) {
    const t = start + step * i;
    const sec = Math.trunc(t / 1000);
    const ms = t % 1000;
    sb +=
      '[' + pad2(Math.trunc(sec / 60)) + ':' + pad2(sec % 60) + '.' +
      pad2(Math.trunc(ms / 10)) + ']' + lines[i] + '\n';
  }
  return sb;
}

/** Java String.format("%02d") 的最小实现（非负数） */
function pad2(n) {
  return String(n).length >= 2 ? String(n) : '0' + String(n);
}

/**
 * 把翻译歌词（tlyric）合并进原文：每一行原文后面补一行同时间戳的译文。
 */
function mergeTranslation(lrc, tlyric) {
  if (lrc == null) lrc = '';
  if (tlyric == null) tlyric = '';
  lrc = String(lrc);
  tlyric = String(tlyric);
  if (javaTrim(lrc).length === 0 || javaTrim(tlyric).length === 0) return lrc;

  const a = normalizeLrc(lrc);
  const b = normalizeLrc(tlyric);
  if (javaTrim(a) === javaTrim(b)) return lrc; // 有些歌「翻译」就是原文

  const tr = new Map();
  for (const raw of b.split('\n')) {
    const line = javaTrim(raw);
    if (line.length < 7 || line.charAt(0) !== '[') continue;
    const close = line.indexOf(']');
    if (close <= 1) continue;
    const ms = stampMs(line.slice(1, close));
    if (ms === null) continue;
    const text = javaTrim(line.slice(close + 1));
    if (text.length === 0) continue;
    if (text.startsWith('//')) continue; // 纯音乐占位
    if (!tr.has(ms)) tr.set(ms, text);
  }
  if (tr.size === 0) return lrc;

  let sb = '';
  for (const raw of a.split('\n')) {
    const line = javaTrim(raw);
    if (line.length === 0) continue;
    sb += line + '\n';
    if (line.length < 7 || line.charAt(0) !== '[') continue;
    const close = line.indexOf(']');
    if (close <= 1) continue;
    const ms = stampMs(line.slice(1, close));
    if (ms === null) continue;
    const text = tr.get(ms);
    if (text === undefined) continue;
    sb += line.slice(0, close + 1) + text + '\n';
  }
  return sb;
}

/** [mm:ss.xx] / [mm:ss.xxx] / [mm:ss] → 毫秒；不是时间戳返回 null */
function stampMs(ts) {
  try {
    const c = ts.indexOf(':');
    if (c <= 0) return null;
    const mm = parseJavaLong(javaTrim(ts.slice(0, c)));
    if (mm === null) return null;
    const rest = ts.slice(c + 1);
    let d = rest.indexOf('.');
    const d2 = rest.indexOf(':');
    if (d2 >= 0 && (d < 0 || d2 < d)) d = d2;
    const ssStr = d < 0 ? rest : rest.slice(0, d);
    const ss = parseJavaLong(javaTrim(ssStr));
    if (ss === null) return null;
    let ms = 0;
    if (d >= 0) {
      let frac = rest.slice(d + 1);
      if (frac.length > 3) frac = frac.slice(0, 3);
      while (frac.length < 3) frac += '0';
      const msv = parseJavaLong(frac);
      if (msv === null) return null;
      ms = msv;
    }
    return mm * 60000 + ss * 1000 + ms;
  } catch (e) {
    return null;
  }
}

/** 统一入口：规范化 + 必要时估算时间轴 */
function prepareLyric(raw, durationMs) {
  let lrc = normalizeLrc(sanitizeLrc(raw));
  if (!hasTimestamp(lrc) && Number(durationMs) > 0) {
    lrc = estimateLrc(lrc, durationMs);
  }
  return lrc;
}

/**
 * LRC 时间戳规范化：
 *   [mm:ss:xx] → [mm:ss.xx]
 *   [mm:ss]    → [mm:ss.00]
 */
function normalizeLrc(lrc) {
  if (lrc == null) return '';
  let s = toLf(String(lrc));
  s = s.replace(/\[(\d{1,3}):(\d{1,2}):(\d{1,3})\]/g, '[$1:$2.$3]');
  s = s.replace(/\[(\d{1,3}):(\d{1,2})\](?=\D|$)/g, '[$1:$2.00]');
  return s;
}

/** 把 LRC 转成纯文本歌词（去掉行首时间戳），保留换行 */
function stripTimestamps(lrc) {
  if (lrc == null) return '';
  let sb = '';
  for (const raw of toLf(String(lrc)).split('\n')) {
    let t = javaTrim(raw);
    if (t.length === 0) continue;
    t = javaTrim(t.replace(LEADING_TIMESTAMP_RE, ''));
    if (t.length > 0) sb += t + '\n';
  }
  return sb;
}

// ================================================================ MP3 / ID3v2.3

/** 构造完整 ID3v2.3 标签（含 10 字节头） */
function buildId3(m) {
  const frames = [];
  addTextFrame(frames, 'TIT2', m.title);
  addTextFrame(frames, 'TPE1', m.artist);
  addTextFrame(frames, 'TALB', m.album);
  if ((m.track | 0) > 0) addTextFrame(frames, 'TRCK', String(m.track | 0));
  addTextFrame(frames, 'TSSE', 'NeteaseParser');

  if (notEmpty(m.lyric)) {
    const lrc = prepareLyric(m.lyric, m.durationMs);
    if (notEmpty(lrc)) {
      if (hasTimestamp(lrc)) {
        // 有时间轴：USLT + TXXX:LYRICS + SYLT
        addUsltFrame(frames, lrc);
        addTxxxFrame(frames, 'LYRICS', lrc);
        addSyltFrame(frames, lrc);
      } else {
        // 纯文本歌词：USLT + TXXX:UNSYNCEDLYRICS
        addUsltFrame(frames, lrc);
        addTxxxFrame(frames, 'UNSYNCEDLYRICS', stripTimestamps(lrc));
      }
    }
  }

  const cover = coverBuffer(m);
  if (cover !== null) addApicFrame(frames, cover, m.coverMime);

  const body = Buffer.concat(frames);
  const header = Buffer.allocUnsafe(10);
  header[0] = 0x49; // 'I'
  header[1] = 0x44; // 'D'
  header[2] = 0x33; // '3'
  header[3] = 3;    // 版本 2.3
  header[4] = 0;    // 修订号
  header[5] = 0;    // 标志位
  syncSafe(body.length).copy(header, 6);
  return Buffer.concat([header, body]);
}

/** 文本帧：编码 0x01 + UTF-16LE(BOM) 的 trim 后文本 */
function addTextFrame(frames, id, text) {
  if (!notEmpty(text)) return;
  const d = Buffer.concat([byte(0x01), utf16le(javaTrim(text))]);
  frames.push(writeFrame(id, d));
}

/** USLT：不带时间轴的歌词；语言固定 eng，描述符为空，正文也带 BOM */
function addUsltFrame(frames, lyric) {
  const l = toLf(lyric);
  const d = Buffer.allocUnsafe(10 + l.length * 2);
  d[0] = 0x01;             // UTF-16
  d[1] = 0x65;             // 'e'
  d[2] = 0x6E;             // 'n'
  d[3] = 0x67;             // 'g'
  d[4] = 0xFF;             // 描述符 BOM
  d[5] = 0xFE;
  d[6] = 0x00;             // 空描述符
  d[7] = 0x00;
  d[8] = 0xFF;             // 歌词正文 BOM
  d[9] = 0xFE;
  for (let i = 0; i < l.length; i++) {
    const c = l.charCodeAt(i);
    d[10 + i * 2] = c & 0xFF;
    d[11 + i * 2] = (c >> 8) & 0xFF;
  }
  frames.push(writeFrame('USLT', d));
}

/** TXXX：描述符和值都各带一个 BOM */
function addTxxxFrame(frames, desc, value) {
  const v = toLf(value);
  const d = Buffer.concat([
    byte(0x01),
    utf16le(desc),
    Buffer.from([0x00, 0x00]),
    utf16le(v),
  ]);
  frames.push(writeFrame('TXXX', d));
}

/** SYLT：逐行同步歌词（时间戳格式 2 = 毫秒，内容类型 1 = 歌词） */
function addSyltFrame(frames, lrc) {
  const parts = [Buffer.from([0x01, 0x65, 0x6E, 0x67, 0x02, 0x01, 0xFF, 0xFE, 0x00, 0x00])];
  let count = 0;
  for (const raw of toLf(lrc).split('\n')) {
    const line = javaTrim(raw);
    if (line.length < 6 || line.charAt(0) !== '[') continue;
    const close = line.indexOf(']');
    if (close < 0) continue;
    const ts = line.slice(1, close);
    const text = javaTrim(line.slice(close + 1));
    const c1 = ts.indexOf(':');
    if (c1 <= 0) continue;
    const rest = ts.slice(c1 + 1);
    let c2 = rest.indexOf(':');
    if (c2 < 0) c2 = rest.indexOf('.');
    const ssStr = c2 < 0 ? rest : rest.slice(0, c2);
    const frac = c2 < 0 ? null : rest.slice(c2 + 1);

    let t;
    try {
      const mm = parseJavaLong(javaTrim(ts.slice(0, c1)));
      const sec = parseJavaLong(javaTrim(ssStr));
      if (mm === null || sec === null) continue;
      let ms = 0;
      if (frac !== null && frac.length > 0) {
        const msv = parseJavaLong((frac + '000').slice(0, 3));
        if (msv === null) continue;
        ms = msv;
      }
      t = mm * 60000 + sec * 1000 + ms;
    } catch (e) {
      continue;
    }
    if (text.length === 0) continue;

    const u = t >>> 0; // Java 写的是 long 的低 32 位
    parts.push(utf16le(text));
    parts.push(Buffer.from([0x00, 0x00, (u >>> 24) & 0xFF, (u >>> 16) & 0xFF, (u >>> 8) & 0xFF, u & 0xFF]));
    count++;
  }
  if (count === 0) return;
  frames.push(writeFrame('SYLT', Buffer.concat(parts)));
}

/** APIC：描述用 ISO-8859-1、图片类型 0x03（正面封面）、空描述 */
function addApicFrame(frames, img, mime) {
  const mimeB = javaLatin1(mime == null ? 'image/jpeg' : mime);
  const d = Buffer.concat([
    byte(0x00),
    mimeB,
    Buffer.from([0x00, 0x03, 0x00]),
    Buffer.from(img),
  ]);
  frames.push(writeFrame('APIC', d));
}

/** 一个 ID3v2.3 帧：4 字节 id + 4 字节 big-endian 长度 + 2 字节标志 */
function writeFrame(id, data) {
  const idB = javaLatin1(id);
  const head = Buffer.allocUnsafe(idB.length + 6);
  idB.copy(head, 0);
  be32(data.length).copy(head, idB.length);
  head[idB.length + 4] = 0;
  head[idB.length + 5] = 0;
  return Buffer.concat([head, data]);
}

/** 原地写 MP3：剥掉旧 ID3v2，标签插到音频数据前面 */
function writeMp3(filePath, m) {
  const inFd = fs.openSync(filePath, 'r');
  try {
    const size = fs.fstatSync(inFd).size;
    let audioStart = 0;
    if (size >= 10) {
      const h = readExact(inFd, 10, 0);
      if (h[0] === 0x49 && h[1] === 0x44 && h[2] === 0x33) {
        const tagSize =
          ((h[6] & 0x7F) << 21) |
          ((h[7] & 0x7F) << 14) |
          ((h[8] & 0x7F) << 7) |
          (h[9] & 0x7F);
        audioStart = 10 + tagSize;
        if ((h[5] & 0x10) !== 0) audioStart += 10; // footer
      }
    }
    if (audioStart >= size) audioStart = 0;

    const tag = buildId3(m);
    const tmp = filePath + '.tmp';
    const outFd = fs.openSync(tmp, 'w');
    try {
      writeAll(outFd, tag);
      copyFrom(inFd, outFd, audioStart);
    } finally {
      fs.closeSync(outFd);
    }
    return replaceFile(tmp, filePath);
  } finally {
    fs.closeSync(inFd);
  }
}

// ================================================================ FLAC

/** 原地写 FLAC：保留 STREAMINFO，重建 VORBIS_COMMENT / PICTURE */
function writeFlac(filePath, m) {
  const inFd = fs.openSync(filePath, 'r');
  let tmp = null;
  try {
    const size = fs.fstatSync(inFd).size;
    if (size < 8) return false;
    const sig = readExact(inFd, 4, 0);
    if (sig[0] !== 0x66 || sig[1] !== 0x4C || sig[2] !== 0x61 || sig[3] !== 0x43) return false;

    let streamInfo = null;
    let audioStart = -1;
    let pos = 4;
    for (;;) {
      const r = readUpTo(inFd, 4, pos);
      if (r.n < 4) break;
      const h = r.buf;
      const last = (h[0] & 0x80) !== 0;
      const type = h[0] & 0x7F;
      const len = (h[1] << 16) | (h[2] << 8) | h[3];
      const dataPos = pos + 4;
      if (type === 0) {
        // STREAMINFO 必须原样保留
        streamInfo = readExact(inFd, len, dataPos);
      }
      pos = dataPos + len;
      if (last) {
        audioStart = pos;
        break;
      }
    }
    if (streamInfo === null || audioStart < 0) return false;

    const vc = buildVorbisComment(m);
    const pic = coverBuffer(m) !== null ? buildFlacPicture(m) : null;

    tmp = filePath + '.tmp';
    const outFd = fs.openSync(tmp, 'w');
    try {
      writeAll(outFd, Buffer.from([0x66, 0x4C, 0x61, 0x43])); // 'fLaC'
      writeAll(outFd, flacBlock(0, streamInfo, false));       // STREAMINFO
      writeAll(outFd, flacBlock(4, vc, pic === null));        // VORBIS_COMMENT
      if (pic !== null) writeAll(outFd, flacBlock(6, pic, true)); // PICTURE（最后一个）
      copyFrom(inFd, outFd, audioStart);
    } finally {
      fs.closeSync(outFd);
    }
    return replaceFile(tmp, filePath);
  } finally {
    fs.closeSync(inFd);
  }
}

/** FLAC metadata block 头（1 字节 isLast|type + 3 字节长度）+ 数据 */
function flacBlock(type, data, last) {
  const head = Buffer.allocUnsafe(4);
  head[0] = (last ? 0x80 : 0x00) | (type & 0x7F);
  head[1] = (data.length >>> 16) & 0xFF;
  head[2] = (data.length >>> 8) & 0xFF;
  head[3] = data.length & 0xFF;
  return Buffer.concat([head, data]);
}

function buildVorbisComment(m) {
  const parts = [];
  const vendor = javaUtf8('NeteaseParser 1.0');
  parts.push(le32(vendor.length), vendor);

  const cs = [];
  if (notEmpty(m.title)) cs.push('TITLE=' + javaTrim(m.title));
  if (notEmpty(m.artist)) cs.push('ARTIST=' + javaTrim(m.artist));
  if (notEmpty(m.album)) cs.push('ALBUM=' + javaTrim(m.album));
  if ((m.track | 0) > 0) cs.push('TRACKNUMBER=' + (m.track | 0));
  if (notEmpty(m.lyric)) {
    const lrc = prepareLyric(m.lyric, m.durationMs);
    if (hasTimestamp(lrc)) {
      cs.push('LYRICS=' + lrc);
      cs.push('LYRIC=' + lrc);
    } else {
      cs.push('UNSYNCEDLYRICS=' + stripTimestamps(lrc));
    }
  }

  parts.push(le32(cs.length));
  for (const c of cs) {
    const b = javaUtf8(c);
    parts.push(le32(b.length), b);
  }
  return Buffer.concat(parts);
}

function buildFlacPicture(m) {
  const cover = coverBuffer(m);
  const mime = javaAscii(m.coverMime == null ? 'image/jpeg' : m.coverMime);
  return Buffer.concat([
    be32(3),                          // 类型：正面封面
    be32(mime.length),
    mime,
    be32(0),                          // 描述长度
    be32(m.coverW | 0),
    be32(m.coverH | 0),
    be32(24),                         // 位深
    be32(0),                          // 索引色数
    be32(cover.length),
    cover,
  ]);
}

// ================================================================ 文件替换 / 入口

/** 用临时文件替换原文件；失败时清理临时文件并返回 false */
function replaceFile(tmp, dst) {
  try {
    fs.renameSync(tmp, dst);
    return true;
  } catch (e) {
    try {
      fs.unlinkSync(tmp);
    } catch (e2) {
      // ignore
    }
    return false;
  }
}

/**
 * 原地写入标签；返回是否成功（与 Java 一致：失败一律返回 false，不抛异常）。
 * @param {string} filePath
 * @param {string} ext  "mp3" | "flac"（大小写敏感，同 Java）
 * @param {object} meta { title, artist, album, lyric, durationMs, cover, coverW, coverH, coverMime, track }
 * @returns {boolean}
 */
function write(filePath, ext, meta) {
  if (filePath == null || meta == null) return false;
  try {
    if (!fs.existsSync(filePath)) return false;
    if (ext === 'mp3') return writeMp3(filePath, meta);
    if (ext === 'flac') return writeFlac(filePath, meta);
  } catch (e) {
    // 标签写失败不该影响文件本身
  }
  return false;
}

// ================================================================ 图片尺寸

/** 解析 JPEG / PNG 的宽高；无法识别返回 [0, 0] */
function imageSize(d) {
  if (d == null || d.length < 24) return [0, 0];
  // PNG
  if ((d[0] & 0xFF) === 0x89 && d[1] === 0x50 && d[2] === 0x4E && d[3] === 0x47) {
    const w = ((d[16] & 0xFF) << 24) | ((d[17] & 0xFF) << 16) | ((d[18] & 0xFF) << 8) | (d[19] & 0xFF);
    const h = ((d[20] & 0xFF) << 24) | ((d[21] & 0xFF) << 16) | ((d[22] & 0xFF) << 8) | (d[23] & 0xFF);
    return [w, h];
  }
  // JPEG
  if ((d[0] & 0xFF) === 0xFF && (d[1] & 0xFF) === 0xD8) {
    let i = 2;
    while (i + 9 < d.length) {
      if ((d[i] & 0xFF) !== 0xFF) {
        i++;
        continue;
      }
      const mk = d[i + 1] & 0xFF;
      if (mk === 0xFF) {
        i++;
        continue;
      }
      if (mk >= 0xC0 && mk <= 0xCF && mk !== 0xC4 && mk !== 0xC8 && mk !== 0xCC) {
        const h = ((d[i + 5] & 0xFF) << 8) | (d[i + 6] & 0xFF);
        const w = ((d[i + 7] & 0xFF) << 8) | (d[i + 8] & 0xFF);
        return [w, h];
      }
      if (mk === 0xD8 || mk === 0xD9 || (mk >= 0xD0 && mk <= 0xD7)) {
        i += 2;
        continue;
      }
      const len = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
      if (len < 2) break;
      i += 2 + len;
    }
  }
  return [0, 0];
}

module.exports = {
  write,
  sanitizeLrc,
  normalizeLrc,
  estimateLrc,
  hasTimestamp,
  stripTimestamps,
  mergeTranslation,
  imageSize,
};
