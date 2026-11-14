package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.Site;
import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 站点管理：删除（隐藏）+ 手动排序。2026-10-13 手机端需求。
 *
 * <p>背景：Site 是 config JSON 解析出的内存对象（{@code sites[]}），运行期改 bean 不持久化。
 * 这里用 Prefers 持久化两份数据：
 * <ul>
 *   <li>{@code site_hidden_keys}——被删除的站点 key 集合（加载 config 时过滤）；</li>
 *   <li>{@code site_order_keys}——站点 key 的手动排序（加载时重排，未列出的追加在后）。</li>
 * </ul>
 * leanback（TV 端）不提供管理 UI，但共享同一份持久化——电视上未删过站点时两个集合为空，零影响。
 */
public class SiteManager {

    private static final String HIDDEN = "site_hidden_keys";
    private static final String ORDER = "site_order_keys";

    private SiteManager() {
    }

    private static Set<String> hiddenKeys() {
        Set<String> set = new LinkedHashSet<>();
        String raw = Prefers.getString(HIDDEN, "");
        if (!TextUtils.isEmpty(raw)) set.addAll(Arrays.asList(raw.split(",")));
        return set;
    }

    private static List<String> orderKeys() {
        List<String> list = new ArrayList<>();
        String raw = Prefers.getString(ORDER, "");
        if (!TextUtils.isEmpty(raw)) list.addAll(Arrays.asList(raw.split(",")));
        return list;
    }

    private static void putHidden(Set<String> keys) {
        Prefers.put(HIDDEN, TextUtils.join(",", keys));
    }

    private static void putOrder(List<String> keys) {
        Prefers.put(ORDER, TextUtils.join(",", keys));
    }

    /** 过滤已删除站点 + 按手动排序重排。VodConfig/LiveConfig 解析 sites 后调用。 */
    public static List<Site> apply(List<Site> sites) {
        if (sites == null || sites.isEmpty()) return sites;
        Set<String> hidden = hiddenKeys();
        List<String> order = orderKeys();
        if (hidden.isEmpty() && order.isEmpty()) return sites;
        List<Site> out = new ArrayList<>();
        for (Site s : sites) if (!hidden.contains(s.getKey())) out.add(s);
        if (!order.isEmpty()) {
            List<Site> sorted = new ArrayList<>();
            for (String key : order) for (Site s : out) if (key.equals(s.getKey()) && !sorted.contains(s)) sorted.add(s);
            for (Site s : out) if (!sorted.contains(s)) sorted.add(s);
            out = sorted;
        }
        return out;
    }

    /** 删除站点：加入 hidden，从 order 移除。 */
    public static void hide(String key) {
        Set<String> hidden = hiddenKeys();
        hidden.add(key);
        putHidden(hidden);
        List<String> order = orderKeys();
        order.remove(key);
        putOrder(order);
    }

    /** 恢复站点（取消删除）。 */
    public static void show(String key) {
        Set<String> hidden = hiddenKeys();
        hidden.remove(key);
        putHidden(hidden);
    }

    public static boolean isHidden(String key) {
        return hiddenKeys().contains(key);
    }

    /** 在 order 中上移一位；未在 order 里的先追加再上移。 */
    public static void moveUp(String key, List<Site> current) {
        List<String> order = normalizeOrder(current);
        int i = order.indexOf(key);
        if (i > 0) {
            Collections.swap(order, i, i - 1);
            putOrder(order);
        }
    }

    /** 在 order 中下移一位。 */
    public static void moveDown(String key, List<Site> current) {
        List<String> order = normalizeOrder(current);
        int i = order.indexOf(key);
        if (i >= 0 && i < order.size() - 1) {
            Collections.swap(order, i, i + 1);
            putOrder(order);
        }
    }

    /** 把当前内存顺序补全进 order（新站点追加在后）。 */
    private static List<String> normalizeOrder(List<Site> current) {
        List<String> order = orderKeys();
        if (current != null) for (Site s : current) if (!order.contains(s.getKey())) order.add(s.getKey());
        return order;
    }
}
