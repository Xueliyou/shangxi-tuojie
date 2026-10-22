package com.whale.netease;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 把封面 / 歌词 / 歌手 / 专辑等元数据写进下载好的音频文件。
 * 支持 MP3（ID3v2.3：TIT2/TPE1/TALB/APIC/USLT）与 FLAC（Vorbis Comment + PICTURE）。
 * 纯手写实现，不依赖任何第三方标签库。
 */
public final class TagWriter {

    /** 要写入的元数据 */
    public static class Meta {
        public String title;
        public String artist;
        public String album;
        public String lyric;
        /** 歌曲时长（毫秒），用于给纯文本歌词估算时间轴 */
        public long durationMs;
        public byte[] cover;
        public int coverW;
        public int coverH;
        public String coverMime = "image/jpeg";
        public int track;

        public boolean hasCover() {
            return cover != null && cover.length > 0;
        }
    }

    private TagWriter() {}

    /** 原地写入标签；返回是否成功 */
    public static boolean write(File file, String ext, Meta meta) {
        if (file == null || !file.exists() || meta == null) return false;
        try {
            if ("mp3".equals(ext)) return writeMp3(file, meta);
            if ("flac".equals(ext)) return writeFlac(file, meta);
        } catch (Throwable ignored) {
            // 标签写失败不该影响文件本身（原文件保持不动）
        }
        return false;
    }

    // ================================================================ MP3

    private static boolean writeMp3(File src, Meta m) throws IOException {
        long audioStart = 0;
        RandomAccessFile raf = new RandomAccessFile(src, "r");
        try {
            if (raf.length() >= 10) {
                byte[] h = new byte[10];
                raf.readFully(h);
                if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') {
                    int size = ((h[6] & 0x7F) << 21) | ((h[7] & 0x7F) << 14)
                             | ((h[8] & 0x7F) << 7) | (h[9] & 0x7F);
                    audioStart = 10L + size;
                    if ((h[5] & 0x10) != 0) audioStart += 10;   // footer
                }
            }
            if (audioStart >= raf.length()) audioStart = 0;

            byte[] tag = buildId3(m);
            File tmp = new File(src.getParentFile(), src.getName() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(tag);
                raf.seek(audioStart);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = raf.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            } finally {
                out.close();
            }
            if (!src.delete()) { tmp.delete(); return false; }
            return tmp.renameTo(src);
        } finally {
            raf.close();
        }
    }

    private static byte[] buildId3(Meta m) throws IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream(8192);
        addTextFrame(frames, "TIT2", m.title);
        addTextFrame(frames, "TPE1", m.artist);
        addTextFrame(frames, "TALB", m.album);
        if (m.track > 0) addTextFrame(frames, "TRCK", String.valueOf(m.track));
        addTextFrame(frames, "TSSE", "NeteaseParser");
        if (notEmpty(m.lyric)) {
            String lrc = prepareLyric(m.lyric, m.durationMs);
            if (notEmpty(lrc)) {
                if (hasTimestamp(lrc)) {
                    // 有时间轴：USLT 存完整 LRC（会滚动的播放器），TXXX:LYRICS 兼容只认自定义字段的，
                    // 再加一份 SYLT 逐行同步帧（车机 / 部分播放器优先读它）
                    addUsltFrame(frames, lrc);
                    addTxxxFrame(frames, "LYRICS", lrc);
                    addSyltFrame(frames, lrc);
                } else {
                    // 纯文本歌词：USLT + UNSYNCEDLYRICS（明确表示"没有时间轴"）
                    addUsltFrame(frames, lrc);
                    addTxxxFrame(frames, "UNSYNCEDLYRICS", stripTimestamps(lrc));
                }
            }
        }
        if (m.hasCover()) addApicFrame(frames, m.cover, m.coverMime);

        byte[] body = frames.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 10);
        out.write('I'); out.write('D'); out.write('3');
        out.write(3);            // 版本 2.3
        out.write(0);            // 修订号
        out.write(0);            // 标志位
        out.write(syncSafe(body.length));
        out.write(body);
        return out.toByteArray();
    }

    private static void addTextFrame(ByteArrayOutputStream out, String id, String text) throws IOException {
        if (!notEmpty(text)) return;
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        d.write(0x01);                       // 编码：UTF-16 带 BOM
        d.write(utf16le(text.trim()));
        writeFrame(out, id, d.toByteArray());
    }

    /** 清洗歌词：HTML 实体、UTF-8 误码、零宽/控制字符 */
    public static String sanitizeLrc(String s) {
        if (s == null) return "";
        String o = s;
        o = o.replace("&apos;", "'").replace("&#39;", "'").replace("&quot;", "\"")
             .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&nbsp;", " ");
        // UTF-8 被当作 Latin-1 解析后的典型乱码
        o = o.replace("â€™", "'").replace("â€˜", "'")
             .replace("â€œ", "\u201c").replace("â€\u009d", "\u201d")
             .replace("â€“", "\u2013").replace("â€”", "\u2014")
             .replace("â€¦", "\u2026").replace("Â©", "\u00a9").replace("Ã©", "\u00e9");
        // 零宽字符 / 控制字符（保留换行）
        o = o.replace("\uFEFF", "").replace("\u200B", "")
             .replace("\u200C", "").replace("\u200D", "");
        o = o.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        return o;
    }

    /** 歌词里是否已经带标准时间戳 */
    private static boolean hasTimestamp(String lrc) {
        if (lrc == null) return false;
        return java.util.regex.Pattern.compile("\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?(?:-\\d+)?\\]")
                .matcher(lrc).find();
    }

    /**
     * 纯文本歌词（网易云有些歌只提供无时间轴的文本）→ 按歌曲时长均匀分配时间轴，
     * 让播放器能逐行滚动。属于估算，节奏不会完全吻合。
     */
    public static String estimateLrc(String plain, long durationMs) {
        if (plain == null) return "";
        String[] raw = plain.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        java.util.List<String> lines = new java.util.ArrayList<String>();
        for (String r : raw) {
            String t = r.trim();
            if (!t.isEmpty()) lines.add(t);
        }
        if (lines.size() < 2 || durationMs <= 10000) return plain;

        long start = 800;
        long end = durationMs - 4000;
        if (end <= start) return plain;
        long step = (end - start) / (lines.size() - 1);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            long t = start + step * i;
            long sec = t / 1000, ms = t % 1000;
            sb.append(String.format("[%02d:%02d.%02d]%s\n", sec / 60, sec % 60, ms / 10, lines.get(i)));
        }
        return sb.toString();
    }

    /** 统一入口：规范化 + 必要时估算时间轴 */
    private static String prepareLyric(String raw, long durationMs) {
        String lrc = normalizeLrc(sanitizeLrc(raw));
        if (!hasTimestamp(lrc) && durationMs > 0) {
            lrc = estimateLrc(lrc, durationMs);
        }
        return lrc;
    }

    /**
     * LRC 时间戳规范化：
     *   [mm:ss:xx] → [mm:ss.xx]   （网易云有些歌词用冒号分隔毫秒）
     *   [mm:ss]    → [mm:ss.00]   （缺毫秒）
     */
    public static String normalizeLrc(String lrc) {
        if (lrc == null) return "";
        String s = lrc.replace("\r\n", "\n").replace('\r', '\n');
        s = s.replaceAll("\\[(\\d{1,3}):(\\d{1,2}):(\\d{1,3})\\]", "[$1:$2.$3]");
        s = s.replaceAll("\\[(\\d{1,3}):(\\d{1,2})\\](?=\\D|$)", "[$1:$2.00]");
        return s;
    }

    /** 把 LRC 转成纯文本歌词（去掉行首时间戳），保留换行 */
    private static String stripTimestamps(String lrc) {
        if (lrc == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = lrc.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String raw : lines) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            t = t.replaceAll("^\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?(?:-\\d+)?\\]", "").trim();
            if (!t.isEmpty()) sb.append(t).append('\n');
        }
        return sb.toString();
    }

    /** USLT：不带时间轴的歌词，绝大多数播放器都认 */
    private static void addUsltFrame(ByteArrayOutputStream out, String lyric) throws IOException {
        String l = lyric.replace("\r\n", "\n").replace('\r', '\n');
        ByteArrayOutputStream d = new ByteArrayOutputStream(l.length() * 2 + 16);
        d.write(0x01);                       // UTF-16
        d.write('e'); d.write('n'); d.write('g');   // 语言
        d.write(0xFF); d.write(0xFE);        // 描述符 BOM
        d.write(0x00); d.write(0x00);        // 空描述符
        d.write(0xFF); d.write(0xFE);        // 歌词正文同样要带 BOM（否则解析方猜错字节序）
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            d.write(c & 0xFF);
            d.write((c >> 8) & 0xFF);
        }
        writeFrame(out, "USLT", d.toByteArray());
    }

    /** TXXX：自定义文本字段（把歌词也放一份，兼容只认 TXXX:LYRICS 的播放器） */
    private static void addTxxxFrame(ByteArrayOutputStream out, String desc, String value) throws IOException {
        String v = value.replace("\r\n", "\n").replace('\r', '\n');
        ByteArrayOutputStream d = new ByteArrayOutputStream(v.length() * 2 + 64);
        d.write(0x01);                       // UTF-16
        d.write(utf16le(desc));              // 描述
        d.write(0x00); d.write(0x00);        // 描述结束
        d.write(utf16le(v));                 // 值
        writeFrame(out, "TXXX", d.toByteArray());
    }

    /** SYLT：逐行同步歌词帧（ID3v2.3），时间戳格式为毫秒 */
    private static void addSyltFrame(ByteArrayOutputStream out, String lrc) throws IOException {
        String[] lines = lrc.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        ByteArrayOutputStream d = new ByteArrayOutputStream(1024);
        d.write(1);                                  // 编码：UTF-16 带 BOM
        d.write('e'); d.write('n'); d.write('g');    // 语言
        d.write(2);                                  // 时间戳格式：毫秒
        d.write(1);                                  // 内容类型：歌词
        d.write(0xFF); d.write(0xFE); d.write(0); d.write(0);   // 描述符：空串
        int count = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() < 6 || line.charAt(0) != '[') continue;
            int close = line.indexOf(']');
            if (close < 0) continue;
            String ts = line.substring(1, close);
            String text = line.substring(close + 1).trim();
            int c1 = ts.indexOf(':');
            if (c1 <= 0) continue;
            String rest = ts.substring(c1 + 1);
            int c2 = rest.indexOf(':');
            if (c2 < 0) c2 = rest.indexOf('.');
            String ss = c2 < 0 ? rest : rest.substring(0, c2);
            String frac = c2 < 0 ? null : rest.substring(c2 + 1);
            long t;
            try {
                long mm = Long.parseLong(ts.substring(0, c1).trim());
                long sec = Long.parseLong(ss.trim());
                long ms = 0;
                if (frac != null && frac.length() > 0) {
                    String f = (frac + "000").substring(0, 3);
                    ms = Long.parseLong(f);
                }
                t = mm * 60000L + sec * 1000L + ms;
            } catch (Exception e) {
                continue;
            }
            if (text.isEmpty()) continue;
            d.write(0xFF); d.write(0xFE);
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                d.write(ch & 0xFF);
                d.write((ch >> 8) & 0xFF);
            }
            d.write(0); d.write(0);
            d.write((int) ((t >> 24) & 0xFF));
            d.write((int) ((t >> 16) & 0xFF));
            d.write((int) ((t >> 8) & 0xFF));
            d.write((int) (t & 0xFF));
            count++;
        }
        if (count == 0) return;
        writeFrame(out, "SYLT", d.toByteArray());
    }

    /** APIC：嵌入封面 */
    private static void addApicFrame(ByteArrayOutputStream out, byte[] img, String mime) throws IOException {
        ByteArrayOutputStream d = new ByteArrayOutputStream(img.length + 32);
        d.write(0x00);                                   // 描述用 ISO-8859-1
        d.write((mime == null ? "image/jpeg" : mime).getBytes("ISO-8859-1"));
        d.write(0x00);                                   // mime 结束
        d.write(0x03);                                   // 图片类型：正面封面
        d.write(0x00);                                   // 空描述
        d.write(img);
        writeFrame(out, "APIC", d.toByteArray());
    }

    private static void writeFrame(ByteArrayOutputStream out, String id, byte[] data) throws IOException {
        out.write(id.getBytes("ISO-8859-1"));
        out.write(be32(data.length));
        out.write(0); out.write(0);          // 帧标志
        out.write(data);
    }

    private static byte[] utf16le(String s) {
        byte[] b = new byte[2 + s.length() * 2];
        b[0] = (byte) 0xFF; b[1] = (byte) 0xFE;    // BOM（小端）
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b[2 + i * 2] = (byte) (c & 0xFF);
            b[3 + i * 2] = (byte) ((c >> 8) & 0xFF);
        }
        return b;
    }

    // =============================================================== FLAC

    private static boolean writeFlac(File src, Meta m) throws IOException {
        byte[] streamInfo = null;
        long audioStart = -1;

        RandomAccessFile raf = new RandomAccessFile(src, "r");
        try {
            if (raf.length() < 8) return false;
            byte[] sig = new byte[4];
            raf.readFully(sig);
            if (sig[0] != 'f' || sig[1] != 'L' || sig[2] != 'a' || sig[3] != 'C') return false;

            long pos = 4;
            while (true) {
                raf.seek(pos);
                int h = raf.read();
                if (h < 0) break;
                boolean last = (h & 0x80) != 0;
                int type = h & 0x7F;
                int b1 = raf.read(), b2 = raf.read(), b3 = raf.read();
                if (b1 < 0 || b2 < 0 || b3 < 0) break;
                int len = (b1 << 16) | (b2 << 8) | b3;
                long dataPos = pos + 4;
                if (type == 0) {                       // STREAMINFO 必须保留
                    streamInfo = new byte[len];
                    raf.seek(dataPos);
                    raf.readFully(streamInfo);
                }
                pos = dataPos + len;
                if (last) { audioStart = pos; break; }
            }
        } finally {
            raf.close();
        }
        if (streamInfo == null || audioStart < 0) return false;

        byte[] vc = buildVorbisComment(m);
        byte[] pic = m.hasCover() ? buildFlacPicture(m) : null;

        File tmp = new File(src.getParentFile(), src.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        RandomAccessFile in = new RandomAccessFile(src, "r");
        try {
            out.write(new byte[]{'f', 'L', 'a', 'C'});
            writeBlock(out, 0, streamInfo, false);              // STREAMINFO
            writeBlock(out, 4, vc, pic == null);                // VORBIS_COMMENT
            if (pic != null) writeBlock(out, 6, pic, true);     // PICTURE（最后一个）
            in.seek(audioStart);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            out.close();
            in.close();
        }
        if (!src.delete()) { tmp.delete(); return false; }
        return tmp.renameTo(src);
    }

    private static void writeBlock(FileOutputStream out, int type, byte[] data, boolean last) throws IOException {
        out.write((last ? 0x80 : 0x00) | (type & 0x7F));
        out.write((data.length >> 16) & 0xFF);
        out.write((data.length >> 8) & 0xFF);
        out.write(data.length & 0xFF);
        out.write(data);
    }

    private static byte[] buildVorbisComment(Meta m) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        byte[] vendor = "NeteaseParser 1.0".getBytes("UTF-8");
        le32(out, vendor.length);
        out.write(vendor);

        List<String> cs = new ArrayList<String>();
        if (notEmpty(m.title)) cs.add("TITLE=" + m.title.trim());
        if (notEmpty(m.artist)) cs.add("ARTIST=" + m.artist.trim());
        if (notEmpty(m.album)) cs.add("ALBUM=" + m.album.trim());
        if (m.track > 0) cs.add("TRACKNUMBER=" + m.track);
        if (notEmpty(m.lyric)) {
            String lrc = prepareLyric(m.lyric, m.durationMs);
            if (hasTimestamp(lrc)) {
                cs.add("LYRICS=" + lrc);                // 标准：支持 LRC 的播放器会逐行同步
                cs.add("LYRIC=" + lrc);                 // 部分播放器（如椒盐）只认这个
            } else {
                cs.add("UNSYNCEDLYRICS=" + stripTimestamps(lrc));   // 兜底：纯文本，不带时间戳
            }
        }

        le32(out, cs.size());
        for (String c : cs) {
            byte[] b = c.getBytes("UTF-8");
            le32(out, b.length);
            out.write(b);
        }
        return out.toByteArray();
    }

    private static byte[] buildFlacPicture(Meta m) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(m.cover.length + 64);
        be32out(out, 3);                                      // 类型：正面封面
        byte[] mime = (m.coverMime == null ? "image/jpeg" : m.coverMime).getBytes("US-ASCII");
        be32out(out, mime.length);
        out.write(mime);
        be32out(out, 0);                                      // 描述长度
        be32out(out, m.coverW);
        be32out(out, m.coverH);
        be32out(out, 24);                                     // 位深
        be32out(out, 0);                                      // 索引色数
        be32out(out, m.cover.length);
        out.write(m.cover);
        return out.toByteArray();
    }

    // ============================================================== 工具

    /** 解析 JPEG / PNG 的宽高（写 FLAC 的 PICTURE 块需要） */
    public static int[] imageSize(byte[] d) {
        if (d == null || d.length < 24) return new int[]{0, 0};
        // PNG
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            int w = ((d[16] & 0xFF) << 24) | ((d[17] & 0xFF) << 16) | ((d[18] & 0xFF) << 8) | (d[19] & 0xFF);
            int h = ((d[20] & 0xFF) << 24) | ((d[21] & 0xFF) << 16) | ((d[22] & 0xFF) << 8) | (d[23] & 0xFF);
            return new int[]{w, h};
        }
        // JPEG
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            int i = 2;
            while (i + 9 < d.length) {
                if ((d[i] & 0xFF) != 0xFF) { i++; continue; }
                int mk = d[i + 1] & 0xFF;
                if (mk == 0xFF) { i++; continue; }
                if (mk >= 0xC0 && mk <= 0xCF && mk != 0xC4 && mk != 0xC8 && mk != 0xCC) {
                    int h = ((d[i + 5] & 0xFF) << 8) | (d[i + 6] & 0xFF);
                    int w = ((d[i + 7] & 0xFF) << 8) | (d[i + 8] & 0xFF);
                    return new int[]{w, h};
                }
                if (mk == 0xD8 || mk == 0xD9 || (mk >= 0xD0 && mk <= 0xD7)) { i += 2; continue; }
                int len = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
                if (len < 2) break;
                i += 2 + len;
            }
        }
        return new int[]{0, 0};
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static byte[] be32(int v) {
        return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static void be32out(ByteArrayOutputStream o, int v) {
        o.write((v >> 24) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }

    private static byte[] syncSafe(int v) {
        return new byte[]{
                (byte) ((v >> 21) & 0x7F),
                (byte) ((v >> 14) & 0x7F),
                (byte) ((v >> 7) & 0x7F),
                (byte) (v & 0x7F)
        };
    }
}
