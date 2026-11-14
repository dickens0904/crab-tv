package com.fongmi.android.tv.utils;

import android.util.LruCache;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.utils.Path;

import com.google.gson.annotations.SerializedName;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 点播分栏列表缓存（2026-11-12）。
 *
 * <p>解决「下拉加载几十页后，出去进来又只剩第一页几十条」：累积后的全量有序列表
 * 连同分页游标一起缓存，进入分栏先秒开缓存，再拿第 1 页作<b>增量探针</b>——
 * 无新增就原样不动（不重排、不跳滚动位置），有新增才按排序规则插入并更新缓存。
 *
 * <p>两级存储：进程内 LruCache 优先（App 内切页面零延迟），磁盘 Gson JSON 兜底
 * （重启 App 仍在）。缓存不做硬 TTL 拦截：是否更新由「第 1 页增量对比」决定，
 * 只有超过 {@link #MAX_AGE} 的僵尸文件才在清理时删除。下拉刷新 / 切排序 /
 * 切筛选 / 切源时由调用方 {@link #invalidate(String)} 显式失效。
 *
 * <p>key 含 siteKey / typeId / 排序模式 / 筛选条件，条件一变自然不命中，不会串数据。
 */
public final class VodListCache {

    public static final class Entry {
        @SerializedName("key") public String key;
        @SerializedName("items") public List<Vod> items = new ArrayList<>();
        @SerializedName("page") public int page = 1;
        @SerializedName("pageCount") public int pageCount;
        @SerializedName("sortMode") public int sortMode;
        @SerializedName("savedAt") public long savedAt;
    }

    private static final int MAX_ITEMS = 1000;
    private static final int MAX_DISK_FILES = 60;
    private static final long MAX_AGE = 7L * 24 * 3600 * 1000;
    private static final LruCache<String, Entry> MEMORY = new LruCache<>(24);
    private static final File DIR = Path.cache("vod_list");

    private VodListCache() {
    }

    /** 排序模式/筛选条件变化 → key 变化 → 旧缓存自动不命中。 */
    public static String key(String siteKey, String typeId, int sortMode, Map<String, String> extend) {
        TreeMap<String, String> sorted = extend == null ? new TreeMap<>() : new TreeMap<>(extend);
        return siteKey + "|" + typeId + "|" + sortMode + "|" + sorted;
    }

    private static File file(String key) {
        return new File(DIR, Integer.toHexString(key.hashCode()) + ".json");
    }

    /**
     * 归一化磁盘缓存里的 items：旧版本 R8 混淆偶发把 items 反序列化成 LinkedTreeMap 集合，
     * 直接当 {@code List<Vod>} 迭代会强转崩溃。这里统一经由 {@link Vod#arrayFrom} 重新解析
     * （LinkedTreeMap 本质仍是合法 Vod JSON，可完整还原），并兜底挑出真正的 Vod。
     */
    private static List<Vod> safeItems(Entry e) {
        if (e == null || e.items == null) return new ArrayList<>();
        try {
            return Vod.arrayFrom(App.gson().toJson(e.items));
        } catch (Throwable t) {
            List<Vod> out = new ArrayList<>();
            for (Object o : e.items) if (o instanceof Vod) out.add((Vod) o);
            return out;
        }
    }

    /** 内存优先，未命中读磁盘；返回 null 表示需要走完整加载。 */
    public static Entry get(String key) {
        if (key == null) return null;
        Entry memory = MEMORY.get(key);
        if (memory != null && !memory.items.isEmpty()) return memory;
        try {
            File f = file(key);
            if (!f.isFile()) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            Entry disk = App.gson().fromJson(sb.toString(), Entry.class);
            if (disk == null || !key.equals(disk.key)) return null;
            disk.items = safeItems(disk);
            if (disk.items.isEmpty()) {
                f.delete();
                return null;
            }
            if (System.currentTimeMillis() - disk.savedAt > MAX_AGE) {
                f.delete();
                return null;
            }
            MEMORY.put(key, disk);
            return disk;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 每次加载一页 / 探针发现增量后调用；磁盘写入异步，不阻塞 UI。 */
    public static void put(String key, List<Vod> items, int page, int pageCount, int sortMode) {
        if (key == null || items == null || items.isEmpty()) return;
        List<Vod> copy = new ArrayList<>(items.size() > MAX_ITEMS ? items.subList(0, MAX_ITEMS) : items);
        Entry entry = new Entry();
        entry.key = key;
        entry.items = copy;
        entry.page = Math.max(1, page);
        entry.pageCount = pageCount;
        entry.sortMode = sortMode;
        entry.savedAt = System.currentTimeMillis();
        MEMORY.put(key, entry);
        Task.execute(() -> writeDisk(key, entry));
    }

    private static void writeDisk(String key, Entry entry) {
        try {
            if (!DIR.exists() && !DIR.mkdirs()) return;
            File f = file(key);
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                App.gson().toJson(entry, writer);
            }
            cleanup();
        } catch (Throwable ignored) {
        }
    }

    /** 下拉刷新 / 切排序 / 切筛选 / 切源时显式失效。 */
    public static void invalidate(String key) {
        if (key == null) return;
        MEMORY.remove(key);
        try {
            file(key).delete();
        } catch (Throwable ignored) {
        }
    }

    public static void invalidateAll() {
        MEMORY.evictAll();
        try {
            File[] files = DIR.listFiles();
            if (files != null) for (File f : files) f.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 读取磁盘上所有缓存条目（供评分补全每日全量扫描）。 */
    public static List<Vod> allItems() {
        List<Vod> out = new ArrayList<>();
        try {
            File[] files = DIR.listFiles();
            if (files == null) return out;
            for (File f : files) {
                if (!f.isFile() || !f.getName().endsWith(".json")) continue;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    Entry e = App.gson().fromJson(sb.toString(), Entry.class);
                    out.addAll(safeItems(e));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 控制磁盘占用：只保留最近 MAX_DISK_FILES 个文件，顺带清掉过期文件。 */
    private static void cleanup() {
        try {
            File[] files = DIR.listFiles();
            if (files == null) return;
            long now = System.currentTimeMillis();
            for (File f : files) if (now - f.lastModified() > MAX_AGE) f.delete();
            files = DIR.listFiles();
            if (files == null || files.length <= MAX_DISK_FILES) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < files.length - MAX_DISK_FILES; i++) files[i].delete();
        } catch (Throwable ignored) {
        }
    }
}
