package com.whale.netease;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;

/**
 * 音乐下载器。
 * 流程：先下到应用缓存 → 写入封面/歌词/歌手等标签 → 再存进公共音乐库。
 * Android 10+ 走 MediaStore，无需任何存储权限。
 */
public class Downloader {

    public interface Listener {
        /**
         * @param percent   0-100 总进度
         * @param done      已下载字节
         * @param total     音频总字节（未知为 0）
         * @param state     0=进行中 1=完成 2=失败 3=已取消
         * @param message   阶段提示
         * @param savedPath 完成时的保存路径
         */
        void onProgress(int percent, long done, long total, int state, String message, String savedPath);
    }

    /** 下载阶段占总进度的比例 */
    private static final int P_DOWNLOAD = 88;

    private volatile boolean cancelled = false;

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public static String extOf(String type, String url) {
        String t = type == null ? "" : type.toLowerCase();
        if (t.contains("flac")) return "flac";
        if (t.contains("mp4") || t.contains("m4a") || t.contains("aac")) return "m4a";
        if (t.contains("mp3")) return "mp3";
        if (t.contains("wav")) return "wav";
        if (t.contains("ape")) return "ape";
        String u = url == null ? "" : url.toLowerCase();
        if (u.contains(".flac")) return "flac";
        if (u.contains(".m4a")) return "m4a";
        if (u.contains(".mp3")) return "mp3";
        return "mp3";
    }

    public static String mimeOf(String ext) {
        if ("flac".equals(ext)) return "audio/flac";
        if ("m4a".equals(ext)) return "audio/mp4";
        if ("wav".equals(ext)) return "audio/wav";
        return "audio/mpeg";
    }

    public static String safeName(String name) {
        if (name == null) return "unknown";
        String s = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (s.length() > 80) s = s.substring(0, 80);
        return s.isEmpty() ? "unknown" : s;
    }

    /**
     * 下载并写入标签。
     *
     * @param meta 元数据（可为 null，则只下不写标签）
     */
    /** 单线程下载（小文件 / 多线程失败时的回退） */
    private long downloadSingle(HttpURLConnection conn, File tmp, long total, Listener listener) throws Exception {
        InputStream in = conn.getInputStream();
        OutputStream out = new FileOutputStream(tmp);
        byte[] buf = new byte[64 * 1024];
        int n;
        long done = 0;
        long lastTick = 0;
        try {
            while ((n = in.read(buf)) > 0) {
                if (cancelled) break;
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastTick > 250) {
                    lastTick = now;
                    int pct = total > 0 ? (int) Math.min(P_DOWNLOAD, done * P_DOWNLOAD / total) : 0;
                    listener.onProgress(pct, done, total, 0, null, null);
                }
            }
            out.flush();
        } finally {
            close(out);
            close(in);
        }
        return done;
    }

    /** 多线程分块下载：4 个连接各取一段，用 FileChannel 按偏移写入同一文件 */
    private long downloadMulti(final String url, final File tmp, final long total, Listener listener) throws Exception {
        final int threads = 4;
        final long part = (total + threads - 1) / threads;
        final java.util.concurrent.atomic.AtomicLong done = new java.util.concurrent.atomic.AtomicLong(0);

        RandomAccessFile raf = new RandomAccessFile(tmp, "rw");
        try { raf.setLength(total); } finally { close(raf); }

        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < threads; i++) {
            final long from = i * part;
            final long to = Math.min(total - 1, (i + 1) * part - 1);
            futures.add(ex.submit(new java.util.concurrent.Callable<Void>() {
                @Override public Void call() throws Exception {
                    downloadRange(url, tmp, from, to, done);
                    return null;
                }
            }));
        }

        long lastTick = 0;
        try {
            while (true) {
                boolean allDone = true;
                for (java.util.concurrent.Future<?> f : futures) {
                    if (!f.isDone()) { allDone = false; break; }
                }
                if (allDone) break;
                if (cancelled) throw new java.io.IOException("已取消");
                long now = System.currentTimeMillis();
                if (now - lastTick > 300) {
                    lastTick = now;
                    int pct = (int) Math.min(P_DOWNLOAD, done.get() * P_DOWNLOAD / total);
                    listener.onProgress(pct, done.get(), total, 0, null, null);
                }
                Thread.sleep(120);
            }
            for (java.util.concurrent.Future<?> f : futures) f.get();
        } finally {
            ex.shutdownNow();
        }
        return done.get();
    }

    /** 下载一个区间 [from, to]，带 3 次重试 */
    private void downloadRange(String url, File tmp, long from, long to,
                               java.util.concurrent.atomic.AtomicLong done) throws Exception {
        int attempts = 0;
        while (true) {
            HttpURLConnection conn = null;
            RandomAccessFile raf = null;
            try {
                conn = (HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(40000);
                conn.setRequestProperty("User-Agent", Http.UA);
                conn.setRequestProperty("Referer", "https://music.163.com/");
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Range", "bytes=" + from + "-" + to);
                conn.connect();
                int code = conn.getResponseCode();
                if (code != 206 && code != 200) throw new java.io.IOException("HTTP " + code);

                InputStream in = conn.getInputStream();
                raf = new RandomAccessFile(tmp, "rw");
                java.nio.channels.FileChannel ch = raf.getChannel();
                byte[] buf = new byte[64 * 1024];
                long pos = from;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled) break;
                    ch.write(java.nio.ByteBuffer.wrap(buf, 0, n), pos);
                    pos += n;
                    done.addAndGet(n);
                }
                close(in);
                return;
            } catch (Exception e) {
                attempts++;
                if (attempts >= 3 || cancelled) throw e;
                try { Thread.sleep(400L * attempts); } catch (InterruptedException ignored) {}
            } finally {
                close(raf);
                if (conn != null) conn.disconnect();
            }
        }
    }

    public String download(Context ctx, String url, String fileName, String ext, String subDir,
                           TagWriter.Meta meta, Listener listener) {
        File tmp = null;
        HttpURLConnection conn = null;
        long done = 0;
        long total = 0;
        try {
            // ---------------- 阶段 1：下载到缓存 ----------------
            tmp = new File(ctx.getCacheDir(),
                    "np_" + System.currentTimeMillis() + "_" + Integer.toHexString(url.hashCode()) + "." + ext);
            conn = Http.openStream(url, null);
            conn.connect();
            int code = conn.getResponseCode();
            if (code >= 400) {
                listener.onProgress(0, 0, 0, 2, "服务器返回 HTTP " + code, null);
                return null;
            }
            total = conn.getContentLength();
            if (total <= 0) total = 0;

            // 大文件（> 8MB）走多线程分块：CDN 实测支持 Range（206），
            // 单连接容易吃不满带宽，分块后速度明显提升。失败自动回退单线程。
            if (total > 8L * 1024 * 1024) {
                conn.disconnect();
                conn = null;
                try {
                    done = downloadMulti(url, tmp, total, listener);
                } catch (Exception me) {
                    if (cancelled) {
                        tmp.delete();
                        listener.onProgress(0, 0, total, 3, "已取消", null);
                        return null;
                    }
                    done = 0;
                    tmp.delete();
                    conn = Http.openStream(url, null);
                    conn.connect();
                    done = downloadSingle(conn, tmp, total, listener);
                }
            } else {
                done = downloadSingle(conn, tmp, total, listener);
                conn.disconnect();
                conn = null;
            }
            if (cancelled) {
                tmp.delete();
                listener.onProgress(0, done, total, 3, "已取消", null);
                return null;
            }

            long fileSize = tmp.length();
            if (fileSize <= 0) {
                tmp.delete();
                listener.onProgress(0, 0, 0, 2, "下载内容为空", null);
                return null;
            }
            if (total <= 0) total = fileSize;

            // ---------------- 阶段 2：写入标签 ----------------
            if (meta != null) {
                if (cancelled) {
                    tmp.delete();
                    listener.onProgress(0, done, total, 3, "已取消", null);
                    return null;
                }
                listener.onProgress(P_DOWNLOAD, fileSize, fileSize, 0, "写入封面与歌词…", null);
                TagWriter.write(tmp, ext, meta);
            }

            // ---------------- 阶段 3：存进音乐库 ----------------
            if (cancelled) {
                tmp.delete();
                listener.onProgress(0, done, total, 3, "已取消", null);
                return null;
            }
            listener.onProgress(92, fileSize, fileSize, 0, "保存到音乐库…", null);
            String saved = saveToLibrary(ctx, tmp, fileName + "." + ext, ext, subDir);
            long finalSize = tmp.length();
            tmp.delete();
            if (saved == null) return null;

            // ---------------- 阶段 4：同目录生成同名 .lrc ----------------
            // 这是关键：绝大多数手机播放器不读内嵌歌词帧，只认同名 .lrc 文件
            boolean lrcSaved = false;
            if (meta != null && meta.lyric != null && !meta.lyric.trim().isEmpty()) {
                listener.onProgress(97, finalSize, finalSize, 0, "写入歌词文件…", null);
                lrcSaved = saveLrc(ctx, fileName, meta.lyric, meta.durationMs, subDir);
            }
            if (lrcSaved) {
                listener.onProgress(99, finalSize, finalSize, 0, "歌词已生成 .lrc", null);
            } else {
                listener.onProgress(99, finalSize, finalSize, 0, "歌词文件写入失败（内嵌歌词仍可用）", null);
            }

            listener.onProgress(100, finalSize, finalSize, 1, null, saved);
            return saved;
        } catch (Exception e) {
            if (tmp != null) tmp.delete();
            listener.onProgress(0, done, total, 2, describe(e), null);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 能否绕过 MediaStore 直接按路径写 .lrc：Android 10 上两条路都不通，返回 false */
    private static boolean canDirectLrc() {
        int sdk = Build.VERSION.SDK_INT;
        if (sdk <= 28) return true;
        if (sdk >= 30) {
            try { return Environment.isExternalStorageManager(); }
            catch (Throwable ignored) { return false; }
        }
        return false;
    }

    /**
     * 在音乐目录里生成与音频同名的 .lrc 歌词文件。
     * 手机播放器普遍只认这种外挂歌词，内嵌帧它们大多不看。
     */
    private boolean saveLrc(Context ctx, String baseName, String lyric, long durationMs, String subDir) {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues cv = null;
        Uri uri = null;
        OutputStream out = null;
        try {
            String prep = TagWriter.normalizeLrc(TagWriter.sanitizeLrc(lyric));
            if (durationMs > 0 && !prep.matches("(?s).*\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?(?:-\\d+)?\\].*")) {
                prep = TagWriter.estimateLrc(prep, durationMs);
            }
            byte[] data = prep.getBytes("UTF-8");
            // 授权「所有文件访问」后直接按路径写：MediaStore 对 .lrc 的 insert 支持很差
            if (canDirectLrc()) {
                try {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), subDir);
                    if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("mkdir failed");
                    File target = new File(dir, baseName + ".lrc");
                    FileOutputStream fo = new FileOutputStream(target);
                    try {
                        fo.write(data);
                        fo.flush();
                    } finally {
                        fo.close();
                    }
                    Log.i("NeteaseLrc", "direct saved " + target + " bytes=" + data.length);
                    return true;
                } catch (Throwable t) {
                    Log.w("NeteaseLrc", "direct failed " + t, t);
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Uri files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                String rel = Environment.DIRECTORY_MUSIC + "/" + subDir;
                // 同名先清掉，避免 insert 冲突返回 null
                try {
                    cr.delete(files, MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                            + MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[]{baseName + ".lrc", rel});
                } catch (Exception ignored) {}
                cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, baseName + ".lrc");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                // 有些系统对 .lrc + text/plain 的 insert 会直接返回 null，
                // 依次换几种 MIME 试，直到拿到 uri
                String[] mimes = {"text/plain", "application/octet-stream", "text/x-lrc", "application/x-lrc"};
                for (String mime : mimes) {
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    try {
                        uri = cr.insert(files, cv);
                    } catch (Exception ignored) {
                        uri = null;
                    }
                    if (uri != null) break;
                }
                if (uri == null) return false;
                out = cr.openOutputStream(uri);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), subDir);
                if (!dir.exists() && !dir.mkdirs()) return false;
                out = new FileOutputStream(new File(dir, baseName + ".lrc"));
            }
            if (out == null) return false;
            out.write(data);
            out.flush();
            out.close();
            out = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);
            }
            return true;
        } catch (Exception e) {
            close(out);
            if (uri != null) {
                try { cr.delete(uri, null, null); } catch (Exception ignored) {}
            }
            return false;
        }
    }

    /** 把打好标签的临时文件搬进公共音乐目录 */
    private String saveToLibrary(Context ctx, File src, String displayName, String ext, String subDir) {
        InputStream in = null;
        OutputStream out = null;
        Uri mediaUri = null;
        File target = null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            String mime = mimeOf(ext);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
                cv.put(MediaStore.Audio.Media.MIME_TYPE, mime);
                cv.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + subDir);
                cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
                mediaUri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
                if (mediaUri == null) {
                    // 重名等情况：去掉 pending 标记再插一次
                    cr.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            MediaStore.Audio.Media.DISPLAY_NAME + "=? AND "
                                    + MediaStore.Audio.Media.RELATIVE_PATH + "=?",
                            new String[]{displayName, Environment.DIRECTORY_MUSIC + "/" + subDir});
                    mediaUri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
                }
                if (mediaUri == null) {
                    return null;
                }
                out = cr.openOutputStream(mediaUri);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), subDir);
                if (!dir.exists() && !dir.mkdirs()) return null;
                target = new File(dir, displayName);
                out = new FileOutputStream(target);
            }
            if (out == null) {
                if (mediaUri != null) cr.delete(mediaUri, null, null);
                return null;
            }

            in = new java.io.FileInputStream(src);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.close();
            out = null;
            close(in);
            in = null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaUri != null) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                cr.update(mediaUri, done, null, null);
                return Environment.getExternalStorageDirectory().getAbsolutePath() + "/"
                        + Environment.DIRECTORY_MUSIC + "/" + subDir + "/" + displayName;
            }
            return target == null ? null : target.getAbsolutePath();
        } catch (Exception e) {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            if (mediaUri != null) {
                try { ctx.getContentResolver().delete(mediaUri, null, null); } catch (Exception ignored) {}
            }
            return null;
        }
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        if (m.contains("ENOSPC") || m.contains("No space")) return "存储空间不足";
        if (m.contains("EPERM") || m.contains("Permission")) return "没有写入权限：" + m;
        return m;
    }

    private static void close(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (Exception ignored) {}
    }
}
