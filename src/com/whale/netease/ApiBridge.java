package com.whale.netease;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 暴露给网页前端的原生接口。
 *
 * <p>这一层只做四件事：<b>路由</b>、<b>参数解析</b>、<b>线程切换</b>、<b>回调</b>。
 * 具体业务已经拆出去：
 * <ul>
 *   <li>{@link DownloadCenter} —— 下载任务（线程池 / 前台服务 / 标签准备）</li>
 *   <li>{@link ProgressMux} —— 下载进度节流</li>
 *   <li>{@link Apps} —— 其它 App 的安装判断 / 图标 / 启动 / 打开链接</li>
 *   <li>{@link NeteaseApi} / {@link NeteaseLogin} —— 网易云接口与登录</li>
 * </ul>
 * 以前这里是 600 多行 if-else 大杂烩，加一个方法要翻半个文件。
 */
public class ApiBridge {

    /** 路由：方法名 → 处理函数 */
    private interface Route {
        String call(JSONObject p) throws Exception;
    }

    /** 用来探测账号音质上限的歌曲（本身带无损 / Hi-Res 版权） */
    private static final long PROBE_SONG_ID = 1330348068L;

    private final Activity activity;
    private final WebView webView;
    private final Config config;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Map<String, Route> routes = new HashMap<String, Route>();

    private final ProgressMux progress;
    private final DownloadCenter downloads;
    private MediaNotifier media;

    public ApiBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.config = new Config(activity);
        this.progress = new ProgressMux(new ProgressMux.Emitter() {
            @Override public void emit(String js) { eval(js); }
        });
        this.downloads = new DownloadCenter(activity, config, new DownloadCenter.Sink() {
            @Override public void onProgress(String taskId, int state, int percent, long done,
                                             long total, String message, String path,
                                             String name, String ext) {
                progress.push(taskId, state, percent, done, total, message, path, name, ext);
            }
        });
        registerRoutes();
    }

    // ------------------------------------------------------------- JS 调用

    /** 统一入口：method + JSON 参数 + 回调 id。所有方法都在后台线程执行 */
    @JavascriptInterface
    public void call(final String method, final String paramsJson, final String callbackId) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject p = new JSONObject(
                            paramsJson == null || paramsJson.isEmpty() ? "{}" : paramsJson);
                    Route route = routes.get(method);
                    if (route == null) throw new Exception("未知方法：" + method);
                    String out = route.call(p);
                    callback(callbackId, true, out == null ? "null" : out);
                } catch (Throwable t) {
                    callback(callbackId, false, errorText(t));
                }
            }
        });
    }

    private void registerRoutes() {
        // 网易云接口
        routes.put("search", this::search);
        routes.put("song", this::song);
        routes.put("url", this::songUrl);
        routes.put("probe", this::probe);
        routes.put("toplist", this::toplist);
        routes.put("audioInfo", this::audioInfo);
        routes.put("lyric", this::lyric);
        routes.put("playlist", this::playlist);
        routes.put("album", this::album);
        routes.put("parse", this::parse);

        // 外挂歌词（.lrc）写文件需要「所有文件访问」；这两个接口供设置页查询/申请
        routes.put("lrcPerm", this::lrcPerm);
        routes.put("requestLrcPerm", this::requestLrcPerm);
        // 配置 / 账号
        routes.put("getConfig", this::getConfig);
        routes.put("setConfig", this::setConfig);
        routes.put("testCookie", this::testCookie);
        routes.put("levels", this::levels);
        routes.put("qrCreate", this::qrCreate);
        routes.put("qrCheck", this::qrCheck);
        routes.put("sendLoginCode", this::sendLoginCode);
        routes.put("loginByCode", this::loginByCode);
        // 下载
        routes.put("download", this::download);
        routes.put("cancel", this::cancel);
        // 系统 / 其它 App
        routes.put("getShare", this::getShare);
        routes.put("openApp", this::openApp);
        routes.put("openUrl", this::openUrl);
        routes.put("isInstalled", this::isInstalled);
        routes.put("appIcon", this::appIcon);
        routes.put("copyText", this::copyText);
        routes.put("setBars", this::setBars);
        routes.put("mediaUpdate", this::mediaUpdate);
        routes.put("mediaStop", this::mediaStop);
        routes.put("toast", this::toast);
        routes.put("exit", this::exit);
    }

    // ------------------------------------------------------------------ 接口

    private String search(JSONObject p) throws Exception {
        return newApi().search(p.optString("kw"), p.optInt("limit", 100)).toString();
    }

    private String song(JSONObject p) throws Exception {
        return newApi().songDetail(p.optLong("id")).toString();
    }

    private String songUrl(JSONObject p) throws Exception {
        return newApi().songUrl(p.optLong("id"), p.optString("level", config.getLevel())).toString();
    }

    private String probe(JSONObject p) throws Exception {
        JSONArray arr = p.optJSONArray("ids");
        if (arr == null || arr.length() == 0) return "[]";
        long[] ids = new long[arr.length()];
        for (int i = 0; i < arr.length(); i++) ids[i] = arr.optLong(i);
        return newApi().probeLevels(ids).toString();
    }

    private String toplist(JSONObject p) throws Exception {
        return newApi().playlistTop(p.optLong("id"), p.optInt("limit", 3)).toString();
    }

    private String audioInfo(JSONObject p) throws Exception {
        return newApi().audioHeader(p.optString("url", "")).toString();
    }

    private String lyric(JSONObject p) throws Exception {
        return newApi().lyric(p.optLong("id")).toString();
    }

    private String playlist(JSONObject p) throws Exception {
        return newApi().playlist(p.optLong("id")).toString();
    }

    private String album(JSONObject p) throws Exception {
        return newApi().album(p.optLong("id")).toString();
    }

    private String parse(JSONObject p) throws Exception {
        NeteaseApi.Link l = NeteaseApi.parseLink(p.optString("text"));
        if (l == null) throw new Exception("没有识别出链接里的歌曲/歌单/专辑 ID");
        JSONObject o = new JSONObject();
        o.put("type", l.type);
        o.put("id", l.id);
        return o.toString();
    }

    // ------------------------------------------------------------------ 配置

    private String getConfig(JSONObject p) throws Exception {
        return configJson().toString();
    }

    private String setConfig(JSONObject p) throws Exception {
        if (p.has("cookie")) config.setCookie(p.optString("cookie"));
        if (p.has("level")) config.setLevel(p.optString("level"));
        if (p.has("dir")) config.setDir(p.optString("dir"));
        return configJson().toString();
    }

    private String levels(JSONObject p) throws Exception {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < NeteaseApi.LEVEL_KEYS.length; i++) {
            JSONObject o = new JSONObject();
            o.put("key", NeteaseApi.LEVEL_KEYS[i]);
            o.put("name", NeteaseApi.LEVEL_NAMES[i]);
            arr.put(o);
        }
        return arr.toString();
    }

    /** 校验 Cookie 是否有效，并返回账号信息（昵称 / VIP 类型 / 能解析到的最高音质） */
    private String testCookie(JSONObject ignored) throws Exception {
        String resp = Http.postForm("https://music.163.com/api/nuser/account/get", "", config.getCookie());
        JSONObject root = new JSONObject(resp);
        JSONObject out = new JSONObject();
        JSONObject profile = root.optJSONObject("profile");
        if (profile == null) {
            out.put("valid", false);
            out.put("message", "Cookie 无效或已过期（未取到账号信息）");
            return out.toString();
        }
        out.put("valid", true);
        out.put("nickname", profile.optString("nickname", ""));
        out.put("userId", profile.optLong("userId", 0));
        int vipType = profile.optInt("vipType", 0);
        out.put("vipType", vipType);
        String vipName;
        if (vipType == 0) vipName = "普通账号";
        else if (vipType == 11) vipName = "黑胶 VIP";
        else if (vipType == 10) vipName = "音乐包";
        else vipName = "会员类型 " + vipType;
        out.put("vipName", vipName);

        // 顺带探一下这个账号实际能解析到的最高音质
        try {
            JSONObject probe = newApi().songUrl(PROBE_SONG_ID, "hires");
            String maxLv = probe.optString("actual", "");
            out.put("maxLevel", maxLv);
            out.put("maxLevelName", probe.optString("levelName", maxLv));
        } catch (Throwable ignored2) {
            out.put("maxLevel", "");
        }

        String msg = "Cookie 有效，当前账号：" + profile.optString("nickname", "") + "（" + vipName + "）";
        String ml = out.optString("maxLevelName", "");
        if (!ml.isEmpty()) {
            msg += "\n该账号最高可解析：" + ml;
            String lv = out.optString("maxLevel");
            if (lv.equals("standard") || lv.equals("exhigh")) {
                msg += "\n（无损及以上需要黑胶 VIP，把会员账号的 Cookie 填进来即可）";
            }
        }
        out.put("message", msg);
        return out.toString();
    }

    // ------------------------------------------------------------------ 登录

    private String qrCreate(JSONObject p) throws Exception {
        return NeteaseLogin.qrCreate();
    }

    private String qrCheck(JSONObject p) throws Exception {
        String[] res = NeteaseLogin.qrCheck(p.optString("key", "").trim());
        JSONObject o = new JSONObject();
        o.put("body", res[0]);
        o.put("cookie", res[1]);
        return o.toString();
    }

    private String sendLoginCode(JSONObject p) throws Exception {
        String phone = p.optString("phone", "").trim();
        if (phone.isEmpty()) throw new Exception("请先填写手机号");
        return NeteaseLogin.sendCode(phone);
    }

    private String loginByCode(JSONObject p) throws Exception {
        String phone = p.optString("phone", "").trim();
        String code = p.optString("code", "").trim();
        if (phone.isEmpty()) throw new Exception("请先填写手机号");
        if (code.isEmpty()) throw new Exception("请先填写验证码");
        String[] res = NeteaseLogin.login(phone, code);
        JSONObject o = new JSONObject();
        o.put("body", res[0]);
        o.put("cookie", res[1]);
        return o.toString();
    }

    // ------------------------------------------------------------------ 下载

    private String download(JSONObject p) throws Exception {
        JSONObject o = new JSONObject();
        o.put("taskId", downloads.start(p));
        return o.toString();
    }

    private String cancel(JSONObject p) {
        downloads.cancel(p.optString("taskId"));
        return "{\"canceled\":true}";
    }

    // ------------------------------------------------------------ 系统 / 其它 App

    private String getShare(JSONObject p) throws Exception {
        String text = (activity instanceof MainActivity) ? ((MainActivity) activity).takeSharedText() : null;
        JSONObject o = new JSONObject();
        o.put("text", text == null ? "" : text);
        return o.toString();
    }

    private String openApp(JSONObject p) throws Exception {
        final String label = p.optString("name", "目标应用");
        String[] candidates = Apps.candidates(p.optString("pkgs", ""), p.optString("pkg", ""));
        if (candidates.length == 0) throw new Exception("缺少应用包名");

        // 同步返回「是否找到」，让前端据此决定是否弹安装引导
        final String target = Apps.firstInstalled(activity, candidates);
        JSONObject o = new JSONObject();
        if (target == null) {
            o.put("ok", false);
            o.put("reason", "not-installed");
            return o.toString();
        }
        o.put("ok", true);
        o.put("pkg", target);
        main.post(new Runnable() {
            @Override public void run() {
                String err = Apps.launch(activity, target);
                if (err != null) toastMsg("打开「" + label + "」失败：" + err);
            }
        });
        return o.toString();
    }

    private String openUrl(JSONObject p) throws Exception {
        final String url = p.optString("url", "");
        if (url.isEmpty()) throw new Exception("缺少链接");
        main.post(new Runnable() {
            @Override public void run() {
                String err = Apps.openUrl(activity, url);
                if (err != null) toastMsg("无法打开链接：" + err);
            }
        });
        return "{}";
    }

    private String isInstalled(JSONObject p) throws Exception {
        String[] candidates = Apps.candidates(p.optString("pkgs", ""), p.optString("pkg", ""));
        String pkg = Apps.firstInstalled(activity, candidates);
        JSONObject o = new JSONObject();
        o.put("installed", pkg != null);
        o.put("pkg", pkg == null ? "" : pkg);
        return o.toString();
    }

    private String appIcon(JSONObject p) throws Exception {
        String[] candidates = Apps.candidates(p.optString("pkgs", ""), p.optString("pkg", ""));
        String pkg = Apps.firstInstalled(activity, candidates);
        JSONObject o = new JSONObject();
        if (pkg == null) {
            o.put("ok", false);
            o.put("reason", "not-installed");
            return o.toString();
        }
        try {
            o.put("ok", true);
            o.put("pkg", pkg);
            o.put("label", Apps.labelOf(activity, pkg));
            o.put("dataUrl", Apps.iconDataUrl(activity, pkg));
        } catch (Throwable t) {
            o.put("ok", false);
            o.put("reason", errorText(t));
        }
        return o.toString();
    }

    private String copyText(JSONObject p) throws Exception {
        final String text = p.optString("text", "");
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager)
                            activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Cookie", text));
                    toastMsg("Cookie 已复制到剪贴板");
                } catch (Throwable t) {
                    toastMsg("复制失败：" + errorText(t));
                }
            }
        });
        return "{}";
    }

    private String setBars(JSONObject p) throws Exception {
        final boolean dark = p.optBoolean("dark", false);
        main.post(new Runnable() {
            @Override public void run() {
                if (activity instanceof MainActivity) ((MainActivity) activity).applyBars(dark);
            }
        });
        return "{}";
    }

    private String mediaUpdate(JSONObject p) throws Exception {
        if (media == null) media = new MediaNotifier(activity);
        String cover = p.optString("cover", "");
        boolean playing = p.optBoolean("playing", true);
        media.update(p.optString("title", ""), p.optString("artist", ""),
                cover.isEmpty() ? null : cover, playing, p.optLong("duration", 0));
        // 暂停时让服务释放唤醒锁（通知保留）
        if (!playing) MediaPlaybackService.start(activity, false);
        return "{}";
    }

    private String mediaStop(JSONObject p) throws Exception {
        if (media != null) media.stop();
        return "{}";
    }

    private String toast(JSONObject p) throws Exception {
        final String msg = p.optString("msg");
        main.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
            }
        });
        return "{}";
    }

    private String exit(JSONObject p) throws Exception {
        main.post(new Runnable() {
            @Override public void run() { activity.finish(); }
        });
        return "{}";
    }

    // ------------------------------------------------------------------ 内部

    private NeteaseApi newApi() {
        return new NeteaseApi(config.getCookie());
    }

    private JSONObject configJson() throws Exception {
        JSONObject o = new JSONObject();
        String cookie = config.getCookie();
        o.put("level", config.getLevel());
        o.put("dir", config.getDir());
        o.put("customCookie", config.isCustomCookie());
        String musicU = Config.extractMusicU(cookie);
        o.put("musicU", musicU);
        o.put("musicUMasked", musicU.length() > 12
                ? musicU.substring(0, 6) + "…" + musicU.substring(musicU.length() - 4)
                : musicU);
        return o;
    }

    /** 查询「所有文件访问」状态：need 表示系统是否需要单独授权 */
    private String lrcPerm(JSONObject p) throws Exception {
        boolean need = Build.VERSION.SDK_INT >= 30;
        boolean granted = true;
        if (need) {
            try { granted = Environment.isExternalStorageManager(); }
            catch (Throwable ignored) { granted = false; }
        }
        JSONObject o = new JSONObject();
        o.put("need", need);
        o.put("granted", granted);
        return o.toString();
    }

    /** 拉起系统「所有文件访问」授权页（必须在主线程） */
    private String requestLrcPerm(JSONObject p) throws Exception {
        if (activity instanceof MainActivity) {
            final MainActivity a = (MainActivity) activity;
            activity.runOnUiThread(new Runnable() {
                @Override public void run() { a.requestAllFilesAccess(); }
            });
        }
        return "{\"ok\":true}";
    }

    private void toastMsg(final String msg) {
        try {
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }

    private void callback(String id, boolean ok, String payload) {
        if (id == null || id.isEmpty() || "__dl_started__".equals(id)) return;
        eval("window.__cb(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(payload) + ")");
    }

    private void eval(final String js) {
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    webView.evaluateJavascript(js, null);
                } catch (Throwable ignored) {}
            }
        });
    }

    private static String errorText(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        return m;
    }
}
