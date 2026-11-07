package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.bean.Vod;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 点播节目排序（分栏内）。
 *
 * <p>2026-10-25: 各 CMS 站默认返回顺序不统一（多数按更新时间倒序，个别按 id），
 * 且只有苹果 CMS 系给点播量字段。用户要求分栏内可按「时间」「热度」排序。
 *
 * <p>排序在客户端做，字段来自响应：
 * <ul>
 *   <li>时间：{@code vod_time}（形如 2026-10-25 13:24:08），解析不了就排最后</li>
 *   <li>热度：{@code vod_hits} → 没有则 vod_hits_day / week / month → 再没有则
 *       {@code vod_score}；都没有就保持原序（该源不支持热度排序）</li>
 * </ul>
 */
public class VodSort {

    public static final int DEFAULT = 0;
    /** 上映时间（主维度）：vod_year 年份降序，同年份再按上传时间 */
    public static final int YEAR = 1;
    /** 上传时间：vod_time 降序 */
    public static final int UPLOAD = 2;
    /** 热度（点播量） */
    public static final int HOT = 3;
    public static final int MAX = HOT;

    private static final String[] TIME_FORMATS = {
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd",
    };

    private VodSort() {
    }

    /** 按当前偏好返回排序后的列表副本（不改动 Result 里的原始列表）。 */
    public static List<Vod> sorted(com.fongmi.android.tv.bean.Result result) {
        if (result == null) return java.util.Collections.emptyList();
        List<Vod> list = new java.util.ArrayList<>(result.getList());
        sort(list, com.fongmi.android.tv.setting.Setting.getVodSort());
        return list;
    }

    public static void sort(List<Vod> list, int mode) {
        if (list == null || list.size() < 2) return;
        switch (mode) {
            case YEAR:
                Collections.sort(list, byYear());
                break;
            case UPLOAD:
                Collections.sort(list, byTime());
                break;
            case HOT:
                Collections.sort(list, byHot());
                break;
            default:
                break;
        }
    }

    /** 该源是否带可用的排序字段（用于提示"该源不支持热度排序"）。 */
    public static boolean supportHot(List<Vod> list) {
        if (list == null) return false;
        for (Vod vod : list) {
            if (vod == null) continue;
            if (num(vod.getHits()) > 0 || num(vod.getHitsDay()) > 0 || num(vod.getHitsWeek()) > 0
                    || num(vod.getHitsMonth()) > 0 || num(vod.getScore()) > 0) return true;
        }
        return false;
    }

    /** 上映时间：年份降序；同年份按上传时间降序。 */
    private static Comparator<Vod> byYear() {
        return (a, b) -> {
            int ya = year(a), yb = year(b);
            if (ya == 0 && yb == 0) return 0;
            if (ya == 0) return 1;
            if (yb == 0) return -1;
            if (ya != yb) return Integer.compare(yb, ya);
            return Long.compare(time(b), time(a));
        };
    }

    private static int year(Vod vod) {
        if (vod == null) return 0;
        String y = vod.getYear();
        if (y == null) return 0;
        y = y.trim();
        int i = 0;
        while (i < y.length() && Character.isDigit(y.charAt(i))) i++;
        if (i == 0) return 0;
        try {
            return Integer.parseInt(y.substring(0, i));
        } catch (Exception e) {
            return 0;
        }
    }

    private static Comparator<Vod> byTime() {
        return (a, b) -> {
            long ta = time(a), tb = time(b);
            if (ta == 0 && tb == 0) return 0;
            if (ta == 0) return 1;   // 解析不了的排最后
            if (tb == 0) return -1;
            return Long.compare(tb, ta); // 新的在前
        };
    }

    private static Comparator<Vod> byHot() {
        return (a, b) -> {
            double ha = hot(a), hb = hot(b);
            if (ha == 0 && hb == 0) return 0;
            return Double.compare(hb, ha); // 热的在前
        };
    }

    private static double hot(Vod vod) {
        if (vod == null) return 0;
        double h = num(vod.getHits());
        if (h > 0) return h;
        h = num(vod.getHitsDay());
        if (h > 0) return h;
        h = num(vod.getHitsWeek());
        if (h > 0) return h;
        h = num(vod.getHitsMonth());
        if (h > 0) return h;
        return num(vod.getScore());
    }

    private static long time(Vod vod) {
        if (vod == null) return 0;
        String t = vod.getTime();
        if (t == null || t.isEmpty()) return 0;
        t = t.trim();
        for (String f : TIME_FORMATS) {
            try {
                return new SimpleDateFormat(f, Locale.CHINA).parse(t).getTime();
            } catch (Exception ignored) {
            }
        }
        // 兜底：取前 10 位当 yyyy-MM-dd
        if (t.length() >= 10) {
            try {
                return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(t.substring(0, 10)).getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static double num(String s) {
        if (s == null) return 0;
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
