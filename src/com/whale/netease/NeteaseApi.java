package com.whale.netease;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网易云音乐接口封装（Web / eapi）。
 * 接口地址与调用方式参考 Suxiaoqinx/Netease_url（MIT）。
 */
public class NeteaseApi {

    public static final String SONG_URL_V1 = "https://interface3.music.163.com/eapi/song/enhance/player/url/v1";
    public static final String SONG_DETAIL_V3 = "https://interface3.music.163.com/api/v3/song/detail";
    public static final String LYRIC_API = "https://interface3.music.163.com/api/song/lyric";
    public static final String SEARCH_API = "https://music.163.com/api/cloudsearch/pc";
    public static final String PLAYLIST_DETAIL_API = "https://music.163.com/api/v6/playlist/detail";
    public static final String ALBUM_DETAIL_API = "https://music.163.com/api/v1/album/";

    /** 音质等级（从高到低），用于自动降级 */
    public static final String[] LEVEL_CHAIN = {"jymaster", "hires", "lossless", "exhigh", "standard"};

    public static final String[] LEVEL_KEYS = {
            "standard", "exhigh", "lossless", "hires", "jyeffect", "sky", "jymaster", "dolby"
    };
    public static final String[] LEVEL_NAMES = {
            "标准 128K", "极高 320K", "无损 FLAC", "Hi-Res", "高清环绕声", "沉浸环绕声", "超清母带", "杜比全景声"
    };

    private final String cookie;

    public NeteaseApi(String cookie) {
        this.cookie = cookie == null ? "" : cookie;
    }

    public static String levelName(String key) {
        for (int i = 0; i < LEVEL_KEYS.length; i++) {
            if (LEVEL_KEYS[i].equals(key)) return LEVEL_NAMES[i];
        }
        return key;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    // ------------------------------------------------------------------ 搜索

    /** 搜索歌曲，返回精简后的 JSONArray */
    public JSONArray search(String keyword, int limit) throws Exception {
        String body = "s=" + enc(keyword) + "&type=1&limit=" + limit + "&offset=0";
        String resp = Http.postForm(SEARCH_API, body, cookie);
        JSONObject root = new JSONObject(resp);
        int code = root.optInt("code", -1);
        if (code != 200) {
            throw new Exception("搜索失败：" + root.optString("message", "code=" + code));
        }
        JSONArray songs = root.optJSONObject("result") == null
                ? new JSONArray()
                : root.getJSONObject("result").optJSONArray("songs");
        JSONArray out = new JSONArray();
        if (songs == null) return out;
        for (int i = 0; i < songs.length(); i++) {
            out.put(brief(songs.getJSONObject(i)));
        }
        return out;
    }

    /** 把原始歌曲对象精简成前端需要的字段 */
    private static JSONObject brief(JSONObject s) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", s.optLong("id"));
            o.put("name", s.optString("name"));
            // 歌手
            StringBuilder ar = new StringBuilder();
            JSONArray ars = s.optJSONArray("ar");
            if (ars == null) ars = s.optJSONArray("artists");
            if (ars != null) {
                for (int i = 0; i < ars.length(); i++) {
                    if (i > 0) ar.append('/');
                    ar.append(ars.getJSONObject(i).optString("name"));
                }
            }
            o.put("artists", ar.toString());
            // 专辑与封面
            JSONObject al = s.optJSONObject("al");
            if (al == null) al = s.optJSONObject("album");
            if (al != null) {
                o.put("album", al.optString("name"));
                String pic = al.optString("picUrl", "");
                if (pic.isEmpty()) {
                    long picId = al.optLong("pic", 0);
                    if (picId > 0) pic = picUrl(picId, 300);
                }
                o.put("picUrl", pic);
            } else {
                o.put("album", "");
                o.put("picUrl", "");
            }
            long dt = s.optLong("dt", s.optLong("duration", 0));
            o.put("duration", dt);
            o.put("fee", s.optInt("fee", 0));
            JSONObject pr = s.optJSONObject("privilege");
            if (pr != null) {
                String pl = pr.optString("plLevel", "");
                o.put("plLevel", pl);
                o.put("maxbr", pr.optInt("maxbr", 0));
                o.put("dlLevel", pr.optString("dlLevel", ""));
                if (pr.has("fl")) o.put("br", pr.optInt("fl", 0));
            }
        } catch (Exception ignored) {}
        return o;
    }

    /** 网易云封面图 URL 加密算法（参考原项目 netease_encrypt_id） */
    public static String picUrl(long picId, int size) {
        String encId = encryptPicId(picId);
        return "https://p3.music.126.net/" + encId + "/" + picId + ".jpg?param=" + size + "y" + size;
    }

    private static final String MAGIC = "3go8&$8*3*3h0k(2)2";

    private static String encryptPicId(long id) {
        String idStr = String.valueOf(id);
        byte[] magic = MAGIC.getBytes();
        byte[] xored = new byte[idStr.length()];
        for (int i = 0; i < idStr.length(); i++) {
            xored[i] = (byte) (idStr.charAt(i) ^ magic[i % magic.length]);
        }
        try {
            byte[] md5 = java.security.MessageDigest.getInstance("MD5").digest(xored);
            String b64 = android.util.Base64.encodeToString(md5, android.util.Base64.NO_WRAP);
            b64 = b64.replace('/', '_').replace('+', '-');
            return b64;
        } catch (Exception e) {
            return idStr;
        }
    }

    // ------------------------------------------------------------------ 详情

    public JSONObject songDetail(long id) throws Exception {
        String body = "c=" + enc("[{\"id\":" + id + "}]");
        String resp = Http.postForm(SONG_DETAIL_V3, body, cookie);
        JSONObject root = new JSONObject(resp);
        JSONArray songs = root.optJSONArray("songs");
        if (songs == null || songs.length() == 0) {
            throw new Exception("没有找到这首歌（ID: " + id + "）");
        }
        return brief(songs.getJSONObject(0));
    }

    // ------------------------------------------------------------ 播放地址

    /**
     * 伪装成 App 客户端的设备信息。
     * 网易云只有在请求"看起来来自 App"时才下发高档音质，
     * 否则超清母带 / Hi-Res 会被静默降级成无损 FLAC。
     */
    private static final String APP_VER = "8.10.42";
    private static final String OS_VER = "14";
    private static final String DEVICE_ID = "7f3a9c2e5b1d4f8a9c6e2b5d8f1a4c7e";

    /** 给 Cookie 补上设备字段；用户自己填了 deviceId 就不覆盖 */
    private String audioCookie() {
        if (cookie == null || cookie.trim().isEmpty()) return cookie;
        if (cookie.contains("deviceId=")) return cookie;
        return cookie.trim()
                + "; os=android; appver=" + APP_VER
                + "; osver=" + OS_VER
                + "; deviceId=" + DEVICE_ID;
    }

    /** 原始请求：指定音质取播放地址 */
    private JSONObject rawSongUrl(long id, String level) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("ids", new JSONArray().put(id));
        payload.put("level", level);
        payload.put("encodeType", "dolby".equals(level) ? "mp4" : "flac");
        JSONObject header = new JSONObject();
        header.put("os", "android");
        header.put("appver", APP_VER);
        header.put("osver", OS_VER);
        header.put("deviceId", DEVICE_ID);
        header.put("requestId", String.valueOf(System.currentTimeMillis()));
        payload.put("header", header.toString());
        if ("sky".equals(level)) payload.put("immerseType", "c51");

        String params = Eapi.encrypt(SONG_URL_V1, payload.toString());
        String resp = Http.postForm(SONG_URL_V1, "params=" + enc(params), audioCookie());
        JSONObject root = new JSONObject(resp);
        if (root.optInt("code", -1) != 200) {
            throw new Exception("解析失败：" + root.optString("message", "code=" + root.optInt("code", -1)));
        }
        JSONArray data = root.optJSONArray("data");
        if (data == null || data.length() == 0) throw new Exception("接口未返回数据");
        return data.getJSONObject(0);
    }

    /**
     * 取播放地址，失败时按音质链自动降级。
     * 返回对象里带上 requested / actual 音质，便于前端提示"已降级"。
     */
    public JSONObject songUrl(long id, String level) throws Exception {
        List<String> chain = new ArrayList<>();
        chain.add(level);
        boolean found = false;
        for (String lv : LEVEL_CHAIN) {
            if (lv.equals(level)) { found = true; continue; }
            if (found) chain.add(lv);
        }
        if (!found) for (String lv : LEVEL_CHAIN) if (!chain.contains(lv)) chain.add(lv);

        JSONObject last = null;
        String lastErr = null;
        for (String lv : chain) {
            JSONObject d;
            try {
                d = rawSongUrl(id, lv);
            } catch (Exception e) {
                lastErr = e.getMessage();
                continue;
            }
            last = d;
            String url = d.optString("url", "");
            if (url != null && url.length() > 0 && !"null".equals(url)) {
                // 关键：网易云会按账号权限自动回落音质，响应里的 level 才是真实等级。
                // 例：请求 lossless、普通账号只会拿到 320k mp3，此时 level 返回 exhigh。
                String actual = d.optString("level", "").trim();
                if (actual.isEmpty() || "none".equals(actual)) actual = lv;
                JSONObject o = new JSONObject();
                o.put("id", id);
                // 网易云 CDN 同时支持 https：优先走 https，可绕开 Android 9+ 的明文流量限制
                o.put("url", url.startsWith("http://") ? "https://" + url.substring(7) : url);
                o.put("urlHttp", url);          // 保留原始地址做兜底

                o.put("br", d.optInt("br", 0));
                o.put("sr", d.optInt("sr", 0));
                o.put("size", d.optLong("size", 0));
                o.put("type", d.optString("type", "mp3"));
                o.put("md5", d.optString("md5", ""));
                o.put("requested", level);
                o.put("requestedName", levelName(level));
                o.put("actual", actual);
                o.put("levelName", levelName(actual));
                o.put("degraded", !actual.equals(level));
                o.put("fee", d.optInt("fee", 0));
                return o;
            }
            int code = d.optInt("code", 0);
            if (code == 200 && (url == null || url.isEmpty() || "null".equals(url))) {
                lastErr = "当前账号无此音质权限或该歌曲无版权";
            }
        }
        if (last != null) {
            int code = last.optInt("code", -1);
            if (code != 200) throw new Exception("解析失败（code=" + code + "）");
        }
        throw new Exception(lastErr != null ? lastErr : "无法获取播放地址");
    }

    /**
     * 批量探测每首歌当前账号能拿到的最高音质。
     * 直接按「超清母带」档请求，服务端会按账号权益返回实际等级：
     * SVIP 且有母带版权 → jymaster；否则依次回落（hires / lossless / exhigh）。
     * 一次最多带 50 首，返回原始 data 数组（含 id / level / br / sr / url / fee）。
     */
    public JSONArray probeLevels(long[] ids) throws Exception {
        JSONArray out = new JSONArray();
        if (ids == null || ids.length == 0) return out;
        for (int start = 0; start < ids.length; start += 50) {
            int end = Math.min(start + 50, ids.length);
            JSONArray batch = new JSONArray();
            for (int i = start; i < end; i++) batch.put(ids[i]);

            JSONObject payload = new JSONObject();
            payload.put("ids", batch);
            payload.put("level", "jymaster");
            payload.put("encodeType", "flac");
            JSONObject header = new JSONObject();
            header.put("os", "android");
            header.put("appver", APP_VER);
            header.put("osver", OS_VER);
            header.put("deviceId", DEVICE_ID);
            header.put("requestId", String.valueOf(System.currentTimeMillis()));
            payload.put("header", header.toString());

            String params = Eapi.encrypt(SONG_URL_V1, payload.toString());
            String resp = Http.postForm(SONG_URL_V1, "params=" + enc(params), audioCookie());
            try {
                JSONObject root = new JSONObject(resp);
                JSONArray data = root.optJSONArray("data");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) out.put(data.getJSONObject(i));
                }
            } catch (Exception ignored) {
                // 单批失败不影响整体，剩下的批次继续
            }
        }
        return out;
    }

    /**
     * 读音频文件头，解析真实的采样率 / 声道数 / 位深。
     * 网易云接口只给 sr/br/size，声道数和位深得从文件本身读：
     * FLAC 看 STREAMINFO 块，MP3 看第一帧帧头。
     */
    public JSONObject audioHeader(String url) throws Exception {
        JSONObject o = new JSONObject();
        if (url == null || url.isEmpty()) return o;
        byte[] head = Http.getRange(url, 131072);
        if (head == null || head.length < 4) return o;

        // ---------- FLAC ----------
        if (head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
            o.put("format", "FLAC");
            int pos = 4;
            while (pos + 4 <= head.length) {
                int type = head[pos] & 0x7F;
                int len = ((head[pos + 1] & 0xFF) << 16) | ((head[pos + 2] & 0xFF) << 8) | (head[pos + 3] & 0xFF);
                int d = pos + 4;
                if (type == 0 && d + 18 <= head.length) {   // STREAMINFO
                    long b = 0;
                    for (int i = 0; i < 8; i++) b = (b << 8) | (head[d + 10 + i] & 0xFF);
                    int sampleRate = (int) ((b >> 44) & 0xFFFFF);
                    int channels = (int) ((b >> 41) & 0x7) + 1;
                    int bits = (int) ((b >> 36) & 0x1F) + 1;
                    long totalSamples = b & 0xFFFFFFFFFL;
                    if (sampleRate > 0) {
                        o.put("sampleRate", sampleRate);
                        o.put("duration", totalSamples * 1000L / sampleRate);
                    }
                    o.put("channels", channels);
                    o.put("bits", bits);
                    break;
                }
                pos = d + len;
                if (type == 0 || pos <= d) break;
            }
            return o;
        }

        // ---------- MP3 ----------
        int limit = Math.min(head.length, 8192);
        for (int i = 0; i + 4 <= limit; i++) {
            if ((head[i] & 0xFF) != 0xFF || (head[i + 1] & 0xE0) != 0xE0) continue;
            int h1 = head[i + 1] & 0xFF;
            int h2 = head[i + 2] & 0xFF;
            int h3 = head[i + 3] & 0xFF;
            int verBits = (h1 >> 3) & 0x3;    // 3=MPEG1 2=MPEG2 0=MPEG2.5 1=保留
            int layerBits = (h1 >> 1) & 0x3;  // 1=Layer3
            if (verBits == 1 || layerBits == 0) continue;
            int brIdx = (h2 >> 4) & 0xF;
            int srIdx = (h2 >> 2) & 0x3;
            if (brIdx == 0 || brIdx == 15 || srIdx == 3) continue;
            int[] sr1 = {44100, 48000, 32000};
            int[] sr2 = {22050, 24000, 16000};
            int[] sr25 = {11025, 12000, 8000};
            int sr = verBits == 3 ? sr1[srIdx] : (verBits == 2 ? sr2[srIdx] : sr25[srIdx]);
            int[] br1 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
            int[] br2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0};
            int br = (verBits == 3 ? br1[brIdx] : br2[brIdx]) * 1000;
            int chMode = (h3 >> 6) & 0x3;
            o.put("format", "MP3");
            o.put("sampleRate", sr);
            o.put("channels", chMode == 3 ? 1 : 2);
            o.put("bits", 16);
            if (br > 0) o.put("bitrate", br);
            break;
        }
        return o;
    }

    // ------------------------------------------------------------------ 歌词

    public JSONObject lyric(long id) throws Exception {
        // lv 是「歌词版本」：不少歌（新歌 / 非官方上传）的歌词不在版本 1，
        // 用 lv=1 会返回空，界面就成了「暂无歌词」。
        // 先请求 lv=-1（服务端可用的默认版本），仍为空再退回 lv=1 兜底。
        JSONObject root = requestLyric(id, -1);
        if (lyricText(root, "lrc").isEmpty()) {
            root = requestLyric(id, 1);
        }
        JSONObject out = new JSONObject();
        String lrc = lyricText(root, "lrc");
        String tlyric = lyricText(root, "tlyric");
        String romalrc = lyricText(root, "romalrc");
        // 逐字歌词（yrc）：有就给前端做卡拉OK式高亮
        String yrcRaw = lyricText(root, "yrc");
        // 没有任何正文歌词（只剩作词/作曲这类元信息）时，也去拿逐字歌词
        boolean noRealLyric = realLyricLines(lrc) == 0;
        if (yrcRaw.isEmpty() && (lrc.isEmpty() || noRealLyric)) {
            JSONObject full = requestLyricFull(id);
            yrcRaw = lyricText(full, "yrc");
            if (yrcRaw.isEmpty()) yrcRaw = lyricText(full, "klyric");
            if ((lrc.isEmpty() || noRealLyric) && !yrcRaw.isEmpty()) {
                String converted = yrcToLrc(yrcRaw);
                if (realLyricLines(converted) > 0) lrc = converted;
            }
            if (tlyric.isEmpty()) tlyric = lyricText(full, "tlyric");
        }
        // 没有正文 或 纯文本（无时间轴）：借用同名/相似歌曲的带轴歌词
        if (realLyricLines(lrc) == 0 || !hasTimestamp(lrc)) {
            String alt = similarLyric(id, lrc);
            if (alt != null && realLyricLines(alt) > 0) lrc = alt;
        }
        // 极端情况：lrc 为空但只有翻译歌词（用户上传），用翻译兜底
        if (lrc.isEmpty() && !tlyric.isEmpty()) lrc = tlyric;
        // 纯音乐 / 无歌词标记，前端据此显示「纯音乐，请欣赏」而不是「暂无歌词」
        boolean pure = root.optBoolean("pureMusic", false) || root.optBoolean("nolyric", false);
        out.put("pure", pure);
        out.put("lyric", lrc);
        out.put("yrc", yrcRaw);
        out.put("tlyric", tlyric);
        out.put("romalrc", romalrc);
        return out;
    }

    /** 带逐字歌词（yrc）的完整请求 */
    private JSONObject requestLyricFull(long id) throws Exception {
        String body = "id=" + id + "&lv=-1&kv=-1&tv=-1&rv=-1&yv=1&ytv=-1&yrv=-1";
        return new JSONObject(Http.postForm(LYRIC_API, body, cookie));
    }

    /**
     * 统计"真正的歌词行"数量：排除作词/作曲/编曲等元信息行。
     * 有些歌的接口只返回这些元信息，没有任何正文。
     */
    private static int realLyricLines(String lrc) {
        if (lrc == null || lrc.isEmpty()) return 0;
        int cnt = 0;
        for (String line : lrc.split("\n")) {
            String t = line.replaceAll("^\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?(?:-\\d+)?\\]", "").trim();
            if (t.isEmpty()) continue;
            if (t.matches("^(作词|作曲|编曲|制作人|混音|母带|和声|监制|出品|演唱|原唱|词|曲|吉他|贝斯|鼓|键盘|弦乐|录音|配唱|人声|后期|封面|设计)\\s*[:：].*")) continue;
            if (t.matches("^(Lyrics|Composed|Produced|Arranged|Written|Music|Mixed|Mastered|Performed)\\s+by\\b.*")) continue;
            if (t.matches("^(作词|作曲|编曲|制作人)\\s*$")) continue;
            cnt++;
        }
        return cnt;
    }

    /** 是否含标准时间戳 */
    private static boolean hasTimestamp(String lrc) {
        if (lrc == null || lrc.isEmpty()) return false;
        return java.util.regex.Pattern.compile("\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?(?:-\\d+)?\\]")
                .matcher(lrc).find();
    }

    /** 去掉时间戳后按行取集合，用于相似度比较 */
    private static java.util.Set<String> lyricLines(String lrc) {
        java.util.Set<String> set = new java.util.HashSet<String>();
        if (lrc == null) return set;
        for (String line : lrc.split("\n")) {
            String t = line.replaceAll("^\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?(?:-\\d+)?\\]", "").trim().toLowerCase();
            if (t.length() >= 2) set.add(t);
        }
        return set;
    }

    /** Jaccard 相似度 */
    private static double similarity(String a, String b) {
        java.util.Set<String> sa = lyricLines(a);
        java.util.Set<String> sb = lyricLines(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0;
        java.util.Set<String> inter = new java.util.HashSet<String>(sa);
        inter.retainAll(sb);
        java.util.Set<String> union = new java.util.HashSet<String>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    /**
     * 纯文本歌词没有时间轴时，搜索同名歌曲，找一份文本高度相似且带时间轴的歌词。
     * 相似度门槛 0.55，避免借到翻唱/改编版完全不同的词。
     */
    private String similarLyric(long id, String plain) {
        try {
            String name = songDetail(id).optString("name", "");
            if (name.isEmpty()) return null;
            JSONArray arr = search(name, 4);
            String best = null;
            double bestScore = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.getJSONObject(i);
                long sid = s.optLong("id", 0);
                if (sid == 0 || sid == id) continue;
                try {
                    String l = lyricText(requestLyric(sid, -1), "lrc");
                    if (!hasTimestamp(l)) continue;
                    double sc = similarity(plain, l);
                    if (sc > bestScore) { bestScore = sc; best = l; }
                } catch (Exception ignored) {}
            }
            return (best != null && bestScore >= 0.55) ? best : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 把逐字歌词转成标准 LRC。
     * 逐字格式：[起始ms,时长ms](偏移,时长,标志)字(偏移,时长,标志)字…
     * 转换后：[mm:ss.xx]整行文字
     */
    private static String yrcToLrc(String src) {
        if (src == null || src.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String raw : src.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) != '[') continue;
            int close = line.indexOf(']');
            if (close <= 1) continue;
            String head = line.substring(1, close);
            int comma = head.indexOf(',');
            if (comma <= 0) continue;
            long start;
            try {
                start = Long.parseLong(head.substring(0, comma).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            String body = line.substring(close + 1);
            StringBuilder text = new StringBuilder();
            int i = 0;
            while (i < body.length()) {
                char c = body.charAt(i);
                if (c == '(') {
                    int end = body.indexOf(')', i);
                    if (end < 0) break;
                    i = end + 1;          // 跳过 (偏移,时长,标志) 时间标签
                } else {
                    text.append(c);
                    i++;
                }
            }
            String t = text.toString().trim();
            if (t.isEmpty()) continue;
            long sec = start / 1000, ms = start % 1000;
            sb.append(String.format("[%02d:%02d.%02d]%s\n", sec / 60, sec % 60, ms / 10, t));
        }
        return sb.toString();
    }

    private JSONObject requestLyric(long id, int lv) throws Exception {
        String body = "id=" + id + "&lv=" + lv + "&kv=1&tv=-1&rv=1";
        return new JSONObject(Http.postForm(LYRIC_API, body, cookie));
    }

    private static String lyricText(JSONObject root, String key) {
        JSONObject o = root.optJSONObject(key);
        return o == null ? "" : o.optString("lyric", "");
    }

    /** 只取歌单前几首（首页榜单展示前三名用，避免拉整榜） */
    public JSONArray playlistTop(long id, int limit) throws Exception {
        String body = "id=" + id + "&n=" + Math.max(limit, 5);
        String resp = Http.postForm(PLAYLIST_DETAIL_API, body, cookie);
        JSONObject root = new JSONObject(resp);
        JSONArray out = new JSONArray();
        JSONObject pl = root.optJSONObject("playlist");
        if (pl == null) return out;
        JSONArray tracks = pl.optJSONArray("tracks");
        if (tracks == null) return out;
        for (int i = 0; i < tracks.length() && i < limit; i++) {
            out.put(brief(tracks.getJSONObject(i)));
        }
        return out;
    }

    // ------------------------------------------------------------------ 歌单

    public JSONObject playlist(long id) throws Exception {
        String body = "id=" + id + "&n=1000&s=8";
        String resp = Http.postForm(PLAYLIST_DETAIL_API, body, cookie);
        JSONObject root = new JSONObject(resp);
        if (root.optInt("code", -1) != 200) {
            throw new Exception("歌单获取失败：code=" + root.optInt("code", -1));
        }
        JSONObject pl = root.optJSONObject("playlist");
        if (pl == null) throw new Exception("歌单不存在");
        JSONObject out = new JSONObject();
        out.put("id", pl.optLong("id"));
        out.put("name", pl.optString("name"));
        out.put("coverImgUrl", pl.optString("coverImgUrl", ""));
        JSONObject creator = pl.optJSONObject("creator");
        out.put("creator", creator == null ? "" : creator.optString("nickname", ""));
        out.put("trackCount", pl.optInt("trackCount", 0));
        out.put("description", pl.optString("description", ""));
        out.put("playCount", pl.optLong("playCount", 0));

        // 收集全部 trackId，再分批换取歌曲详情
        List<Long> ids = new ArrayList<>();
        JSONArray tids = pl.optJSONArray("trackIds");
        if (tids != null) {
            for (int i = 0; i < tids.length(); i++) {
                ids.add(tids.getJSONObject(i).optLong("id"));
            }
        }
        // 部分歌单直接带 tracks
        JSONArray tracks = pl.optJSONArray("tracks");
        if (ids.isEmpty() && tracks != null) {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < tracks.length(); i++) arr.put(brief(tracks.getJSONObject(i)));
            out.put("tracks", arr);
            return out;
        }
        out.put("tracks", songDetailBatch(ids));
        return out;
    }

    /** 批量取歌曲详情（每批 100 条） */
    public JSONArray songDetailBatch(List<Long> ids) throws Exception {
        JSONArray out = new JSONArray();
        if (ids.isEmpty()) return out;
        for (int i = 0; i < ids.size(); i += 100) {
            int end = Math.min(i + 100, ids.size());
            List<Long> batch = ids.subList(i, end);
            StringBuilder sb = new StringBuilder("[");
            for (int k = 0; k < batch.size(); k++) {
                if (k > 0) sb.append(',');
                sb.append("{\"id\":").append(batch.get(k)).append("}");
            }
            sb.append(']');
            String resp = Http.postForm(SONG_DETAIL_V3, "c=" + enc(sb.toString()), cookie);
            JSONObject root = new JSONObject(resp);
            JSONArray songs = root.optJSONArray("songs");
            if (songs == null) continue;
            Map<Long, JSONObject> map = new LinkedHashMap<>();
            for (int k = 0; k < songs.length(); k++) {
                JSONObject s = songs.getJSONObject(k);
                map.put(s.optLong("id"), brief(s));
            }
            // 保持歌单原始顺序
            for (Long t : batch) {
                JSONObject s = map.get(t);
                if (s != null) out.put(s);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 专辑

    public JSONObject album(long id) throws Exception {
        String resp = Http.get(ALBUM_DETAIL_API + id, cookie);
        JSONObject root = new JSONObject(resp);
        if (root.optInt("code", -1) != 200) {
            throw new Exception("专辑获取失败：code=" + root.optInt("code", -1));
        }
        JSONObject al = root.optJSONObject("album");
        if (al == null) throw new Exception("专辑不存在");
        JSONObject out = new JSONObject();
        out.put("id", al.optLong("id"));
        out.put("name", al.optString("name"));
        out.put("coverImgUrl", al.optString("picUrl", picUrl(al.optLong("pic", 0), 300)));
        JSONObject artist = al.optJSONObject("artist");
        out.put("artist", artist == null ? "" : artist.optString("name", ""));
        out.put("description", al.optString("description", ""));
        out.put("size", al.optInt("size", 0));
        JSONArray songs = root.optJSONArray("songs");
        JSONArray arr = new JSONArray();
        if (songs != null) {
            for (int i = 0; i < songs.length(); i++) arr.put(brief(songs.getJSONObject(i)));
        }
        out.put("tracks", arr);
        return out;
    }

    // ------------------------------------------------------------ 链接解析

    public static class Link {
        public String type;   // song / playlist / album
        public long id;
    }

    /** 从用户输入里解析出类型与 ID：支持纯 ID 与各种网易云分享链接 */
    public static Link parseLink(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        Link l = new Link();
        String lower = s.toLowerCase();

        String type = null;
        if (lower.contains("/song") || lower.contains("song?id") || lower.contains("music.163.com/song")) {
            type = "song";
        } else if (lower.contains("playlist") || lower.contains("歌单")) {
            type = "playlist";
        } else if (lower.contains("album") || lower.contains("专辑")) {
            type = "album";
        }

        // 优先按参数名抓 id
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:id=|/)(\\d{4,})").matcher(lower);
        long found = 0;
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            if (v > 999) { found = v; break; }
        }
        if (found == 0) {
            m = java.util.regex.Pattern.compile("(\\d{4,})").matcher(lower);
            if (m.find()) found = Long.parseLong(m.group(1));
        }
        if (found == 0) return null;
        l.id = found;
        l.type = type != null ? type : "song";
        return l;
    }
}
