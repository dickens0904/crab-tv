package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.bean.Class;
import com.github.catvod.utils.Prefers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 点播分类过滤（家长控制）：把不想看的分栏（伦理片/写真热舞/理论片…）隐藏掉。
 *
 * <p>2026-10-25 用户口径：
 * <ul>
 *   <li>规则<strong>按源分别记录在设备本地</strong>（Prefers，key = "vod_filter_" + 站点 key）：
 *       切到别的源再切回来，被隐藏的分栏仍然是隐藏的</li>
 *   <li>直到在家长控制里把该源的分栏重新打开（取消勾选）</li>
 *   <li>过滤只删命中的分类，同一源内其它正常分栏原样保留</li>
 * </ul>
 * 过滤点在数据层（{@code Result.getTypes()}），电视端与手机端同时生效。
 */
public class ContentFilter {

    /** 内置默认隐藏的关键词（只是默认值，用户可按源改）。 */
    private static final String[] DEFAULT_HIDE = {
            "伦理", "写真", "热舞", "理论", "福利", "三级", "里番", "无码",
            "情色", "色情", "成人", "18禁", "偷拍", "自慰", "裸聊",
    };

    /** 默认不隐藏的正规分类（避免"日韩综艺""动漫"这类被误伤）。 */
    private static final String[] DEFAULT_SHOW = {
            "日韩综艺", "日韩动漫", "日本动漫", "港台综艺", "港台动漫", "综艺", "动漫", "纪录片",
    };

    private static final String PREFIX = "vod_filter_";
    private static final String KEY_PWD = "parent_pwd";
    public static final String DEFAULT_PWD = "122192";

    private static final Gson GSON = new Gson();
    private static final Type TYPE = new TypeToken<Config>() {
    }.getType();

    public static class Config {
        public List<String> hide = new ArrayList<>();
        public List<String> show = new ArrayList<>();
    }

    private ContentFilter() {
    }

    private static String key(String siteKey) {
        return PREFIX + (siteKey == null ? "" : siteKey);
    }

    private static Config config(String siteKey) {
        Config cfg = new Config();
        String json = Prefers.getString(key(siteKey), "");
        if (!json.isEmpty()) {
            try {
                Config parsed = GSON.fromJson(json, TYPE);
                if (parsed != null) cfg = parsed;
            } catch (Exception ignored) {
            }
        }
        return cfg;
    }

    /** 该源生效的"隐藏分类名"集合：默认名单 ∪ 用户勾选隐藏 − 用户勾选显示。 */
    public static Set<String> hiddenNames(String siteKey) {
        Set<String> result = new LinkedHashSet<>(Arrays.asList(DEFAULT_HIDE));
        Config cfg = config(siteKey);
        result.addAll(cfg.hide);
        result.removeAll(cfg.show);
        return result;
    }

    /** 保存该源的显式选择。hide=勾选隐藏的分类，show=取消隐藏的分类。 */
    public static void save(String siteKey, Collection<String> hide, Collection<String> show) {
        Config cfg = new Config();
        cfg.hide = new ArrayList<>(hide);
        cfg.show = new ArrayList<>(show);
        Prefers.put(key(siteKey), GSON.toJson(cfg));
    }

    /** 清除该源的规则（该源的分栏全部恢复显示）。 */
    public static void clear(String siteKey) {
        Prefers.put(key(siteKey), "");
    }

    /** 哪些源已经保存过规则（家长控制里能看到并可恢复）。 */
    public static List<String> savedSites() {
        List<String> result = new ArrayList<>();
        try {
            for (String name : Prefers.getPrefers().getAll().keySet()) {
                if (name != null && name.startsWith(PREFIX) && name.length() > PREFIX.length()) {
                    result.add(name.substring(PREFIX.length()));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /** 分类名在该源下是否应被隐藏。 */
    public static boolean isHidden(String name, String siteKey) {
        if (name == null) return false;
        String n = name.trim();
        if (n.isEmpty()) return false;
        Set<String> hidden = hiddenNames(siteKey);
        for (String h : hidden) if (n.contains(h)) return true;
        return false;
    }

    /** 过滤分类列表：只移除被隐藏的分类，同源其它分类原样保留、顺序不变。 */
    public static List<Class> filter(List<Class> types, String siteKey) {
        if (types == null || types.isEmpty()) return types;
        List<Class> result = new ArrayList<>();
        for (Class type : types) {
            if (type == null) continue;
            if (isHidden(type.getTypeName(), siteKey)) continue;
            result.add(type);
        }
        return result;
    }

    /** 该站所有分类名（含被隐藏的），供「隐藏分类」对话框列出。 */
    public static List<String> names(List<Class> types) {
        List<String> result = new ArrayList<>();
        if (types == null) return result;
        Set<String> seen = new HashSet<>();
        for (Class type : types) {
            if (type == null) continue;
            String name = type.getTypeName();
            if (name != null && !name.isEmpty() && seen.add(name)) result.add(name);
        }
        return result;
    }

    /** 家长控制密码（6 位数字），未设置时为初始密码 122192。 */
    public static String password() {
        String pwd = Prefers.getString(KEY_PWD, "");
        return pwd.isEmpty() ? DEFAULT_PWD : pwd;
    }

    public static boolean checkPassword(String input) {
        return input != null && input.trim().equals(password());
    }

    public static void setPassword(String pwd) {
        Prefers.put(KEY_PWD, pwd == null ? "" : pwd.trim());
    }
}
