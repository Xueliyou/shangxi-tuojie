package com.whale.netease;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/** 极简 HTTP 客户端：POST 表单 / GET，返回 UTF-8 字符串。 */
public final class Http {

    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/91.0.4472.164 NeteaseMusicDesktop/2.10.2.200154";
    public static final String REFERER = "https://music.163.com/";

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 25000;

    private Http() {}

    private static HttpURLConnection open(String url, String method, String cookie, String contentType) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", REFERER);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        if (cookie != null && cookie.length() > 0) {
            conn.setRequestProperty("Cookie", cookie);
        }
        if (contentType != null) {
            conn.setRequestProperty("Content-Type", contentType);
        }
        return conn;
    }

    private static String readAll(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream raw = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (raw == null) {
            throw new IOException("HTTP " + code + " 无响应体");
        }
        String enc = conn.getContentEncoding();
        InputStream in = (enc != null && enc.toLowerCase().contains("gzip")) ? new GZIPInputStream(raw) : raw;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            conn.disconnect();
        }
    }

    /** 带重试的 POST 表单请求 */
    public static String postForm(String url, String body, String cookie) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = open(url, "POST", cookie, "application/x-www-form-urlencoded");
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.getBytes("UTF-8").length);
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
                os.close();
                return readAll(conn);
            } catch (IOException e) {
                last = e;
                if (conn != null) conn.disconnect();
                try { Thread.sleep(400L * (attempt + 1)); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("请求被中断", ie);
                }
            }
        }
        throw last != null ? last : new IOException("请求失败");
    }

    /** 带重试的 GET 请求 */
    public static String get(String url, String cookie) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = open(url, "GET", cookie, null);
                return readAll(conn);
            } catch (IOException e) {
                last = e;
                if (conn != null) conn.disconnect();
                try { Thread.sleep(400L * (attempt + 1)); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("请求被中断", ie);
                }
            }
        }
        throw last != null ? last : new IOException("请求失败");
    }

    /** 下载一个小文件到内存（用于封面图） */
    public static byte[] getBytes(String url, int maxSize) throws IOException {
        HttpURLConnection conn = open(url, "GET", null, null);
        try {
            int code = conn.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (maxSize > 0 && total > maxSize) throw new IOException("文件超过上限");
                bo.write(buf, 0, n);
            }
            in.close();
            return bo.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    /** 打开一个下载流（不自动重试，交给下载器处理） */
    public static HttpURLConnection openStream(String url, String cookie) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", REFERER);
        if (cookie != null && cookie.length() > 0) conn.setRequestProperty("Cookie", cookie);
        return conn;
    }

    /**
     * 只取文件开头的一小段（Range 请求），用于解析音频头。
     * 服务器若忽略 Range 直接返回 200，也会在读够 maxSize 后主动断开，
     * 不会把整首歌（几百 MB）拖下来。
     */
    public static byte[] getRange(String url, int maxSize) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", REFERER);
        conn.setRequestProperty("Range", "bytes=0-" + (maxSize - 1));
        InputStream in = null;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            int code = conn.getResponseCode();
            in = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return new byte[0];
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                if (total + n > maxSize) n = maxSize - total;
                if (n <= 0) break;
                bo.write(buf, 0, n);
                total += n;
                if (total >= maxSize) break;
            }
            return bo.toByteArray();
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            conn.disconnect();
        }
    }
}
