package com.whale.netease;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 网易云手机验证码登录。
 * 优先走 eapi（App 端通道，风控通常更宽松），失败再回退 weapi。
 */
public final class NeteaseLogin {

    private static final String SMS_EAPI = "https://music.163.com/eapi/sms/captcha/sent";
    private static final String LOGIN_EAPI = "https://music.163.com/eapi/login/cellphone";
    private static final String SMS_WEAPI = "https://music.163.com/weapi/sms/captcha/sent";
    private static final String LOGIN_WEAPI = "https://music.163.com/weapi/login/cellphone";

    private NeteaseLogin() {}

    /** 生成扫码登录的 unikey；weapi 失败时回退到 api 明文接口 */
    public static String qrCreate() throws Exception {
        JSONObject data = new JSONObject();
        data.put("type", 1);
        String[] enc = Weapi.encrypt(data);
        try {
            String r = post("https://music.163.com/weapi/login/qrcode/unikey",
                    "params=" + enc(enc[0]) + "&encSecKey=" + enc(enc[1]), "")[0];
            if (r != null && r.contains("unikey")) return r;
        } catch (Exception ignored) {}
        try {
            String r2 = post("https://music.163.com/api/login/qrcode/unikey", "type=1", "")[0];
            if (r2 != null && r2.contains("unikey")) return r2;
        } catch (Exception ignored) {}
        // 最后再试一次 GET 形式
        return post("https://music.163.com/api/login/qrcode/unikey", "type=1", "")[0];
    }

    /** 轮询扫码状态，返回 {body, cookie}（803 成功时带 cookie） */
    public static String[] qrCheck(String key) throws Exception {
        JSONObject data = new JSONObject();
        data.put("key", key);
        data.put("type", 1);
        String[] enc = Weapi.encrypt(data);
        return post("https://music.163.com/weapi/login/qrcode/client/login",
                "params=" + enc(enc[0]) + "&encSecKey=" + enc(enc[1]), "");
    }

    /** 发送短信验证码，返回接口原始响应 */
    public static String sendCode(String phone) throws Exception {
        JSONObject data = new JSONObject();
        data.put("cellphone", phone);
        data.put("ctcode", "86");
        data.put("header", headerJson());
        try {
            String params = Eapi.encrypt(SMS_EAPI, data.toString());
            return post(SMS_EAPI, "params=" + enc(params), "")[0];
        } catch (Exception e) {
            // 回退 weapi
            JSONObject w = new JSONObject();
            w.put("cellphone", phone);
            w.put("ctcode", "86");
            String[] enc2 = Weapi.encrypt(w);
            return post(SMS_WEAPI, "params=" + enc(enc2[0]) + "&encSecKey=" + enc(enc2[1]), "")[0];
        }
    }

    /** 用验证码登录，返回 {body, cookie}；eapi 失败回退 weapi */
    public static String[] login(String phone, String code) throws Exception {
        JSONObject data = new JSONObject();
        data.put("phone", phone);
        data.put("countrycode", "86");
        data.put("captcha", code);
        data.put("rememberLogin", "true");
        data.put("header", headerJson());
        try {
            String params = Eapi.encrypt(LOGIN_EAPI, data.toString());
            String[] r = post(LOGIN_EAPI, "params=" + enc(params), "");
            if (hasMusicU(r[1])) return r;
            // eapi 没拿到 cookie，再试 weapi
        } catch (Exception ignored) {}

        JSONObject w = new JSONObject();
        w.put("phone", phone);
        w.put("countrycode", "86");
        w.put("captcha", code);
        w.put("rememberLogin", "true");
        String[] enc2 = Weapi.encrypt(w);
        return post(LOGIN_WEAPI, "params=" + enc(enc2[0]) + "&encSecKey=" + enc(enc2[1]), "");
    }

    private static boolean hasMusicU(String cookie) {
        return cookie != null && cookie.contains("MUSIC_U=");
    }

    private static String headerJson() {
        JSONObject h = new JSONObject();
        try {
            h.put("os", "pc");
            h.put("appver", "8.9.75");
            h.put("osver", "");
            h.put("deviceId", "pyncm!");
            h.put("requestId", String.valueOf(System.currentTimeMillis()));
        } catch (Exception ignored) {}
        return h.toString();
    }

    private static String[] post(String url, String body, String cookie) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", Http.UA);
        conn.setRequestProperty("Referer", "https://music.163.com/");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);

        try {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(data.length);
            OutputStream out = conn.getOutputStream();
            out.write(data);
            out.flush();
            out.close();

            int code = conn.getResponseCode();

            // 先收 Set-Cookie（必须在读 body / disconnect 之前）
            StringBuilder cb = new StringBuilder();
            Map<String, List<String>> headers = conn.getHeaderFields();
            if (headers != null) {
                for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                    if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Set-Cookie")) continue;
                    for (String v : e.getValue()) {
                        String kv = v.split(";")[0].trim();
                        if (kv.isEmpty()) continue;
                        if (cb.length() > 0) cb.append("; ");
                        cb.append(kv);
                    }
                }
            }

            InputStream raw = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String resp = "";
            if (raw != null) {
                String enc = conn.getContentEncoding();
                InputStream in = (enc != null && enc.toLowerCase().contains("gzip"))
                        ? new java.util.zip.GZIPInputStream(raw) : raw;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                resp = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
            return new String[]{resp, cb.toString()};
        } finally {
            conn.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
