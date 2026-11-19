package com.fongmi.android.tv.utils;

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.net.OkHttp;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 直播选线首片护栏。
 *
 * <p>爬虫侧已按首片速度排序，但盒子实测仍会冷启时先命中一条慢线、卡一下再跳。
 * 这里在真正起播前用 Range 只拉首分片前 64KB 测速：慢于阈值直接抛给上层换线，
 * 不让用户先看几秒转圈。探测失败一律视为不可用，交给既有 error→nextLine 链路。
 */
public final class LiveUrlProbe {

    private static final String TAG = "LiveUrlProbe";

    /** 探测超时：宁可误杀慢线，也不能把换台拖成十几秒。 */
    private static final long TIMEOUT_MS = 1800;
    private static final int PROBE_BYTES = 64 * 1024;
    private static final long MIN_BYTES = 512;
    /** 低于 300KB/s 视为慢线，与爬虫闸门阈值保持一致。 */
    private static final long MIN_BPS = 300 * 1024;
    private static final long MAX_MS = 2000;

    private static final OkHttpClient CLIENT = OkHttp.player().newBuilder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build();

    private LiveUrlProbe() {
    }

    /** true = 可用或不可探测（非 HTTP）；false = 慢/失败，应换线。 */
    public static boolean accept(String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) return true;
        try {
            long start = SystemClock.elapsedRealtime();
            String segment = firstSegment(url);
            if (segment == null) return true;               // 拿不到分片清单，不误杀
            Request request = new Request.Builder().url(segment)
                    .header("Range", "bytes=0-" + (PROBE_BYTES - 1)).build();
            try (Response response = CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code != 200 && code != 206) {
                    Log.d(TAG, "HTTP " + code + " " + url);
                    return false;
                }
                byte[] data = response.body() == null ? new byte[0] : response.body().bytes();
                long ms = Math.max(1, SystemClock.elapsedRealtime() - start);
                long bps = data.length * 1000 / ms;
                boolean ok = data.length >= MIN_BYTES && bps >= MIN_BPS && ms <= MAX_MS;
                Log.d(TAG, (ok ? "OK " : "SLOW ") + data.length + "B/" + ms + "ms/" + (bps / 1024) + "KBps " + url);
                return ok;
            }
        } catch (Exception e) {
            Log.d(TAG, "FAIL " + e.getClass().getSimpleName() + " " + url);
            return false;
        }
    }

    /** 取 m3u8 首条媒体分片；直链 ts 直接返回自身。 */
    private static String firstSegment(String url) throws Exception {
        String lower = url.toLowerCase();
        // 注意：不能用 split("?")，? 是正则量词会抛 PatternSyntaxException
        int q = lower.indexOf('?');
        String path = q >= 0 ? lower.substring(0, q) : lower;
        if (!path.endsWith(".m3u8")) return url;
        Request request = new Request.Builder().url(url).build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            String text = response.body().string();
            for (String line : text.split("\\r?\\n")) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                if (s.startsWith("http://") || s.startsWith("https://")) return s;
                if (s.startsWith("/")) {
                    int p = url.indexOf("://");
                    int slash = url.indexOf('/', p + 3);
                    return url.substring(0, slash < 0 ? url.length() : slash) + s;
                }
                int slash = url.lastIndexOf('/');
                return url.substring(0, slash + 1) + s;
            }
        }
        return null;
    }
}
