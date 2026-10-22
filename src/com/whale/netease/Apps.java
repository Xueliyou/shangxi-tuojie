package com.whale.netease;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 「别的 App」相关的杂活：候选包名、是否安装、图标、启动、打开链接。
 *
 * <p>这些和网易云解析没有关系，原来都堆在 {@link ApiBridge} 里，
 * 抽出来以后桥接层只剩路由，这里可以单独演进（比如以后支持更多快捷入口）。
 */
final class Apps {

    private Apps() {}

    /** 包名候选：pkgs（逗号分隔）+ 单个 pkg，去重并保持顺序，单个 pkg 优先 */
    static String[] candidates(String list, String single) {
        List<String> out = new ArrayList<String>();
        if (list != null && !list.isEmpty()) {
            for (String x : list.split(",")) {
                String v = x.trim();
                if (!v.isEmpty() && !out.contains(v)) out.add(v);
            }
        }
        if (single != null && !single.trim().isEmpty()) {
            String v = single.trim();
            if (!out.contains(v)) out.add(0, v);
        }
        return out.toArray(new String[0]);
    }

    /** 返回候选里第一个真正装了的包名，都没装返回 null */
    static String firstInstalled(Context ctx, String[] pkgs) {
        if (pkgs == null || pkgs.length == 0) return null;
        PackageManager pm = ctx.getPackageManager();
        for (String p : pkgs) {
            try {
                pm.getPackageInfo(p, 0);
                return p;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 启动应用：成功返回 null，失败返回原因 */
    static String launch(Context ctx, String pkg) {
        try {
            Intent it = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (it == null) {
                it = new Intent(Intent.ACTION_MAIN);
                it.addCategory(Intent.CATEGORY_LAUNCHER);
                it.setPackage(pkg);
            }
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return null;
        } catch (Throwable t) {
            String m = t.getMessage();
            return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
        }
    }

    /** 用系统浏览器打开链接 */
    static String openUrl(Context ctx, String url) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return null;
        } catch (Throwable t) {
            String m = t.getMessage();
            return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
        }
    }

    static String labelOf(Context ctx, String pkg) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getPackageInfo(pkg, 0).applicationInfo;
            return String.valueOf(ctx.getPackageManager().getApplicationLabel(ai));
        } catch (Throwable t) {
            return pkg;
        }
    }

    /** 应用图标 → data:image/png;base64，前端直接当 img src 用 */
    static String iconDataUrl(Context ctx, String pkg) throws Exception {
        Drawable icon = ctx.getPackageManager().getApplicationIcon(pkg);
        Bitmap bmp = toBitmap(icon, 144);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return "data:image/png;base64,"
                + android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
    }

    private static Bitmap toBitmap(Drawable d, int size) {
        if (d instanceof BitmapDrawable) {
            Bitmap bm = ((BitmapDrawable) d).getBitmap();
            if (bm != null && bm.getWidth() > 0) {
                return Bitmap.createScaledBitmap(bm, size, size, true);
            }
        }
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return bmp;
    }
}
