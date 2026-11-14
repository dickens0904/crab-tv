package com.fongmi.android.tv.utils;

import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.HttpUrl;
import okhttp3.Request;

/**
 * 原生豆瓣爬虫（兜底路径）：中文片名 + 年份 -> 豆瓣评分。
 *
 * <p>仅在 wmdb 主路径没拿到豆瓣分时启用。优先 j/search_subjects（直接带 rate 字段），
 * 若被风控返回空则降级 j/subject_suggest -> subject 详情页解析 v:average。
 * 注意：豆瓣对非正常 UA / 高频 IP 有风控，本类低频(1.2s/req)+固定 UA+礼貌延迟，
 * 且仅作个人补全用途；任何失败都优雅返回空，绝不抛异常阻断上层。
 *
 * <p>仅在后台线程调用。盒子走正常家用路由出口，一般可达豆瓣（沙箱数据中心 IP 才会被风控）。
 */
public class DoubanClient {

    private static final String TAG = "DoubanClient";
    private static final String BASE = "https://movie.douban.com";
    private static final String UA = "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final long DELAY_MS = 1_200L;
    private static final int MAX_RETRIES = 3;
    private static final double TITLE_MIN = 0.6;
    private static final int YEAR_TOL = 1;

    public static class Result {
        public boolean found;
        public String doubanScore;
        public String doubanId;
    }

    private long lastCall;

    private synchronized void throttle() {
        long now = System.currentTimeMillis();
        long gap = DELAY_MS - (now - lastCall);
        if (gap > 0) {
            try {
                Thread.sleep(gap);
            } catch (InterruptedException ignored) {
            }
        }
        lastCall = System.currentTimeMillis();
    }

    private String get(String url) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                throttle();
                okhttp3.Response res = OkHttp.client().newCall(new Request.Builder().url(url)
                        .header("User-Agent", UA)
                        .header("Referer", BASE + "/")
                        .header("Accept-Language", "zh-CN,zh;q=0.9")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .build()).execute();
                int code = res.code();
                if (code == 200) {
                    String body = res.body().string();
                    res.close();
                    return body;
                }
                if (code == 403 || code == 429) {
                    Thread.sleep(DELAY_MS * attempt * 2);
                    continue;
                }
                return null;
            } catch (Exception e) {
                Log.w(TAG, "request failed: " + e.getMessage());
                try {
                    Thread.sleep(DELAY_MS * attempt);
                } catch (InterruptedException ignored) {
                }
            }
        }
        return null;
    }

    public Result search(String name, int year, String mediaType) {
        Result empty = new Result();
        String dtype = "movie".equals(mediaType) ? "movie" : "tv";
        JSONArray cands = searchSubjects(name, dtype);
        if (cands == null) cands = suggest(name, dtype);
        if (cands == null || cands.length() == 0) return empty;
        JSONObject best = match(cands, name, year);
        if (best == null) return empty;
        String id = best.optString("id", "");
        String rate = best.optString("rate", "");
        if (TextUtils.isEmpty(rate)) rate = subjectRate(id);
        if (TextUtils.isEmpty(rate)) return empty;
        Result r = new Result();
        r.found = true;
        r.doubanScore = rate.trim();
        r.doubanId = id;
        return r;
    }

    private JSONArray searchSubjects(String name, String dtype) {
        HttpUrl url = HttpUrl.parse(BASE + "/j/search_subjects").newBuilder()
                .addQueryParameter("type", dtype)
                .addQueryParameter("q", name == null ? "" : name)
                .addQueryParameter("start", "0")
                .addQueryParameter("limit", "20")
                .build();
        String json = get(url.toString());
        if (TextUtils.isEmpty(json)) return null;
        try {
            Object parsed = new JSONObject(json).opt("subjects");
            if (parsed instanceof JSONArray) return (JSONArray) parsed;
            if (parsed instanceof JSONObject) return ((JSONObject) parsed).optJSONArray("subjects");
        } catch (Exception ignored) {
        }
        return null;
    }

    private JSONArray suggest(String name, String dtype) {
        HttpUrl url = HttpUrl.parse(BASE + "/j/subject_suggest").newBuilder()
                .addQueryParameter("q", name == null ? "" : name)
                .build();
        String json = get(url.toString());
        if (TextUtils.isEmpty(json)) return null;
        try {
            JSONArray arr = new JSONArray(json);
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String sub = o.optString("subtype", "");
                if (!TextUtils.isEmpty(sub) && !sub.equals(dtype)) continue;
                out.put(o);
            }
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String subjectRate(String id) {
        if (TextUtils.isEmpty(id)) return null;
        HttpUrl url = HttpUrl.parse(BASE + "/subject/" + id + "/").newBuilder().build();
        String html = get(url.toString());
        if (TextUtils.isEmpty(html)) return null;
        int idx = html.indexOf("property=\"v:average\"");
        if (idx < 0) return null;
        int gt = html.indexOf(">", idx);
        int lt = html.indexOf("<", gt);
        if (gt < 0 || lt < 0) return null;
        String v = html.substring(gt + 1, lt).trim();
        return TextUtils.isDigitsOnly(v.replace(".", "")) ? v : null;
    }

    private JSONObject match(JSONArray cands, String name, int year) {
        JSONObject best = null;
        double bs = 0;
        String nn = norm(name);
        for (int i = 0; i < cands.length(); i++) {
            JSONObject c = cands.optJSONObject(i);
            if (c == null) continue;
            String title = c.optString("title", "");
            if (TextUtils.isEmpty(title)) title = c.optString("name", "");
            int cy = WmdbClient.first4Year(c.optString("year", ""));
            boolean yearOk = (cy == 0) || (year == 0) || (Math.abs(cy - year) <= YEAR_TOL);
            double score = WmdbClient.similarity(norm(title), nn) + (yearOk ? 0.1 : 0);
            if (score > bs) {
                bs = score;
                best = c;
            }
        }
        return (best != null && bs >= TITLE_MIN) ? best : null;
    }

    private static String norm(String s) {
        if (TextUtils.isEmpty(s)) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
