package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.Setting;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    /** 评分：豆瓣评分优先，无豆瓣则用站点评分，降序（2026-11-12）。 */
    public static final int SCORE = 4;
    public static final int MAX = SCORE;

    private static final String[] TIME_FORMATS = {
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd",
    };

    private VodSort() {
    }

    /** 按当前偏好返回排序后的列表副本（不改动 Result 里的原始列表）。 */
    public static List<Vod> sorted(Result result) {
        if (result == null) return Collections.emptyList();
        List<Vod> list = new ArrayList<>(result.getList());
        sort(list, Setting.getVodSort());
        return list;
    }

    public static void sort(List<Vod> list, int mode) {
        if (list == null || list.size() < 2) return;
        Comparator<Vod> comparator = comparator(mode);
        if (comparator != null) Collections.sort(list, comparator);
    }

    /** 返回指定排序模式的比较器；DEFAULT 返回 null（保持源站原序）。 */
    public static Comparator<Vod> comparator(int mode) {
        return switch (mode) {
            case YEAR -> byYear();
            case UPLOAD -> byTime();
            case HOT -> byHot();
            case SCORE -> byScore();
            default -> null;
        };
    }

    /**
     * 分栏分页累积排序器（2026-11-12）。
     *
     * <p>采集站每次只返回一页（常见 20 条），旧实现把「每页各自排序后追加到列表尾部」，
     * 于是多次下拉后列表变成一段一段的局部有序：第 2 页里更"新/热"的条目永远排在
     * 第 1 页之后。本类在客户端累积所有已加载页，并把新页条目按比较器<b>插入到
     * 全量有序列表的正确位置</b>，保证任意时刻界面呈现的都是全局有序。
     *
     * <p>插入而非整体重排：已有条目的位置不变，配合 AsyncListDiffer 只做插入动画、
     * 不会把用户正在看的位置跳走。去重按 {@link Vod#equals(Object)}（id 优先，其次名称）。
     * 排序模式切换时由 Fragment 调用 {@link #reset()} 重新以新模式建序。
     */
    public static final class Accumulator {

        private final List<Vod> items = new ArrayList<>();
        private final Set<Vod> seen = new HashSet<>();
        private Comparator<Vod> cmp;

        public Accumulator() {
            reset();
        }

        /** 切换分栏 / 下拉刷新 / 切换排序方式时调用：清空累积并按当前偏好重建。 */
        public void reset() {
            items.clear();
            seen.clear();
            cmp = comparator(Setting.getVodSort());
        }

        /**
         * 从缓存恢复已累积的全量有序列表（2026-11-12）。
         * 缓存写入时已是该排序模式下的有序结果，这里按原顺序恢复、不再重排，
         * 保证条目位置与缓存前完全一致；同时重建去重集合，供后续增量探针复用。
         */
        public void restore(List<Vod> cached, int mode) {
            items.clear();
            seen.clear();
            cmp = comparator(mode);
            if (cached == null) return;
            for (Vod vod : cached) {
                if (vod == null || !seen.add(vod)) continue;
                items.add(vod);
            }
        }

        public boolean isDefault() {
            return cmp == null;
        }

        public int size() {
            return items.size();
        }

        /** 追加一页，返回「全量有序」的新列表副本（供 adapter 整体 setItems）。 */
        public List<Vod> append(Result result) {
            List<Vod> page = result == null ? null : result.getList();
            if (page == null || page.isEmpty()) return new ArrayList<>(items);
            List<Vod> fresh = new ArrayList<>();
            for (Vod vod : page) {
                if (vod == null || !seen.add(vod)) continue;
                fresh.add(vod);
            }
            if (fresh.isEmpty()) return new ArrayList<>(items);
            if (cmp == null) {
                items.addAll(fresh);                       // DEFAULT：保持源站原序
            } else {
                fresh.sort(cmp);
                for (Vod vod : fresh) items.add(insertionPoint(vod), vod);
            }
            return new ArrayList<>(items);
        }

        /** 第一个「应排在 vod 之后」的位置；相等时新页条目排在同值已有条目之后（稳定）。 */
        private int insertionPoint(Vod vod) {
            int low = 0, high = items.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (cmp.compare(items.get(mid), vod) <= 0) low = mid + 1;
                else high = mid;
            }
            return low;
        }

        /** 当前全量有序列表副本（例如切回该分栏但无新页时）。 */
        public List<Vod> snapshot() {
            return new ArrayList<>(items);
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

    /** 评分排序：豆瓣评分优先，豆瓣缺失/为 0 时回退站点评分，降序（2026-11-12）。 */
    private static Comparator<Vod> byScore() {
        return (a, b) -> {
            double sa = scoreValue(a), sb = scoreValue(b);
            if (sa == 0 && sb == 0) return 0;
            if (sa == 0) return 1;
            if (sb == 0) return -1;
            return Double.compare(sb, sa);
        };
    }

    private static double scoreValue(Vod vod) {
        if (vod == null) return 0;
        double douban = num(vod.getDoubanScore());
        if (douban > 0) return douban;
        return num(vod.getScore());
    }

    /** 该源是否带可用评分（无评分的条目在评分排序里排最后）。 */
    public static boolean supportScore(List<Vod> list) {
        if (list == null) return false;
        for (Vod vod : list) if (scoreValue(vod) > 0) return true;
        return false;
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
