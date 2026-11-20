package com.fongmi.android.tv.player.exo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.HttpUrl;

/**
 * HLS 点播广告段过滤（2026-11-19）。
 *
 * <p>判断依据来自实测：juliang 一类源的插播广告<b>不在正片域名下</b>
 * （正片 v13/v14.wsyzyts.vip 等，广告固定 admedia.mujiziyuan99.com），
 * 而且广告块自身在 playlist 里是独立的 {@code #EXT-X-DISCONTINUITY} 分段。
 *
 * <p>因此规则是「去掉整块跑在别的域名上的分段」，而不是见 DISCONTINUITY 就砍——
 * 实测一个 1748 段的片子里 207 个 DISCONTINUITY，只有 4 个是真的换域名，
 * 其余都是同域编码拼接点，砍掉会直接丢正片。
 *
 * <p>三条保护：
 * <ul>
 *   <li>只处理点播 playlist（有 {@code #EXT-X-ENDLIST} 或 {@code PLAYLIST-TYPE:VOD}），直播不动；</li>
 *   <li>只删「整块都是外域分片」的块，混合块一律保留；</li>
 *   <li>要删的段数超过总段数 25% 时判定误伤，整体放弃过滤。</li>
 * </ul>
 */
public final class HlsAdFilter {

    /** 误删保护：外域段占比超过这个值就认为不是广告而是源本身多 CDN，放弃过滤。 */
    private static final double MAX_REMOVE_RATIO = 0.25;
    /** 广告总时长上限（秒）：超过就不是插播广告了。 */
    private static final double MAX_AD_SECONDS = 180;
    /** 广告时长占全片比例上限。 */
    private static final double MAX_AD_RATIO = 0.10;

    private HlsAdFilter() {
    }

    /**
     * @param text 原始 media playlist 文本
     * @param base playlist 自身的绝对地址（用于解析相对分片路径）
     * @return 过滤后的文本；不需要/不适合过滤时原样返回
     */
    public static String filter(String text, String base) {
        if (text == null || !text.contains("#EXTINF")) return text;
        boolean vod = text.contains("#EXT-X-ENDLIST") || text.contains("#EXT-X-PLAYLIST-TYPE:VOD");
        if (!vod) return text;

        String[] lines = text.split("\r?\n", -1);
        // 1) 统计每段所属的 host/路径组，出现最多的那组是正片
        Map<String, Integer> counts = new HashMap<>();
        int total = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String key = groupOf(base, line);
            if (key == null) continue;
            counts.merge(key, 1, Integer::sum);
            total++;
        }
        if (total == 0 || counts.size() < 2) return text;
        String main = null;
        int best = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                main = entry.getKey();
            }
        }
        if (main == null) return text;

        // 2) 按 DISCONTINUITY 切块，标记「整块都是外域」的块
        List<int[]> blocks = new ArrayList<>();   // 每个块的行区间 [start, end)
        List<Boolean> adBlock = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= lines.length; i++) {
            boolean boundary = i == lines.length || "#EXT-X-DISCONTINUITY".equals(lines[i].trim());
            if (!boundary) continue;
            blocks.add(new int[]{start, i});
            adBlock.add(isAdBlock(lines, start, i, base, main));
            start = i + 1;
        }
        int adSegments = 0;
        double adSeconds = 0;
        double totalSeconds = 0;
        for (int b = 0; b < blocks.size(); b++) {
            int[] range = blocks.get(b);
            double seconds = blockSeconds(lines, range[0], range[1]);
            totalSeconds += seconds;
            if (!adBlock.get(b)) continue;
            adSeconds += seconds;
            for (int i = range[0]; i < range[1]; i++) {
                String s = lines[i].trim();
                if (!s.isEmpty() && !s.startsWith("#")) adSegments++;
            }
        }
        // 时长护栏：插播广告都很短（实测 24~56s，占全片 <2%）。
        // 如果"外域块"占了相当比重，那不是广告而是源用了多 CDN，不能删。
        if (adSeconds <= 0 || adSeconds > MAX_AD_SECONDS || (totalSeconds > 0 && adSeconds > totalSeconds * MAX_AD_RATIO)) return text;
        if (adSegments == 0 || adSegments > total * MAX_REMOVE_RATIO) return text;

        // 3) 重建：丢掉广告块，以及紧贴广告块的那条 DISCONTINUITY（否则留下空分隔）
        StringBuilder out = new StringBuilder(text.length());
        for (int b = 0; b < blocks.size(); b++) {
            int[] range = blocks.get(b);
            if (adBlock.get(b)) {
                continue;
            }
            boolean nextIsAd = b + 1 < blocks.size() && adBlock.get(b + 1);
            boolean prevIsAd = b > 0 && adBlock.get(b - 1);
            for (int i = range[0]; i < range[1]; i++) out.append(lines[i]).append('\n');
            // 块与块之间那条 DISCONTINUITY：前后任一邻居是广告就不写
            if (b < blocks.size() - 1 && !nextIsAd && !prevIsAd) {
                out.append("#EXT-X-DISCONTINUITY").append('\n');
            }
        }
        return out.toString();
    }

    /** 块内所有 #EXTINF 的时长之和（秒）。 */
    private static double blockSeconds(String[] lines, int start, int end) {
        double total = 0;
        for (int i = start; i < end; i++) {
            String s = lines[i].trim();
            if (!s.startsWith("#EXTINF:")) continue;
            try {
                int comma = s.indexOf(',');
                String value = comma > 8 ? s.substring(8, comma) : s.substring(8);
                total += Double.parseDouble(value.trim());
            } catch (Exception ignored) {
            }
        }
        return total;
    }

    /** 整块分片都不属于正片域名才算广告块；只要混进一个正片分片就整块保留。 */
    private static boolean isAdBlock(String[] lines, int start, int end, String base, String main) {
        boolean any = false;
        for (int i = start; i < end; i++) {
            String s = lines[i].trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            any = true;
            if (main.equals(groupOf(base, s))) return false;
        }
        return any;
    }

    private static String groupOf(String base, String url) {
        HttpUrl parsed = resolve(base, url);
        if (parsed == null) return null;
        List<String> segments = parsed.pathSegments();
        StringBuilder key = new StringBuilder(parsed.host());
        for (int i = 0; i < Math.min(2, segments.size()); i++) key.append('/').append(segments.get(i));
        return key.toString().toLowerCase(Locale.ROOT);
    }

    private static HttpUrl resolve(String base, String url) {
        try {
            HttpUrl root = base == null ? null : HttpUrl.parse(base);
            return root == null ? HttpUrl.parse(url) : root.resolve(url);
        } catch (Exception e) {
            return null;
        }
    }
}
