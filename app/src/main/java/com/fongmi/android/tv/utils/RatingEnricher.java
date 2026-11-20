package com.fongmi.android.tv.utils;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.RatingCache;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.db.dao.RatingCacheDao;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 评分补全编排（双端，2026-11-12 盒子版 / 2026-11-14 扩展至手机）。
 *
 * <p>把「片名 + 年份 + 类型」映射到的豆瓣/IMDb 评分，落库到 Room rating_cache，并在渲染点播
 * 列表前回填 {@link Vod#getDoubanScore()}，使「按评分排序」覆盖率从源站原生 ~20% 提升到 ~74%
 * （首装带种子，并随盒子后台自动补全持续增长，逼近 95%+）。
 *
 * <p>数据流：① wmdb（主，豆瓣镜像，一次拿 douban+imdb）；② 原生豆瓣（兜底，补新片实时分）。
 * 严守 wmdb 10s/请求硬限（禁止并发提速），命中缓存即跳过；miss 也缓存(7~30天TTL)避免重复查询，
 * 之后由 sweep 周期清理重试以追上 wmdb 新片滞后。
 *
 * <p>触发：分栏列表加载时懒补全当前页（{@link #enrich}）+ App 启动每日一次全量扫描
 * （{@link #scheduleSweep}）。双端（盒子 leanback / 手机 mobile）均生效，由设置项
 * auto_enrich_ratings 控制开关。所有网络调用在后台线程。
 */
public final class RatingEnricher {

    private static final String TAG = "RatingEnricher";
    private static final String[] TYPES = {"movie", "tv"};
    private static final int PER_SESSION_CAP = 24;     // 单次列表加载至多补全的条数
    private static final int DAILY_CAP = 200;           // 每日全量扫描封顶
    private static final long CACHE_TTL_MS = 30L * 24 * 3600 * 1000;  // 30 天清理/TTL
    private static final AtomicBoolean running = new AtomicBoolean(false);

    private RatingEnricher() {
    }

    /** 渲染前回填：对列表中尚无源站豆瓣分的条目，按 key 命中缓存写入（双类型兜底）。主线程安全。 */
    public static void backfill(List<Vod> list) {
        if (list == null) return;
        RatingCacheDao dao = AppDatabase.get().getRatingCacheDao();
        for (Vod vod : list) {
            if (vod == null || hasScore(vod)) continue;
            RatingCache rc = find(dao, vod);
            if (rc != null && !TextUtils.isEmpty(rc.getDoubanScore())) vod.setDoubanScore(rc.getDoubanScore());
        }
    }

    /**
     * 依次尝试四种 key：原名/清洗名 × 带年份/无年份，movie 与 tv 都试。
     *
     * <p>为什么要这么多组合：内置库来自豆瓣榜单（没有年份），key 是 {@code 片名||类型}；
     * 而 CMS 源给的片名常带"(更至46集)""全季"这类后缀，与豆瓣标题不完全一致。
     * 只按原名+年份查会大面积漏掉。
     */
    private static RatingCache find(RatingCacheDao dao, Vod vod) {
        String raw = vod.getName();
        String clean = cleanName(raw);
        int year = yearOf(vod);
        for (String name : new String[]{raw, clean}) {
            if (TextUtils.isEmpty(name)) continue;
            for (String type : TYPES) {
                RatingCache rc = dao.getByKey(keyOf(name, year, type));
                if (rc != null && !TextUtils.isEmpty(rc.getDoubanScore())) return rc;
            }
        }
        for (String name : new String[]{raw, clean}) {
            if (TextUtils.isEmpty(name)) continue;
            for (String type : TYPES) {
                RatingCache rc = dao.getByKey(keyOf(name, 0, type));
                if (rc != null && !TextUtils.isEmpty(rc.getDoubanScore())) return rc;
            }
        }
        return null;
    }

    /**
     * 懒补全（默认每会话上限 {@link #PER_SESSION_CAP}）。见 {@link #enrich(List, Runnable, int)}。
     */
    public static void enrich(List<Vod> list, Runnable onDone) {
        enrich(list, onDone, PER_SESSION_CAP);
    }

    /**
     * 懒补全：后台串行查询未缓存条目（wmdb 主 + 豆瓣兜底），封顶 cap 条/次。结束回调 onDone（主线程）。
     * 双端均生效；开关关闭时直接回调返回。
     */
    public static void enrich(List<Vod> list, Runnable onDone, int cap) {
        if (list == null || !Setting.getAutoEnrichRatings()) {
            if (onDone != null) App.post(onDone);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            if (onDone != null) App.post(onDone);
            return;
        }
        Task.execute(() -> {
            try {
                WmdbClient wmdb = new WmdbClient();
                DoubanClient douban = new DoubanClient();
                RatingCacheDao dao = AppDatabase.get().getRatingCacheDao();
                dao.deleteOlderThan(System.currentTimeMillis() - CACHE_TTL_MS);
                int n = 0;
                for (Vod vod : list) {
                    if (n >= cap) break;
                    if (vod == null || hasScore(vod)) continue;
                    String type = typeOf(vod);
                    String key = keyOf(vod, type);
                    if (dao.getByKey(key) != null) continue;       // 已缓存(含 miss)则跳过
                    // 2026-11-20: 用清洗后的片名查询。「诛仙 全季」「某某（2024）」这类后缀
                    // 会让 wmdb 的二元组相似度掉到阈值以下，实测是命中率低的主因。
                    String query = cleanName(vod.getName());
                    WmdbClient.Result w = wmdb.search(query, yearOf(vod), type);
                    String ds = (w != null && w.found) ? w.doubanScore : null;
                    String did = (w != null && w.found) ? w.doubanId : null;
                    String imdb = (w != null && w.found) ? w.imdbRating : null;
                    String iid = (w != null && w.found) ? w.imdbId : null;
                    String src = "wmdb";
                    if (TextUtils.isEmpty(ds)) {
                        DoubanClient.Result d = douban.search(query, yearOf(vod), type);
                        if (d != null && d.found && !TextUtils.isEmpty(d.doubanScore)) {
                            ds = d.doubanScore;
                            did = d.doubanId;
                            src = "douban";
                        }
                    }
                    if (!TextUtils.isEmpty(ds) || !TextUtils.isEmpty(imdb)) {
                        dao.insertOrUpdate(new RatingCache(key, ds, did, imdb, iid, src));
                    } else {
                        // 缓存 miss（含 wmdb 新片滞后）：30 天后 sweep 清理重试
                        dao.insertOrUpdate(new RatingCache(key, "", "", "", "", "miss"));
                    }
                    n++;
                }
            } catch (Throwable e) {
                Log.w(TAG, "enrich failed: " + e.getMessage());
            } finally {
                running.set(false);
                if (onDone != null) App.post(onDone);
            }
        });
    }

    /** 每日全量扫描：读取所有已缓存列表，补全未命中条目（封顶 DAILY_CAP）。双端均生效。 */
    public static void sweep() {
        if (!Setting.getAutoEnrichRatings()) return;
        enrich(VodListCache.allItems(), null, DAILY_CAP);
    }

    /** App 启动入口（双端）：导入种子 + 每日一次触发 sweep。 */
    public static void scheduleSweep() {
        importSeed();
        resetMissOnce();
        if (!Setting.getAutoEnrichRatings()) return;
        String today = todayStr();
        if (today.equals(Prefers.getString("rating_last_sweep", ""))) return;
        Prefers.put("rating_last_sweep", today);
        Task.execute(RatingEnricher::sweep);
    }

    /**
     * 5.6.29 起查询用清洗后的片名（去掉"全季/第一季"等），历史 miss 是旧策略的产物，
     * 一次性清掉让它们按新策略重查，否则要等 30 天 TTL 才生效。
     */
    private static void resetMissOnce() {
        if (Prefers.getInt("rating_clean_ver", 0) >= 1) return;
        try {
            AppDatabase.get().getRatingCacheDao().deleteMiss();
            Log.i(TAG, "reset miss cache for cleaned query");
        } catch (Throwable e) {
            Log.w(TAG, "reset miss failed: " + e.getMessage());
        }
        Prefers.put("rating_clean_ver", 1);
    }

    /**
     * 内置库版本。豆瓣榜单抓取结果随包发布（assets/rating_seed.json.gz，约 6.3k 条），
     * 版本变大时整包重导一次；条目 key 为「片名||类型」（豆瓣榜单没有年份）。
     */
    private static final int SEED_VERSION = 2;

    private static void importSeed() {
        if (Prefers.getInt("rating_seed_ver", 0) >= SEED_VERSION) return;
        try {
            // AAPT 会把 assets 里的 .gz 自动解压，运行时文件名是去掉 .gz 的
            InputStream is = App.get().getAssets().open("rating_seed.json");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
            is.close();
            JSONArray arr = new JSONArray(bos.toString("UTF-8"));
            RatingCacheDao dao = AppDatabase.get().getRatingCacheDao();
            java.util.List<RatingCache> batch = new java.util.ArrayList<>(512);
            int n = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String ds = optStr(o, "douban_score");
                String ir = optStr(o, "imdb_rating");
                if (TextUtils.isEmpty(ds) && TextUtils.isEmpty(ir)) continue;
                String name = optStr(o, "name").trim().toLowerCase();
                if (TextUtils.isEmpty(name)) continue;
                String yr = String.valueOf(WmdbClient.first4Year(optStr(o, "year")));
                String type = optStr(o, "media_type").trim().toLowerCase();
                if (TextUtils.isEmpty(type)) type = "movie";
                String key = name + "|" + yr + "|" + type;
                batch.add(new RatingCache(key, ds, optStr(o, "douban_id"), ir, optStr(o, "imdb_id"), optStr(o, "source")));
                n++;
                if (batch.size() >= 500) {
                    dao.insertAll(batch);   // 单事务批量写，避免 6k 次单独提交
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) dao.insertAll(batch);
            Log.d(TAG, "rating seed imported: " + n + " ver=" + SEED_VERSION);
            Prefers.put("rating_seed_ver", SEED_VERSION);
        } catch (Throwable e) {
            Log.w(TAG, "seed import failed: " + e.getMessage());
        }
    }

    // ---- key / 工具 ----

    /** 缓存 key：片名.trim().toLowerCase() | 四位年份 | movie/tv（与离线种子、运行期完全一致，不去标点）。 */
    private static String keyOf(Vod vod, String type) {
        return keyOf(vod.getName(), yearOf(vod), type);
    }

    private static String keyOf(String name, int year, String type) {
        String n = name == null ? "" : name.trim().toLowerCase();
        return n + "|" + (year == 0 ? "" : year) + "|" + type;
    }

    /** 片名清洗：去掉括号补充信息与"全季/第一季/更新至xx/国语版"这类后缀，只用于查询。 */
    private static String cleanName(String name) {
        if (TextUtils.isEmpty(name)) return "";
        String s = name.trim();
        s = s.replaceAll("[（(【\\[][^）)】\\]]*[）)】\\]]", "");
        s = s.replaceAll("第[一二三四五六七八九十\\d]+季|全季|全集|完结|更新至.*|国语版?|粤语版?|TV版|电影版|高清版?|HD|4K", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? name.trim() : s;
    }

    private static int yearOf(Vod vod) {
        return WmdbClient.first4Year(vod.getYear());
    }

    private static String typeOf(Vod vod) {
        String t = vod.getTypeName();
        if (!TextUtils.isEmpty(t) && (t.contains("剧") || t.contains("电视") || t.contains("综艺"))) return "tv";
        return "movie";
    }

    private static boolean hasScore(Vod vod) {
        return !TextUtils.isEmpty(vod.getDoubanScore());
    }

    private static String todayStr() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    }

    private static String optStr(JSONObject o, String key) {
        try {
            String v = o.optString(key, "");
            return (v == null || "null".equals(v)) ? "" : v.trim();
        } catch (Exception e) {
            return "";
        }
    }
}
