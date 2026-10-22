package com.whale.netease;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 播放保活服务：
 *  - 前台服务 + MediaStyle 通知 → 进程获得前台优先级，WebView 后台不会被冻结
 *  - 播放期间持有 PARTIAL_WAKE_LOCK → 息屏时 CPU 持续运行，音频不断流
 */
public class MediaPlaybackService extends Service {

    private PowerManager.WakeLock wake;

    public static void start(Context ctx, boolean playing) {
        try {
            Intent i = new Intent(ctx, MediaPlaybackService.class);
            i.putExtra("playing", playing);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable ignored) {}
    }

    public static void stop(Context ctx) {
        try { ctx.stopService(new Intent(ctx, MediaPlaybackService.class)); } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean playing = intent == null || intent.getBooleanExtra("playing", true);

        // 1) 前台通知：必须成功，否则 5 秒后系统会判定失败并杀服务
        try {
            Notification n = MediaNotifier.sharedNotification();
            if (n == null) {
                Notification.Builder b;
                if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, "media");
                else b = new Notification.Builder(this);
                b.setContentTitle("熵析拓解")
                 .setContentText("正在播放")
                 .setSmallIcon(android.R.drawable.stat_sys_headset)
                 .setOngoing(true);
                n = b.build();
            }
            startForeground(MediaNotifier.NOTIF_ID, n);
        } catch (Throwable ignored) {}

        // 2) 播放期间保活 CPU
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                if (wake == null) {
                    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netease:playback");
                    wake.setReferenceCounted(false);
                }
                if (playing) {
                    if (!wake.isHeld()) wake.acquire(3 * 60 * 60 * 1000L);
                } else {
                    if (wake.isHeld()) wake.release();
                }
            }
        } catch (Throwable ignored) {}
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
