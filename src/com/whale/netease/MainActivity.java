package com.whale.netease;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.Toast;
import android.os.Build;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private WebView webView;
    private ApiBridge bridge;
    private FrameLayout rootLayout;
    /** 内置网页层：用户自填地址，用于访问第三方解析站（App 不内置任何站点） */
    private FrameLayout webLayer;
    private WebView webLayerView;
    private TextView webLayerTitle;
    private Config config;
    private int pendingScrollY = 0;
    /** 正在尝试恢复"上次那一页"——失败就退回入口页，避免卡在错误页 */
    private boolean loadingLastPage = false;
    /** 网页状态记忆脚本（从 assets 读一次，之后每页注入） */
    private String webStateJs = null;
    /** 其它应用（如网易云）分享过来的文本，前端启动后取走 */
    private String pendingShare = null;
    /** 正在等「所有文件访问」授权页返回（onResume 里复查） */
    private boolean lrcPermPending = false;

    public String takeSharedText() {
        String s = pendingShare;
        pendingShare = null;
        return s;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = new Config(this);
        handleShareIntent(getIntent());

        Window window = getWindow();
        // 沉浸式（edge-to-edge）：内容铺到状态栏 / 导航栏下方。
        // 不这么做的话，全屏的播放界面上下会各留一条系统栏白边。
        // 页面内部用 env(safe-area-inset-*) 自行避让，所以系统栏本身设成透明。
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            try {
                View decor = window.getDecorView();
                int flags = decor.getSystemUiVisibility();
                flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                       | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                       | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                decor.setSystemUiVisibility(flags);
            } catch (Throwable ignored) {}
        }
        window.setStatusBarColor(0x00000000);
        window.setNavigationBarColor(0x00000000);

        // 上次用什么主题，先把系统栏图标和底色定下来：
        // 否则暗色启动会先闪一下白底 / 深色图标
        boolean darkTheme = config.isDarkTheme();
        applyBars(darkTheme);
        final int themeBg = darkTheme ? 0xFF0E1116 : 0xFFFFFFFF;

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(themeBg);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(themeBg);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        try {
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
        } catch (Throwable ignored) {}

        // 提高渲染进程优先级，避免切后台后渲染/音频线程被降权
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
            }
        } catch (Throwable ignored) {}

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true; // 站内跳转一律由前端自己处理
            }
        });

        bridge = new ApiBridge(this, webView);
        webView.addJavascriptInterface(bridge, "Native");

        rootLayout.addView(webView);
        setContentView(rootLayout);
        registerPredictiveBack();
        webView.loadUrl(prepareWebRoot());
        installBackHandler();
        requestLegacyStorageIfNeeded();
        requestNotificationIfNeeded();
    }

    // ------------------------------------------------------------ 内置网页版

    /**
     * 打开内置网页层。
     * 地址由用户自己填写并保存在本地 —— App 不内置、也不分发任何第三方站点，
     * 只是提供一个"在应用内打开网页"的容器。
     */
    public void showWebLayer(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return;
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://" + u;
        }
        ensureWebLayer();
        webLayer.setVisibility(View.VISIBLE);
        webLayer.bringToFront();

        Object tag = webLayerView.getTag();          // tag = 上次用的"入口配置地址"
        boolean entryChanged = tag != null && !u.equals(tag.toString());
        boolean fresh = webLayerView.getUrl() == null;

        if (fresh || entryChanged) {
            webLayerView.setTag(u);
            String target = u;
            // 入口配置没被改过 → 接着上次停下的页面看，而不是又弹回首页
            if (!entryChanged) {
                String last = config.getWebLastUrl();
                if (last != null && !last.isEmpty() && sameHost(last, u)) {
                    target = last;
                    pendingScrollY = config.getWebScrollY();
                    loadingLastPage = true;
                } else {
                    pendingScrollY = 0;
                }
            } else {
                pendingScrollY = 0;
            }
            webLayerTitle.setText(target);
            webLayerView.loadUrl(target);
        } else {
            // WebView 还活着：页面状态、滚动位置本来就在，什么都不用做
            pendingScrollY = 0;
        }
    }

    public void hideWebLayer() {
        if (webLayer != null) {
            saveWebScroll();
            webLayer.setVisibility(View.GONE);
        }
    }

    /** 把网页当前的滚动位置记下来，下次打开接着看 */
    private void saveWebScroll() {
        if (webLayerView == null) return;
        try {
            webLayerView.evaluateJavascript(
                "(function(){try{var d=document.documentElement,b=document.body;" +
                "var y=window.scrollY||(d&&d.scrollTop)||(b&&b.scrollTop)||0;return String(Math.round(y));}" +
                "catch(e){return '0';}})()",
                value -> {
                    try {
                        int y = Integer.parseInt(value.replace("\"", "").trim());
                        if (y >= 0) config.setWebScrollY(y);
                    } catch (Throwable ignored) {}
                });
        } catch (Throwable ignored) {}
    }

    public boolean isWebLayerVisible() {
        return webLayer != null && webLayer.getVisibility() == View.VISIBLE;
    }

    private void ensureWebLayer() {
        if (webLayer != null) return;
        webLayer = new FrameLayout(this);
        webLayer.setBackgroundColor(0xFFFFFFFF);

        webLayerView = new WebView(this);
        WebSettings s = webLayerView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        webLayerView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (isDirectFile(u)) {          // 直接指向音频/压缩包的链接
                    enqueueSystemDownload(u, null, null, null);
                    return true;
                }
                return false;                   // 其余站内跳转照常
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                // 每次页面变化都记一笔，这样重启 App 也能接着上次看
                if (url != null && url.startsWith("http")) config.setWebLastUrl(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) fallbackToEntry(view);
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                fallbackToEntry(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loadingLastPage = false;
                if (webLayerTitle != null) webLayerTitle.setText(url);
                if (url != null && url.startsWith("http")) config.setWebLastUrl(url);
                // 注入 blob 下载钩子：WebView 自己不支持 blob: 下载，必须由我们接管
                view.evaluateJavascript(BLOB_HOOK_JS, null);
                // 注入状态记忆脚本：还原上次的选择（解析类型 / 音质 / 关键词）
                if (webStateJs != null && !webStateJs.isEmpty()) {
                    view.evaluateJavascript(webStateJs, null);
                }
                restoreScrollIfNeeded();
            }
        });

        // 普通 http(s) 下载链接 → 交给系统下载管理器
        webLayerView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                if (url != null && url.startsWith("blob:")) return;   // blob 由注入的脚本接管
                enqueueSystemDownload(url, userAgent, contentDisposition, mimeType);
            }
        });
        webLayerView.addJavascriptInterface(new BlobDownloadBridge(), "DownloadBridge");
        webLayerView.addJavascriptInterface(new StateBridge(), "StateBridge");
        webStateJs = loadAsset("webstate.js");
        // 顶部栏 + 网页 垂直排列：栏紧贴屏幕顶端，网页从栏下面开始，互不遮挡
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setBackgroundColor(0xFFFFFFFF);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFFFFFFFF);
        // 背景一路铺到屏幕顶端，文字/按钮则压在状态栏下方
        bar.setPadding(dp(16), statusBarHeight() + dp(11), dp(8), dp(10));

        webLayerTitle = new TextView(this);
        webLayerTitle.setTextColor(0xFF8A9099);
        webLayerTitle.setTextSize(11.5f);
        webLayerTitle.setSingleLine(true);
        webLayerTitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        bar.addView(webLayerTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView reload = new TextView(this);          // 刷新：这站偶尔会卡住，随手刷一下
        reload.setText("↻");
        reload.setTextColor(0xFF2E6BE6);
        reload.setTextSize(18f);
        reload.setPadding(dp(12), dp(5), dp(12), dp(5));
        reload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (webLayerView == null) return;
                try {
                    String u = webLayerView.getUrl();
                    if (u == null || u.isEmpty()) {
                        String entry = config.getWebUrl();
                        if (entry != null && !entry.isEmpty()) webLayerView.loadUrl(entry);
                    } else {
                        webLayerView.reload();
                    }
                    toast("正在刷新…");
                } catch (Throwable t) {
                    toast("刷新失败：" + t.getMessage());
                }
            }
        });
        // 长按 = 强制刷新：清掉缓存重新加载，专治顽固卡死
        reload.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (webLayerView == null) return true;
                try {
                    webLayerView.clearCache(true);
                    webLayerView.clearHistory();
                    String u = webLayerView.getUrl();
                    String entry = config.getWebUrl();
                    String target = (u != null && !u.isEmpty()) ? u
                            : ((entry != null && !entry.isEmpty()) ? entry : null);
                    if (target != null) webLayerView.loadUrl(target);
                    toast("已强制刷新（缓存已清除）");
                } catch (Throwable t) {
                    toast("强制刷新失败：" + t.getMessage());
                }
                return true;
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.rightMargin = dp(2);
        bar.addView(reload, rlp);

        TextView home = new TextView(this);
        home.setText("⌂");
        home.setTextColor(0xFF5A6169);
        home.setTextSize(17f);
        home.setPadding(dp(12), dp(5), dp(12), dp(5));
        home.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String u = config.getWebUrl();
                if (u == null || u.isEmpty()) return;
                config.setWebLastUrl(u);
                config.setWebScrollY(0);
                config.setWebState("");
                pendingScrollY = 0;
                try {
                    webLayerView.evaluateJavascript(
                        "try{localStorage.removeItem('__dsh_ui_state__')}catch(e){}", null);
                } catch (Throwable ignored) {}
                webLayerView.setTag(u);
                webLayerView.loadUrl(u);
                toast("已回到入口页（选择已重置）");
            }
        });
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.rightMargin = dp(6);
        bar.addView(home, hlp);

        TextView openOut = new TextView(this);
        openOut.setText("↗");
        openOut.setTextColor(0xFF5A6169);
        openOut.setTextSize(16f);
        openOut.setPadding(dp(12), dp(5), dp(12), dp(5));
        openOut.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    String u = webLayerView.getUrl();
                    if (u == null) return;
                    Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it);
                } catch (Throwable t) {
                    toast("没找到可用的浏览器");
                }
            }
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.rightMargin = dp(6);
        bar.addView(openOut, olp);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(0xFF18191C);
        close.setTextSize(16f);
        close.setPadding(dp(14), dp(5), dp(14), dp(5));
        close.setBackgroundColor(0xFFF2F3F5);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideWebLayer(); }
        });
        bar.addView(close);

        stack.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View hairline = new View(this);            // 一条发丝线，和 App 主界面同款
        hairline.setBackgroundColor(0xFFEDEFF2);
        stack.addView(hairline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));

        stack.addView(webLayerView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        webLayer.addView(stack, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rootLayout.addView(webLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webLayer.setVisibility(View.GONE);
    }

    // -------------------------------------------------- 内置网页的下载支持

    private static boolean isDirectFile(String u) {
        if (u == null) return false;
        String s = u.toLowerCase();
        return s.endsWith(".mp3") || s.endsWith(".flac") || s.endsWith(".m4a") || s.endsWith(".ape")
                || s.endsWith(".wav") || s.endsWith(".zip") || s.endsWith(".lrc")
                || s.contains("music.126.net/");
    }

    /** 交给系统下载管理器：后台下载、通知栏进度、自动存进 Download 目录 */
    private void enqueueSystemDownload(String url, String userAgent, String contentDisposition, String mime) {
        try {
            if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) return;
            String name = guessFileName(url, contentDisposition);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(name);
            req.setDescription("来自内置网页");
            if (mime != null && !mime.isEmpty()) req.setMimeType(mime);
            req.addRequestHeader("User-Agent",
                    userAgent != null ? userAgent : webLayerView.getSettings().getUserAgentString());
            // 把网页里已登录的 Cookie 一并带过去，否则可能下到的是错误页
            try {
                String ck = CookieManager.getInstance().getCookie(url);
                if (ck != null && !ck.isEmpty()) req.addRequestHeader("Cookie", ck);
            } catch (Throwable ignored) {}
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, name);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) { toast("系统下载服务不可用"); return; }
            dm.enqueue(req);
            toast("已开始下载：" + name);
        } catch (Throwable t) {
            toast("下载失败：" + t.getMessage());
        }
    }

    private static String guessFileName(String url, String contentDisposition) {
        String name = null;
        if (contentDisposition != null) {
            int i = contentDisposition.toLowerCase().indexOf("filename=");
            if (i >= 0) {
                name = contentDisposition.substring(i + 9).trim().replace("\"", "").replace("'", "");
                int semi = name.indexOf(';');
                if (semi > 0) name = name.substring(0, semi);
            }
        }
        if (name == null || name.isEmpty()) {
            try {
                String path = Uri.parse(url).getLastPathSegment();
                if (path != null && !path.isEmpty() && path.contains(".")) name = path;
            } catch (Throwable ignored) {}
        }
        if (name == null || name.isEmpty()) name = "download_" + System.currentTimeMillis();
        name = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        if (name.length() > 90) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 ? name.substring(dot) : "";
            name = name.substring(0, 80) + ext;
        }
        return name;
    }

    /* ---------------- 「所有文件访问」授权（外挂 .lrc 直写） ---------------- */

    /** 拉起系统「所有文件访问」授权页；前端「授权」按钮和 requestLrcPerm 都走这里 */
    public void requestAllFilesAccess() {
        try {
            if (Build.VERSION.SDK_INT < 30) {
                toast("当前系统不需要所有文件访问权限");
                return;
            }
            if (Environment.isExternalStorageManager()) {
                toast("已拥有所有文件访问权限");
                evalLrcPerm(true);
                return;
            }
            lrcPermPending = true;
            Intent intent = new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
            intent.setData(Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (Throwable ignored) {
                startActivity(new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"));
            }
        } catch (Throwable t) {
            lrcPermPending = false;
            toast("无法打开授权页：" + t.getMessage());
        }
    }

    /** 把授权结果回传前端（前端 window.__lrcPerm 回调） */
    void evalLrcPerm(final boolean granted) {
        evalJs("window.__lrcPerm&&window.__lrcPerm(" + granted + ")");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!lrcPermPending) return;
        lrcPermPending = false;
        boolean granted = false;
        try {
            if (Build.VERSION.SDK_INT >= 30) granted = Environment.isExternalStorageManager();
        } catch (Throwable ignored) {}
        evalLrcPerm(granted);
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
            }
        });
    }

    /**
     * 接收网页里 blob: 形式下载的数据。
     * WebView 不支持 blob 下载，所以由注入的脚本把 Blob 切片、base64 回传，这里落盘。
     */
    private class BlobDownloadBridge {
        private OutputStream out;
        private Uri uri;
        private File file;
        private String name = "download";
        private long written;

        @JavascriptInterface
        public void begin(String fileName, String mime, long size) {
            try {
                close();
                name = Downloader.safeName(fileName == null || fileName.isEmpty() ? "download" : fileName);
                if (!name.contains(".")) {
                    String ext = extFromMime(mime);
                    if (ext != null) name = name + "." + ext;
                }
                written = 0;
                if (size > 800L * 1024 * 1024) throw new IllegalStateException("文件过大");
                ContentResolver cr = getContentResolver();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE,
                            mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC);
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    // 音频写进音频库（音乐 App 能扫到），其它类型走通用文件表
                    String mm = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
                    Uri collection = mm.startsWith("audio/")
                            ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                            : MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    uri = cr.insert(collection, cv);
                    if (uri != null) out = cr.openOutputStream(uri);
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    if (!dir.exists()) dir.mkdirs();
                    file = new File(dir, name);
                    out = new FileOutputStream(file);
                }
                if (out == null) throw new IllegalStateException("无法创建目标文件");
            } catch (Throwable t) {
                out = null;
                toast("保存失败：" + t.getMessage());
            }
        }

        @JavascriptInterface
        public void chunk(String base64) {
            if (out == null || base64 == null) return;
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                out.write(data);
                written += data.length;
            } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public void end() {
            try {
                if (out != null) { out.flush(); out.close(); out = null; }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(uri, done, null, null);
                }
                if (written > 0) {
                    toast("已保存到 内部存储/Music/" + name + "（" + (written / 1024 / 1024) + " MB）");
                }
            } catch (Throwable t) {
                toast("保存失败：" + t.getMessage());
            } finally {
                uri = null;
            }
        }

        @JavascriptInterface
        public void fail(String msg) {
            close();
            toast("网页下载失败：" + msg);
        }

        private void close() {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
            out = null;
            if (uri != null) {
                try { getContentResolver().delete(uri, null, null); } catch (Throwable ignored) {}
                uri = null;
            }
            if (file != null && file.exists()) { try { file.delete(); } catch (Throwable ignored) {} }
            file = null;
        }
    }

    /**
     * 恢复上次的滚动位置。
     * 旧实现会在 350/1000/2200ms 各强制 scrollTo 一次 —— 用户一进来就滑动时，
     * 会被这几次强制跳转反复打断，表现就是“网页抽搐”。
     * 现在：只尝试一次，且一旦检测到用户已经触摸/滚动，就立刻放弃恢复。
     */
    private void restoreScrollIfNeeded() {
        if (pendingScrollY <= 0 || webLayerView == null) return;
        final int y = pendingScrollY;
        pendingScrollY = 0;
        final String js = "(function(){var y=" + y + ",cancelled=false;" +
                "function cancel(){cancelled=true;}" +
                "window.addEventListener('touchstart',cancel,{passive:true,once:true});" +
                "window.addEventListener('wheel',cancel,{passive:true,once:true});" +
                "setTimeout(function(){" +
                "  window.removeEventListener('touchstart',cancel);" +
                "  window.removeEventListener('wheel',cancel);" +
                "  if(cancelled)return;" +
                "  try{window.scrollTo(0,y);var d=document.documentElement,b=document.body;" +
                "  if(d)d.scrollTop=y;if(b)b.scrollTop=y;}catch(e){}" +
                "},140);})()";
        webLayerView.postDelayed(new Runnable() {
            @Override public void run() {
                try { webLayerView.evaluateJavascript(js, null); } catch (Throwable ignored) {}
            }
        }, 60);
    }

    /** 恢复的那一页打不开时，退回入口页（只退一次，避免来回撞） */
    private void fallbackToEntry(WebView view) {
        if (!loadingLastPage) return;
        loadingLastPage = false;
        String entry = config.getWebUrl();
        if (entry == null || entry.isEmpty()) return;
        pendingScrollY = 0;
        config.setWebLastUrl(entry);
        view.loadUrl(entry);
        toast("上次那页打不开了，已回到首页");
    }

    private static boolean sameHost(String a, String b) {
        try {
            String ha = new java.net.URL(a).getHost();
            String hb = new java.net.URL(b).getHost();
            return ha != null && ha.equalsIgnoreCase(hb);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 切后台时也记一下滚动位置 */
    @Override
    protected void onPause() {
        if (isWebLayerVisible()) saveWebScroll();
        super.onPause();
    }

    /** 读取 assets 里的脚本 */
    private String loadAsset(String name) {
        java.io.InputStream is = null;
        try {
            is = getAssets().open(name);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) {}
        }
    }

    /** 网页状态的原生兜底存取（localStorage 万一被清还有这一份） */
    private class StateBridge {
        @JavascriptInterface
        public String load() {
            try { return config.getWebState(); } catch (Throwable t) { return ""; }
        }

        @JavascriptInterface
        public void save(String json) {
            try {
                if (json != null && json.length() > 0 && json.length() < 256 * 1024) {
                    config.setWebState(json);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static String extFromMime(String mime) {
        if (mime == null) return null;
        String m = mime.toLowerCase();
        if (m.contains("flac")) return "flac";
        if (m.contains("mpeg") || m.contains("mp3")) return "mp3";
        if (m.contains("mp4") || m.contains("m4a")) return "m4a";
        if (m.contains("wav")) return "wav";
        if (m.contains("zip")) return "zip";
        if (m.contains("text")) return "lrc";
        return null;
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    /** 注入到网页里的 blob 下载钩子：把 Blob 切片、base64 回传给原生落盘 */
    private static final String BLOB_HOOK_JS =
        "(function(){if(window.__dshBlobHook)return;window.__dshBlobHook=1;" +
        "function b64(buf){var u=new Uint8Array(buf),s='',c=0x8000;" +
        "for(var i=0;i<u.length;i+=c){s+=String.fromCharCode.apply(null,u.subarray(i,i+c));}return btoa(s);}" +
        "function save(nm,mime,blob){var api=window.DownloadBridge;if(!api||!api.begin)return;" +
        "try{api.begin(nm||'download',mime||blob.type||'application/octet-stream',blob.size);}catch(e){return;}" +
        "var CH=524288,off=0;(function step(){if(off>=blob.size){try{api.end();}catch(e){}return;}" +
        "var part=blob.slice(off,off+CH);off+=CH;" +
        "part.arrayBuffer().then(function(buf){try{api.chunk(b64(buf));}catch(e){}setTimeout(step,0);})" +
        ".catch(function(e){try{api.fail(String(e));}catch(x){}});})();}" +
        "document.addEventListener('click',function(ev){var el=ev.target;" +
        "while(el&&el.tagName!=='A'){el=el.parentElement;}" +
        "if(!el||!el.href)return;" +
        "if(el.href.indexOf('blob:')===0){ev.preventDefault();ev.stopPropagation();" +
        "var nm=el.getAttribute('download')||'';" +
        "fetch(el.href).then(function(r){return r.blob();}).then(function(b){save(nm,b.type,b);})" +
        ".catch(function(e){try{window.DownloadBridge.fail(String(e));}catch(x){}});}},true);})();";

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    /** Android 13+ 想把系统下载的进度通知显示出来，需要这个权限 */
    private void requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1002);
            }
        } catch (Throwable ignored) {}
    }

    /** Android 9 及以下写公共音乐目录需要运行时权限；10+ 走 MediaStore 无需权限 */
    private void requestLegacyStorageIfNeeded() {
        if (Build.VERSION.SDK_INT >= 29) return;
        try {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1001);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Android 13+ 的预测式返回手势不会走 onBackPressed，
     * 所以额外注册 OnBackInvokedCallback，两套都兜住。
     */
    /**
     * 前端资源已加密存放在 assets/payload.bin（AES-256-CBC）。
     * 首次启动（或 APK 更新后）解密到 filesDir/web/index.html，并补齐静态资源，
     * 之后直接加载本地文件，避免每次启动都解密。
     */
    private String prepareWebRoot() {
        File dir = new File(getFilesDir(), "web");
        try {
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建目录");
            copyAssetIfNeeded("app_icon.png", new File(dir, "app_icon.png"));
            copyAssetIfNeeded("webstate.js", new File(dir, "webstate.js"));

            byte[] enc = readAsset("payload.bin");
            long stamp = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
            String tag = Crypto.hash(enc) + "_" + stamp;

            File html = new File(dir, "index.html");
            File mark = new File(dir, ".stamp");
            String old = "";
            if (mark.exists()) {
                try {
                    byte[] mb = new byte[(int) mark.length()];
                    java.io.FileInputStream in = new java.io.FileInputStream(mark);
                    int n = in.read(mb);
                    in.close();
                    old = new String(mb, 0, Math.max(0, n), "UTF-8");
                } catch (Throwable ignored) {}
            }
            if (!html.exists() || !old.equals(tag)) {
                byte[] plain = Crypto.decrypt(enc);
                java.io.FileOutputStream fo = new java.io.FileOutputStream(html);
                fo.write(plain);
                fo.flush();
                fo.close();
                java.io.FileOutputStream fm = new java.io.FileOutputStream(mark);
                fm.write(tag.getBytes("UTF-8"));
                fm.flush();
                fm.close();
            }
            return "file://" + html.getAbsolutePath();
        } catch (Throwable t) {
            // 解密失败时退化为一个提示页，绝不加载明文源码
            File err = new File(dir, "error.html");
            try {
                java.io.FileOutputStream fo = new java.io.FileOutputStream(err);
                fo.write(("<html><body style='font-family:sans-serif;padding:40px;color:#555'>"
                        + "<h3>资源解密失败</h3><p>" + t.getMessage() + "</p></body></html>").getBytes("UTF-8"));
                fo.flush();
                fo.close();
            } catch (Throwable ignored) {}
            return "file://" + err.getAbsolutePath();
        }
    }

    private void copyAssetIfNeeded(String name, File out) throws Exception {
        if (out.exists() && out.length() > 0) return;
        java.io.InputStream in = getAssets().open(name);
        java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        fo.flush();
        fo.close();
        in.close();
    }

    private byte[] readAsset(String name) throws Exception {
        java.io.InputStream in = getAssets().open(name);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    /** 跟随前端主题切换系统栏图标明暗：暗色主题用亮色图标。顺带落盘，供下次冷启动使用 */
    public void applyBars(boolean dark) {
        try {
            if (config != null) config.setDarkTheme(dark);
            Window window = getWindow();
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = window.getInsetsController();
                if (c != null) {
                    int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                             | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(dark ? 0 : mask, mask);
                }
            } else {
                View decor = window.getDecorView();
                int flags = decor.getSystemUiVisibility();
                if (dark) {
                    if (Build.VERSION.SDK_INT >= 23) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    if (Build.VERSION.SDK_INT >= 26) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                } else {
                    if (Build.VERSION.SDK_INT >= 23) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                decor.setSystemUiVisibility(flags);
            }
        } catch (Throwable ignored) {}
    }

    /** 首次播放时请求加入电池优化白名单（Doze 不再限制网络/CPU） */
    public void requestIgnoreBattery() {
        try {
            if (Build.VERSION.SDK_INT < 23) return;
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            android.content.SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
            if (sp.getBoolean("asked_battery", false)) return;
            sp.edit().putBoolean("asked_battery", true).apply();
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable ignored) {}
    }

    /** 供 MediaNotifier 把通知栏操作转发给前端 */
    public void evalJs(final String js) {
        try {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (webView != null) webView.evaluateJavascript(js, null);
                }
            });
        } catch (Throwable ignored) {}
    }

    private void installBackHandler() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new android.window.OnBackInvokedCallback() {
                        @Override public void onBackInvoked() { handleBack(); }
                    });
        } catch (Throwable ignored) {}
    }

    /** 先问前端：有弹层就关弹层；没有就退出 App */
    private void handleBack() {
        if (isWebLayerVisible()) {
            if (webLayerView != null && webLayerView.canGoBack()) webLayerView.goBack();
            else hideWebLayer();
            return;
        }
        if (webView == null) { finish(); return; }
        webView.evaluateJavascript(
                "(function(){try{return window.__onBack?window.__onBack():'exit';}catch(e){return 'exit';}})()",
                value -> {
                    if (value == null || value.contains("exit")) finish();
                });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
        if (pendingShare != null && webView != null) {
            final String text = pendingShare;
            pendingShare = null;
            webView.post(new Runnable() {
                @Override public void run() {
                    webView.evaluateJavascript(
                        "window.__onShare && window.__onShare(" + org.json.JSONObject.quote(text) + ")", null);
                }
            });
        }
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text == null || text.trim().isEmpty()) text = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            if (text != null && !text.trim().isEmpty()) pendingShare = text.trim();
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    /** API 33+ 的预测性返回兜底：即使系统走 OnBackInvokedCallback，也交给同一套层级逻辑 */
    private void registerPredictiveBack() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new android.window.OnBackInvokedCallback() {
                        @Override public void onBackInvoked() { handleBack(); }
                    });
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            try {
                webView.loadUrl("about:blank");
                webView.removeJavascriptInterface("Native");
                webView.destroy();
            } catch (Throwable ignored) {}
            webView = null;
        }
        super.onDestroy();
    }
}
