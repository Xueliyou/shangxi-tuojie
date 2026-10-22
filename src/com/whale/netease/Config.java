package com.whale.netease;

import android.content.Context;
import android.content.SharedPreferences;

/** 应用配置（Cookie / 默认音质 / 下载目录） */
public class Config {

    private static final String PREF = "netease_cfg";
    private static final String K_COOKIE = "cookie";
    private static final String K_LEVEL = "level";
    private static final String K_DIR = "dir";
    private static final String K_WEB = "webUrl";
    private static final String K_WEB_LAST = "webLastUrl";
    private static final String K_WEB_SCROLL = "webScrollY";
    private static final String K_WEB_STATE = "webState";
    private static final String K_DARK = "darkTheme";

    /**
     * 开源版本不内置任何账号 Cookie：首次使用请用「扫码登录」（推荐），
     * 或在设置里手动粘贴自己的 MUSIC_U。
     */
    public static final String DEFAULT_COOKIE = "";

    private final SharedPreferences sp;

    public Config(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String getCookie() {
        String c = sp.getString(K_COOKIE, "");
        if (c == null || c.trim().isEmpty()) return DEFAULT_COOKIE;
        return c;
    }

    public void setCookie(String c) {
        // 从浏览器 F12 复制出来的 Cookie 常带换行，必须清掉，否则请求头非法
        String v = c == null ? "" : c.trim().replaceAll("[\\r\\n]+", " ").replaceAll("\\s{2,}", " ");
        sp.edit().putString(K_COOKIE, v).apply();
    }

    public boolean isCustomCookie() {
        String c = sp.getString(K_COOKIE, "");
        return c != null && !c.trim().isEmpty();
    }

    /** 只取 MUSIC_U 的值，用于展示与校验 */
    public static String extractMusicU(String cookie) {
        if (cookie == null) return "";
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("MUSIC_U=")) return p.substring("MUSIC_U=".length());
        }
        return "";
    }

    public String getLevel() {
        return sp.getString(K_LEVEL, "exhigh");
    }

    public void setLevel(String lv) {
        sp.edit().putString(K_LEVEL, lv).apply();
    }

    /** 亮暗主题由前端决定，原生只记一份：冷启动时先把底色和系统栏定好，避免闪白 */
    public boolean isDarkTheme() {
        return sp.getBoolean(K_DARK, false);
    }

    public void setDarkTheme(boolean dark) {
        sp.edit().putBoolean(K_DARK, dark).apply();
    }

    public String getDir() {
        return sp.getString(K_DIR, "NeteaseMusic");
    }

    /** 内置网页版地址：由用户自己填写，App 不预置任何第三方站点 */
    public String getWebUrl() {
        return sp.getString(K_WEB, "");
    }

    public void setWebUrl(String u) {
        sp.edit().putString(K_WEB, u == null ? "" : u.trim()).apply();
    }

    /** 内置网页最后一次停留的页面（用于下次进来接着看） */
    public String getWebLastUrl() {
        return sp.getString(K_WEB_LAST, "");
    }

    public void setWebLastUrl(String u) {
        sp.edit().putString(K_WEB_LAST, u == null ? "" : u).apply();
    }

    /** 上次在页面里的滚动位置 */
    public int getWebScrollY() {
        return sp.getInt(K_WEB_SCROLL, 0);
    }

    public void setWebScrollY(int y) {
        sp.edit().putInt(K_WEB_SCROLL, y < 0 ? 0 : y).apply();
    }

    /** 内置网页的表单状态快照（原生侧兜底，防 localStorage 被清） */
    public String getWebState() {
        return sp.getString(K_WEB_STATE, "");
    }

    public void setWebState(String s) {
        sp.edit().putString(K_WEB_STATE, s == null ? "" : s).apply();
    }

    public void setDir(String d) {
        sp.edit().putString(K_DIR, d == null || d.trim().isEmpty() ? "NeteaseMusic" : d.trim()).apply();
    }
}
