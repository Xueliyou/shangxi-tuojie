package com.whale.netease;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 下载保活服务：
 *  - 前台服务：避免切到后台后进程被系统回收
 *  - PARTIAL_WAKE_LOCK：息屏时保持 CPU 运行，网络下载不会停
 */
public class DownloadService extends Service {

    private static final String CHANNEL_ID = "dl";
    private static final int NOTIF_ID = 1001;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context ctx, String text) {
        try {
            Intent i = new Intent(ctx, DownloadService.class);
            i.putExtra("text", text == null ? "正在下载…" : text);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable ignored) {}
    }

    public static void stop(Context ctx) {
        try { ctx.stopService(new Intent(ctx, DownloadService.class)); } catch (Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                    NotificationChannel ch = new NotificationChannel(
                            CHANNEL_ID, "下载", NotificationManager.IMPORTANCE_LOW);
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            } catch (Throwable ignored) {}
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netease:download");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(2 * 60 * 60 * 1000L);   // 最长 2 小时，异常情况下自动释放
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            String text = intent != null ? intent.getStringExtra("text") : "正在下载…";
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_ID);
            else b = new Notification.Builder(this);
            b.setContentTitle("熵析拓解")
             .setContentText(text)
             .setSmallIcon(android.R.drawable.stat_sys_download)
             .setOngoing(true);
            startForeground(NOTIF_ID, b.build());
        } catch (Throwable ignored) {}
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
