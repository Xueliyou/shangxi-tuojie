package com.whale.netease;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.view.KeyEvent;

/**
 * 媒体通知 + MediaSession。
 * HyperOS / MIUI 的「灵动岛 / 媒体卡片」只有在应用发布了
 * MediaStyle 通知且持有活跃 MediaSession 时才会接管显示。
 */
public class MediaNotifier {

    private static final String CHANNEL = "media";
    public static final int NOTIF_ID = 2001;
    private static MediaNotifier INSTANCE;

    private final Context ctx;
    private final NotificationManager nm;
    private MediaSession session;
    private String title = "";
    private String artist = "";
    private String coverUrl = "";
    private Bitmap cover;
    private boolean playing = false;
    private long durationMs = 0;

    public MediaNotifier(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.nm = (NotificationManager) this.ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        INSTANCE = this;
        ensureChannel();
    }

    /** 给前台服务用：取最近一次构建的通知 */
    public static Notification sharedNotification() {
        try {
            if (INSTANCE == null) return null;
            return INSTANCE.buildNotification();
        } catch (Throwable t) {
            return null;
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26 || nm == null) return;
        try {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "正在播放", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable ignored) {}
    }

    private void ensureSession() {
        if (session != null) return;
        try {
            session = new MediaSession(ctx, "熵析拓解");
            session.setActive(true);
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { sendToWeb("play"); }
                @Override public void onPause() { sendToWeb("pause"); }
                @Override public void onSkipToNext() { sendToWeb("next"); }
                @Override public void onSkipToPrevious() { sendToWeb("prev"); }
            });
        } catch (Throwable ignored) {}
    }

    /** 通知栏按钮/锁屏控制 → 通知 WebView 执行对应动作 */
    private void sendToWeb(final String action) {
        if (ctx instanceof MainActivity) {
            ((MainActivity) ctx).evalJs("window.__mediaAction && window.__mediaAction('" + action + "')");
        }
    }

    /**
     * 更新媒体状态与通知。
     *
     * @param title    歌名
     * @param artist   歌手
     * @param coverUrl 封面地址（用于通知大图）
     * @param isPlay   是否正在播放
     * @param duration 时长毫秒
     */
    public void update(String title, String artist, String coverUrl, boolean isPlay, long duration) {
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.playing = isPlay;
        this.durationMs = duration;
        if (coverUrl != null && !coverUrl.isEmpty() && !coverUrl.equals(this.coverUrl)) {
            this.coverUrl = coverUrl;
            loadCoverAsync(coverUrl);
        }
        ensureSession();
        push();
    }

    public void stop() {
        try {
            if (session != null) {
                session.setActive(false);
                session.release();
                session = null;
            }
            if (nm != null) nm.cancel(NOTIF_ID);
            MediaPlaybackService.stop(ctx);
        } catch (Throwable ignored) {}
    }

    private void loadCoverAsync(final String url) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] data = Http.getBytes(url, 2 * 1024 * 1024);
                    if (data == null) return;
                    Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bmp == null) return;
                    cover = bmp;
                    if (url.equals(coverUrl)) push();
                } catch (Throwable ignored) {}
            }
        }, "media-cover").start();
    }

    /** 构造媒体按键的 PendingIntent（系统会转发给 MediaSession） */
    private PendingIntent mediaButton(int keyCode) {
        Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON);
        i.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        int flags = Build.VERSION.SDK_INT >= 23
                ? (PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getBroadcast(ctx, keyCode, i, flags);
    }

    private volatile long lastPushAt = 0;
    private volatile String lastKey = "";

    private void push() {
        try {
            // 播放中：确保前台服务在跑（WebView 后台不被冻结）
            if (playing) MediaPlaybackService.start(ctx, true);
            // 构建通知会解析 Bitmap，放到后台线程，避免阻塞 WebView 主线程
            final String key = title + "|" + artist + "|" + playing + "|" + (cover != null);
            long now = System.currentTimeMillis();
            if (key.equals(lastKey) && now - lastPushAt < 1000) return;   // 节流
            lastKey = key;
            lastPushAt = now;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        Notification n = buildNotification();
                        if (n != null && nm != null) nm.notify(NOTIF_ID, n);
                    } catch (Throwable ignored) {}
                }
            }, "media-notify").start();
        } catch (Throwable ignored) {}
    }

    /** 构建当前状态的媒体通知 */
    public Notification buildNotification() {
        try {
            ensureSession();
            if (session == null) return null;

            // 1) 更新 MediaSession 状态
            int state = playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setState(state, 0, playing ? 1f : 0f)
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .build());

            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "熵析拓解");
            if (durationMs > 0) mb.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);
            if (cover != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover);
            session.setMetadata(mb.build());

            // 2) 发布 MediaStyle 通知
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(ctx, CHANNEL);
            else b = new Notification.Builder(ctx);

            b.setContentTitle(title)
             .setContentText(artist)
             .setSmallIcon(android.R.drawable.stat_sys_headset)
             .setContentIntent(pi)
             .setOngoing(playing)
             .setShowWhen(false)
             .setVisibility(Notification.VISIBILITY_PUBLIC);

            if (cover != null) b.setLargeIcon(cover);

            try {
                b.setCategory(Notification.CATEGORY_TRANSPORT);
                b.setColor(0xFF2E6BE6);
                if (Build.VERSION.SDK_INT >= 26) b.setColorized(false);
                b.setDefaults(0);   // 静音
            } catch (Throwable ignored) {}

            if (Build.VERSION.SDK_INT >= 21) {
                Notification.MediaStyle style = new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken());
                // 上一首 / 播放暂停 / 下一首
                try {
                    b.addAction(new Notification.Action.Builder(
                            android.R.drawable.ic_media_previous, "上一首",
                            mediaButton(KeyEvent.KEYCODE_MEDIA_PREVIOUS)).build());
                    b.addAction(new Notification.Action.Builder(
                            playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                            playing ? "暂停" : "播放",
                            mediaButton(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)).build());
                    b.addAction(new Notification.Action.Builder(
                            android.R.drawable.ic_media_next, "下一首",
                            mediaButton(KeyEvent.KEYCODE_MEDIA_NEXT)).build());
                    style.setShowActionsInCompactView(0, 1, 2);
                } catch (Throwable ignored) {}
                b.setStyle(style);
            }
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);

            return b.build();
        } catch (Throwable ignored) {}
        return null;
    }
}
