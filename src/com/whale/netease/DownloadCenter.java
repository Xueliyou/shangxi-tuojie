package com.whale.netease;

import android.app.Activity;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 下载任务中心。
 *
 * <p>{@link ApiBridge} 只负责把 download / cancel 转给这里；任务登记、
 * 线程池、前台服务 / 唤醒锁、封面与歌词的准备全在本类，
 * 进度通过 {@link Sink} 回传（由 {@link ProgressMux} 负责节流）。
 */
final class DownloadCenter {

    /** 进度出口：state 1=准备 2=下载中 3=完成 4=失败 5=取消 */
    interface Sink {
        void onProgress(String taskId, int state, int percent, long done, long total,
                        String message, String path, String name, String ext);
    }

    /** 同时下几首。每个任务内部还会 4 线程分块，网络差时容易被 CDN 限速 */
    private static final int CONCURRENCY = 3;

    private final Activity activity;
    private final Config config;
    private final Sink sink;

    /* 独立线程池：低优先级，写大文件（打标签要重写整个文件）时别抢播放/渲染的 CPU */
    private final ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY,
            new ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "netease-download");
                    t.setPriority(Thread.MIN_PRIORITY);
                    t.setDaemon(true);
                    return t;
                }
            });

    private final Map<String, Downloader> tasks = new ConcurrentHashMap<String, Downloader>();
    private int seq = 0;

    DownloadCenter(Activity activity, Config config, Sink sink) {
        this.activity = activity;
        this.config = config;
        this.sink = sink;
    }

    /** 登记并排队一个下载任务，立刻返回 taskId */
    String start(JSONObject p) throws Exception {
        final long id = p.optLong("id");
        final String level = p.optString("level", config.getLevel());
        final String fallbackName = Downloader.safeName(p.optString("name", "song"));
        final String fallbackArtist = Downloader.safeName(p.optString("artist", ""));
        final String dir = config.getDir();
        final String taskId = "t" + (++seq);

        final Downloader dl = new Downloader();
        tasks.put(taskId, dl);
        sink.onProgress(taskId, 1, 0, 0, 0, "准备中…", null, fallbackName, null);

        pool.execute(new Runnable() {
            @Override public void run() {
                runTask(taskId, dl, id, level, dir, fallbackName, fallbackArtist);
            }
        });
        return taskId;
    }

    /** 取消：只置标志，收尾（移除任务 / 停服务）由工作线程做 */
    void cancel(String taskId) {
        Downloader d = tasks.get(taskId);
        if (d != null) d.cancel();
    }

    // ------------------------------------------------------------------ 任务体

    private void runTask(String taskId, Downloader dl, long id, String level, String dir,
                         String fallbackName, String fallbackArtist) {
        try {
            NeteaseApi api = new NeteaseApi(config.getCookie());

            // 1) 歌曲详情 —— 标题 / 歌手 / 专辑 / 封面
            JSONObject detail = null;
            try { detail = api.songDetail(id); } catch (Exception ignored) {}
            final String title = detail != null ? detail.optString("name", fallbackName) : fallbackName;
            String artist = detail != null ? detail.optString("artists", fallbackArtist) : fallbackArtist;
            String album = detail != null ? detail.optString("album", "") : "";
            String picUrl = detail != null ? detail.optString("picUrl", "") : "";

            // 2) 歌词
            sink.onProgress(taskId, 1, 0, 0, 0, "获取歌词…", null, null, null);
            String lyric = "";
            try { lyric = api.lyric(id).optString("lyric", ""); } catch (Exception ignored) {}

            // 3) 封面（缩到 500x500，避免嵌入大图把文件撑爆）
            byte[] cover = null;
            int cw = 0, ch = 0;
            String cu = coverUrl(picUrl, 500);
            if (cu != null) {
                try {
                    byte[] img = Http.getBytes(cu, 3 * 1024 * 1024);
                    if (img != null && img.length > 1024) {
                        int[] wh = TagWriter.imageSize(img);
                        cover = img;
                        cw = wh[0];
                        ch = wh[1];
                    }
                } catch (Exception ignored) {}
            }

            // 4) 播放地址
            sink.onProgress(taskId, 1, 0, 0, 0, "解析音质…", null, null, null);
            JSONObject info = api.songUrl(id, level);
            String url = info.optString("url");
            String ext = Downloader.extOf(info.optString("type", "mp3"), url);
            boolean degraded = info.optBoolean("degraded", false);
            String tip = degraded
                    ? "已降级为 " + info.optString("levelName", level) + "，下载中"
                    : "下载中（" + info.optString("levelName", level) + "）";

            String safeTitle = Downloader.safeName(title);
            String safeArtist = Downloader.safeName(artist);
            final String fileName = safeArtist.isEmpty() ? safeTitle : (safeTitle + " - " + safeArtist);

            TagWriter.Meta meta = new TagWriter.Meta();
            meta.title = title;
            meta.artist = artist;
            meta.album = album;
            meta.lyric = lyric;
            meta.durationMs = detail != null ? detail.optLong("duration", 0) : 0;
            meta.cover = cover;
            meta.coverW = cw;
            meta.coverH = ch;
            meta.coverMime = "image/jpeg";

            sink.onProgress(taskId, 1, 0, 0, 0, tip, null, fileName, ext);
            // 前台服务 + 唤醒锁：切后台 / 息屏时下载不中断
            DownloadService.start(activity, "正在下载 " + fileName);

            final String task = taskId;
            dl.download(activity, url, fileName, ext, dir, meta, new Downloader.Listener() {
                @Override
                public void onProgress(int percent, long done, long total,
                                       int state, String message, String savedPath) {
                    /* Downloader: 0=进度 1=完成 2=失败 3=取消 → 前端协议: 2/3/4/5 */
                    int st = state == 0 ? 2 : (state == 1 ? 3 : (state == 3 ? 5 : 4));
                    sink.onProgress(task, st, percent, done, total, message, savedPath, null, null);
                }
            });
        } catch (Throwable t) {
            sink.onProgress(taskId, 4, 0, 0, 0, errorText(t), null, null, null);
        } finally {
            tasks.remove(taskId);
            if (tasks.isEmpty()) DownloadService.stop(activity);
        }
    }

    /** 把封面地址改成指定尺寸并升级到 https */
    private static String coverUrl(String raw, int size) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String u = raw.trim().replace("http://", "https://");
        String param = "param=" + size + "y" + size;
        if (u.matches(".*[?&]param=\\d+y\\d+.*")) {
            u = u.replaceAll("([?&])param=\\d+y\\d+", "$1" + param);
        } else {
            u = u + (u.contains("?") ? "&" : "?") + param;
        }
        return u;
    }

    private static String errorText(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        return m;
    }
}
