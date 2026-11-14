package com.fongmi.android.tv.utils;

import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

import okhttp3.HttpUrl;
import okhttp3.Request;

/**
 * wmdb.tv（douban-imdb-api）客户端：豆瓣数据镜像，按中文片名 + 年份返回豆瓣/IMDb 评分。
 *
 * <p>为什么用它是主路径：豆瓣对受限 IP 风控（搜索/详情页返回验证码），直爬拿不到评分；
 * wmdb 镜像了 douban 原值，返回的 doubanId / doubanRating 即豆瓣原值，且一次查询同时给出
 * imdbRating，性价比高。接口：GET https://api.wmdb.tv/api/v1/movie/search?q=片名&lang=Cn&limit=5
 * 免费档限速 10 秒/请求（本类内置 throttle，务必遵守，严禁并发提速）。
 *
 * <p>匹配：以片名相似度为主（去标点后的二元组 Jaccard）；年份以 dateReleased 优先、year 字段
 * 为辅，容差放宽到 ±2（wmdb 的 year 字段偶尔偏差 ±1）；同名错年空评分条目重罚，避免抢冠军。
 *
 * <p>仅在后台线程调用（RatingEnricher 已保证）。
 */
public class WmdbClient {

    private static final String TAG = "WmdbClient";
    private static final String BASE = "https://api.wmdb.tv/api/v1/movie";
    private static final String UA = "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final long DELAY_MS = 11_000L;        // 10s/req + 1s 余量防临界 429
    private static final int MAX_RETRIES = 3;
    private static final double TITLE_MIN = 0.6;
    private static final int YEAR_TOL = 2;

    public static class Result {
        public boolean found;
        public String doubanScore;
        public String doubanId;
        public String imdbRating;
        public String imdbId;
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

    private String getJson(String url) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                throttle();
                okhttp3.Response res = OkHttp.client().newCall(new Request.Builder().url(url)
                        .header("User-Agent", UA)
                        .header("Accept-Language", "zh-CN,zh;q=0.9")
                        .build()).execute();
                int code = res.code();
                if (code == 200) {
                    String body = res.body().string();
                    res.close();
                    return body;
                }
                if (code == 429) {                      // 10s 限速
                    Thread.sleep(DELAY_MS * attempt);
                    continue;
                }
                Log.w(TAG, "unexpected code " + code + " for " + url);
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
        HttpUrl url = HttpUrl.parse(BASE + "/search").newBuilder()
                .addQueryParameter("q", name == null ? "" : name)
                .addQueryParameter("lang", "Cn")
                .addQueryParameter("limit", "5")
                .build();
        String json = getJson(url.toString());
        if (TextUtils.isEmpty(json)) return empty;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray items = root.optJSONArray("data");
            if (items == null || items.length() == 0) return empty;
            JSONObject best = best(items, name, year);
            if (best == null) return empty;
            Result r = new Result();
            r.found = true;
            r.doubanScore = optStr(best, "doubanRating");
            r.doubanId = optStr(best, "doubanId");
            r.imdbRating = optStr(best, "imdbRating");
            r.imdbId = optStr(best, "imdbId");
            if (TextUtils.isEmpty(r.doubanScore) && TextUtils.isEmpty(r.imdbRating)) r.found = false;
            return r;
        } catch (Exception e) {
            Log.w(TAG, "parse failed: " + e.getMessage());
            return empty;
        }
    }

    private JSONObject best(JSONArray items, String name, int year) {
        JSONObject best = null;
        double bs = 0;
        String nn = norm(name);
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            double sim = 0;
            sim = Math.max(sim, sim(nn, norm(optStr(it, "originalName"))));
            sim = Math.max(sim, sim(nn, norm(optStr(it, "name"))));
            String alias = optStr(it, "alias");
            if (!TextUtils.isEmpty(alias)) {
                for (String part : alias.split("/")) sim = Math.max(sim, sim(nn, norm(part)));
            }
            int y = itemYear(it);
            boolean yearOk = (y == 0) || (year == 0) || (Math.abs(y - year) <= YEAR_TOL);
            double score = sim;
            if (yearOk) score += 0.15;
            else score -= 0.5;                            // 同名错年条目重罚
            if (!TextUtils.isEmpty(optStr(it, "doubanRating")) || !TextUtils.isEmpty(optStr(it, "imdbRating"))) {
                score += 0.08;                            // 已有评分的版本加权
            }
            if (score > bs) {
                bs = score;
                best = it;
            }
        }
        return (best != null && bs >= TITLE_MIN) ? best : null;
    }

    private static int itemYear(JSONObject it) {
        String dr = optStr(it, "dateReleased");
        Integer y = first4(dr);
        if (y != null) return y;
        y = first4(optStr(it, "year"));
        return y == null ? 0 : y;
    }

    /** 取字符串中第一个连续 4 位数字年份；无则返回 null。 */
    private static Integer first4(String s) {
        if (TextUtils.isEmpty(s)) return null;
        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i))) i++;
        if (i + 4 > s.length()) return null;
        try {
            return Integer.parseInt(s.substring(i, i + 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 匹配用规范化：去空白/标点，统一小写（与离线种子/运行期 key 规范化不同，key 不去标点）。 */
    private static String norm(String s) {
        if (TextUtils.isEmpty(s)) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 片名相似度：去标点后字符二元组 Jaccard（对 CJK 短标题稳定）。 */
    private static double sim(String a, String b) {
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) return 0;
        if (a.equals(b)) return 1;
        Set<String> ba = bigrams(a), bb = bigrams(b);
        if (ba.isEmpty() || bb.isEmpty()) return a.equals(b) ? 1 : 0;
        int inter = 0;
        for (String s : ba) if (bb.contains(s)) inter++;
        int union = ba.size() + bb.size() - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    private static Set<String> bigrams(String s) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) set.add(s.substring(i, i + 2));
        if (s.length() == 1) set.add(s);
        return set;
    }

    private static String optStr(JSONObject o, String key) {
        String v = o.optString(key, "");
        return TextUtils.isEmpty(v) || "null".equals(v) ? "" : v.trim();
    }

    /** 供 DoubanClient 复用：取字符串首个 4 位年份（无返回 0）。 */
    public static int first4Year(String s) {
        Integer v = first4(s);
        return v == null ? 0 : v;
    }

    /** 供 DoubanClient 复用：片名相似度（二元组 Jaccard）。 */
    public static double similarity(String a, String b) {
        return sim(a, b);
    }
}
