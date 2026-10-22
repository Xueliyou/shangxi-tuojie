package com.whale.netease;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 下载进度节流器。
 *
 * <p>下载线程每收到一块数据就会回调一次进度，直接一条一条推给 WebView
 * 会让 JS 忙不过来（每次都要跨线程 evaluateJavascript）。
 * 这里做两件事：
 * <ul>
 *   <li>同一任务的连续进度只保留最新一条；</li>
 *   <li>每 300ms 合并成一次调用推送。</li>
 * </ul>
 * 完成 / 失败 / 取消 / 准备这些低频且重要的状态立即推送，不排队。
 */
final class ProgressMux {

    interface Emitter {
        void emit(String js);
    }

    /** 只有这个状态是高频的（下载中），其余一律立即发送 */
    private static final int STATE_DOWNLOADING = 2;

    private final Emitter emitter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, JSONObject> pending = new ConcurrentHashMap<String, JSONObject>();
    private volatile boolean scheduled = false;

    private final Runnable flushTask = new Runnable() {
        @Override public void run() {
            scheduled = false;
            flush();
        }
    };

    ProgressMux(Emitter emitter) {
        this.emitter = emitter;
    }

    /**
     * @param state 1=准备 2=下载中 3=完成 4=失败 5=取消
     */
    void push(String taskId, int state, int percent, long done, long total,
              String message, String path, String name, String ext) {
        try {
            JSONObject o = new JSONObject();
            o.put("taskId", taskId);
            o.put("state", state);
            o.put("percent", percent);
            o.put("done", done);
            o.put("total", total);
            o.put("message", message == null ? "" : message);
            if (path != null) o.put("path", path);
            if (name != null) o.put("name", name);
            if (ext != null) o.put("ext", ext);

            pending.put(taskId, o);
            if (state == STATE_DOWNLOADING) {
                if (!scheduled) {
                    scheduled = true;
                    main.postDelayed(flushTask, 300);
                }
            } else {
                flush();
            }
        } catch (Exception ignored) {}
    }

    private void flush() {
        if (pending.isEmpty()) return;
        List<JSONObject> batch = new ArrayList<JSONObject>(pending.values());
        pending.clear();
        StringBuilder js = new StringBuilder();
        for (JSONObject item : batch) {
            if (js.length() > 0) js.append(';');
            js.append("window.__dl(").append(JSONObject.quote(item.toString())).append(")");
        }
        emitter.emit(js.toString());
    }
}
