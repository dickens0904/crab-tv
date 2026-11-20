package com.fongmi.android.tv.api;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 首页「发现多仓」：实时调用 GitHub Search API 搜多仓相关仓库 → 定位仓库内的 config JSON →
 * 逐个测连通（HTTP 200 且能解析为合法多仓/配置）→ 测试通过的**全自动**写入 Room Config 表。
 *
 * 红线：
 * 1. 只新增旁路，不改 VodConfig / LiveConfig 既有解析逻辑；
 * 2. insert 前必须 ConfigDao.find(url, type) 判重（Config 表 (url,type) 唯一 + BaseDao.insert 是 IGNORE）；
 * 3. GitHub 限流/403 必须优雅停止并提示，不得崩。
 */
public class GitHubDepotSearch {

    private static final String TAG = "GitHubDepot";

    /** Config 表 type：与 {@code bean.Config#vod()/live()} 语义一致（api.config.BaseConfig 的 VOD/LIVE 是包私有，此处复刻常量）。 */
    public static final int VOD = 0;
    public static final int LIVE = 1;

    private static final int MASK_VOD = 1;
    private static final int MASK_LIVE = 1 << 1;

    // ------------------------------------------------------------ 源类型（jar 依赖 vs 纯 HTTP 接口）
    // 背景：第三方 spider jar 内部硬编码调用腾讯 X5（Util.hasX5WebView:false），本构建不含 X5，
    // 这类源在手机/盒子上都取不到数据（进入后是「多半依赖 X5/JS 引擎，本程序未内置」空栏目）。
    // 纯 HTTP 源（sites[].api 直接是 CMS 接口、无 spider/jar/csp_）则一定能用。
    // 所以搜索结果行直接标出类型，让用户一眼避坑，而不是挨个点进去试。

    /** 源类型：纯 HTTP 接口，拿到就能播。 */
    public static final int TYPE_API = 0;
    /** 源类型：依赖第三方 spider（顶层 spider / sites[].jar / api 以 csp_ 开头），本构建很可能取不到数据。 */
    public static final int TYPE_JAR = 1;
    /** 源类型：判定不了。多仓（urls[]）本体不含站点信息，要看子配置才知道。 */
    public static final int TYPE_UNKNOWN = 2;

    private static final String API = "https://api.github.com";
    private static final String RAW = "https://raw.githubusercontent.com";
    private static final String CDN = "https://cdn.jsdelivr.net/gh";

    private static final int TIMEOUT = 10_000;
    private static final int PER_PAGE = 20;
    /** repo 处理上限（每个 repo 至少 1 次 contents 调用，按未登录 core 限流 60/h 留足余量）。 */
    private static final int REPO_LIMIT = 20;
    /** api.github.com 子请求总预算（6 次搜索 + N 次 contents），未登录 core limit = 60/h、search limit = 10/min。 */
    private static final int API_BUDGET = 30;
    /** 候选 config URL 探测上限。 */
    private static final int PROBE_LIMIT = 30;
    /** 每个 repo 最多探测的仓库内 json 数量。 */
    private static final int FILE_LIMIT = 3;
    /** 每个 repo 最多探测的 README 链接数量。 */
    private static final int README_LIMIT = 5;
    /** 整体时长上限，避免弱网下无限拖住 UI。 */
    private static final long DEADLINE = TimeUnit.MINUTES.toMillis(4);

    private static final List<String> KEYWORDS = List.of("TVBox 多仓", "影视仓 配置", "tvbox config", "FongMi 配置", "影视 仓库", "box 订阅");

    private static final Set<String> FILE_SKIP = Set.of("package.json", "package-lock.json", "composer.json", "tsconfig.json", "jsconfig.json", "manifest.json", "bom.json", "deno.json", "biome.json", "eslint.json", "appsettings.json", "launch.json", "settings.json");

    private static final List<String> FILE_PREFER = List.of("config.json", "tvbox.json", "tv.json", "box.json", "cat.json", "cats.json", "interface.json", "api.json", "source.json", "sources.json", "spider.json", "live.json", "lives.json", "diyp.json", "juhe.json");

    private static final Pattern LINK = Pattern.compile("https?://(?:raw\\.githubusercontent\\.com|cdn\\.jsdelivr\\.net/gh)/[^\\s)\"'<>\\\\]+", Pattern.CASE_INSENSITIVE);

    /** 复用工程既有 OkHttp 客户端（catvod OkHttp 单例），只额外加一层 GitHub 请求头并收窄超时。 */
    private static final OkHttpClient CLIENT = OkHttp.client(TIMEOUT).newBuilder().addInterceptor(new GitHubHeader()).build();

    /** 进度阶段：正在搜 GitHub 仓库。 */
    public static final String STAGE_SEARCH = "search";
    /** 进度阶段：正在逐条探测候选 config 的连通性。 */
    public static final String STAGE_PROBE = "probe";
    /** 进度阶段：正在把测通的 config 写入 Config 表。 */
    public static final String STAGE_ADD = "add";

    public interface Listener {

        /**
         * 搜索全程进度回调（统一切主线程，不阻塞搜索线程）。
         *
         * @param stage {@link #STAGE_SEARCH} / {@link #STAGE_PROBE} / {@link #STAGE_ADD}
         * @param done  当前阶段已完成条数
         * @param total 当前阶段总条数（探测阶段为上限预算，进源阶段为已测通条数）
         */
        void onProgress(String stage, int done, int total);

        void onDone(Summary summary);
    }

    private static final class GitHubHeader implements Interceptor {

        @NonNull
        @Override
        public Response intercept(@NonNull Chain chain) throws IOException {
            Request request = chain.request().newBuilder().header("Accept", "application/vnd.github+json").header("User-Agent", "FongMi-TV").build();
            return chain.proceed(request);
        }
    }

    /** 一条候选 config URL 的探测结果。 */
    public static final class Result {

        public final String repo;
        public final String name;
        public final String url;
        /** 通过时为 MASK_VOD / MASK_LIVE 组合，失败时为 0。 */
        public final int mask;
        public final boolean pass;
        public final String message;
        /**
         * 源类型，见 {@link #TYPE_API} / {@link #TYPE_JAR} / {@link #TYPE_UNKNOWN}。
         * 2026-10-13 新增：让用户在结果列表里一眼看出这个源会不会因为 jar/X5 依赖而空栏目。
         * 失败候选（连不上/非法配置）拿不到内容，一律 TYPE_UNKNOWN。
         */
        public final int sourceType;

        private Result(String repo, String name, String url, int mask, boolean pass, String message, int sourceType) {
            this.repo = repo;
            this.name = name;
            this.url = url;
            this.mask = mask;
            this.pass = pass;
            this.message = message;
            this.sourceType = sourceType;
        }

        public static Result ok(String repo, String name, String url, int mask, int sourceType) {
            return new Result(repo, name, url, mask, true, types(mask), sourceType);
        }

        public static Result fail(String repo, String name, String url, String message) {
            return new Result(repo, name, url, 0, false, message, TYPE_UNKNOWN);
        }

        /**
         * 是否需要 jar / 爬虫依赖。
         *
         * @return {@code true}=爬虫依赖（本构建大概率取不到数据）；{@code false}=纯 HTTP 接口（可用）；
         * {@code null}=判定不了（多仓未展开，或候选压根没连上）。UI 据此上色。
         */
        @Nullable
        public Boolean needsJar() {
            if (sourceType == TYPE_JAR) return Boolean.TRUE;
            if (sourceType == TYPE_API) return Boolean.FALSE;
            return null;
        }

        private static String types(int mask) {
            StringBuilder builder = new StringBuilder();
            if ((mask & MASK_VOD) == MASK_VOD) builder.append("VOD");
            if ((mask & MASK_LIVE) == MASK_LIVE) builder.append(builder.length() == 0 ? "LIVE" : "+LIVE");
            return builder.toString();
        }
    }

    public static final class Summary {

        /** 实际进入探测阶段的 repo 数。 */
        public int repos;
        /** 候选 config URL 总数。 */
        public int candidates;
        /** 连通性测试通过数。 */
        public int pass;
        /** 连通性测试失败数。 */
        public int fail;
        /** 实际新写入 Config 表的条数。 */
        public int added;
        /** 已存在而跳过的条数（url+type 去重命中）。 */
        public int duplicate;
        /** 是否被 GitHub 限流中断。 */
        public boolean limited;
        /** 是否因整体超时提前收尾。 */
        public boolean timeout;
        /** 全局异常信息（网络不可达等）。 */
        public String error;
        /** 全部候选明细（含失败），供结果页展示。 */
        public final List<Result> results = Collections.synchronizedList(new ArrayList<>());

        /** 内部：api.github.com 子请求计数。 */
        int apiUsed;
        /** 内部：整体截止时间戳。 */
        long deadline;
    }

    private GitHubDepotSearch() {
    }

    /**
     * 启动一次「搜多仓 → 测连通 → 自动进源」。全程在工作线程执行，回调统一切主线程。
     */
    public static void search(@Nullable Listener listener) {
        Task.submitLarge(() -> {
            Summary summary = new Summary();
            summary.deadline = System.currentTimeMillis() + DEADLINE;
            try {
                run(summary, listener);
            } catch (Throwable e) {
                Log.e(TAG, "search failed", e);
                summary.error = describe(e);
            }
            if (listener != null) {
                Summary result = summary;
                App.post(() -> listener.onDone(result));
            }
        });
    }

    private static void run(Summary summary, @Nullable Listener listener) {
        Map<String, Repo> repos = searchRepos(summary, listener);
        Set<String> visited = new LinkedHashSet<>();
        int count = 0;
        for (Repo repo : repos.values()) {
            if (summary.limited || expired(summary)) break;
            if (summary.candidates >= PROBE_LIMIT || summary.apiUsed >= API_BUDGET) break;
            probeRepo(repo, summary, visited, listener);
            count++;
        }
        summary.repos = count;
        summary.timeout = expired(summary);
    }

    private static boolean expired(Summary summary) {
        return summary.deadline > 0 && System.currentTimeMillis() > summary.deadline;
    }

    // ---------------------------------------------------------------- 阶段一：搜仓库

    private static Map<String, Repo> searchRepos(Summary summary, @Nullable Listener listener) {
        Map<String, Repo> repos = new LinkedHashMap<>();
        int index = 0;
        for (String keyword : KEYWORDS) {
            if (summary.limited || expired(summary)) break;
            if (summary.apiUsed >= API_BUDGET) break;
            index++;
            progress(listener, STAGE_SEARCH, index, KEYWORDS.size());
            String url = Objects.requireNonNull(HttpUrl.parse(API + "/search/repositories")).newBuilder().addQueryParameter("q", keyword).addQueryParameter("sort", "stars").addQueryParameter("order", "desc").addQueryParameter("per_page", String.valueOf(PER_PAGE)).build().toString();
            summary.apiUsed++;
            String body = get(url, summary);
            if (body == null) continue;
            try {
                JsonElement element = Json.parse(body);
                if (!element.isJsonObject()) continue;
                JsonElement items = element.getAsJsonObject().get("items");
                if (items == null || !items.isJsonArray()) continue;
                for (JsonElement opt : items.getAsJsonArray()) {
                    if (!opt.isJsonObject()) continue;
                    String full = Json.safeString(opt.getAsJsonObject(), "full_name");
                    if (TextUtils.isEmpty(full) || repos.containsKey(full)) continue;
                    repos.put(full, Repo.parse(opt.getAsJsonObject()));
                }
            } catch (Throwable e) {
                Log.e(TAG, "parse search result failed", e);
            }
        }
        List<Repo> items = new ArrayList<>(repos.values());
        items.sort((a, b) -> b.stars - a.stars);
        Map<String, Repo> result = new LinkedHashMap<>();
        for (Repo repo : items) {
            if (result.size() >= REPO_LIMIT) break;
            result.put(repo.full, repo);
        }
        return result;
    }

    // ---------------------------------------------------------------- 阶段二：定位 + 测连通

    private static void probeRepo(Repo repo, Summary summary, Set<String> visited, @Nullable Listener listener) {
        List<String> candidates = new ArrayList<>();
        if (!summary.limited && summary.apiUsed < API_BUDGET) {
            summary.apiUsed++;
            collectFiles(repo, summary, candidates);
        }
        collectReadme(repo, candidates);
        for (String url : candidates) {
            if (summary.candidates >= PROBE_LIMIT || expired(summary)) break;
            if (!visited.add(url)) continue;
            summary.candidates++;
            progress(listener, STAGE_PROBE, summary.candidates, PROBE_LIMIT);
            probe(repo, url, summary, listener);
        }
    }

    private static void collectFiles(Repo repo, Summary summary, List<String> out) {
        String body = get(API + "/repos/" + repo.full + "/contents/", summary);
        if (body == null) return;
        List<String> names = new ArrayList<>();
        try {
            JsonElement element = Json.parse(body);
            if (!element.isJsonArray()) return;
            for (JsonElement opt : element.getAsJsonArray()) {
                if (!opt.isJsonObject()) continue;
                JsonObject item = opt.getAsJsonObject();
                if (!"file".equals(Json.safeString(item, "type"))) continue;
                String path = Json.safeString(item, "path");
                String lower = path.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".json") || FILE_SKIP.contains(lower) || names.contains(path)) continue;
                names.add(path);
            }
        } catch (Throwable e) {
            Log.e(TAG, "parse contents failed", e);
            return;
        }
        names.sort((a, b) -> {
            int x = prefer(a);
            int y = prefer(b);
            return x != y ? Integer.compare(x, y) : a.compareTo(b);
        });
        for (String name : names) {
            if (out.size() >= FILE_LIMIT) break;
            out.add(RAW + "/" + repo.full + "/" + repo.branch + "/" + name);
        }
    }

    private static int prefer(String name) {
        int index = FILE_PREFER.indexOf(name.toLowerCase(Locale.ROOT));
        return index < 0 ? FILE_PREFER.size() : index;
    }

    private static void collectReadme(Repo repo, List<String> out) {
        for (String name : Arrays.asList("README.md", "readme.md", "Readme.md")) {
            String body = fetch(RAW + "/" + repo.full + "/" + repo.branch + "/" + name);
            if (body == null) continue;
            Matcher matcher = LINK.matcher(body);
            int count = 0;
            while (matcher.find() && count < README_LIMIT) {
                String url = clean(matcher.group());
                if (url.toLowerCase(Locale.ROOT).contains(".json")) out.add(url);
                count++;
            }
            return;
        }
    }

    private static String clean(String url) {
        String value = url;
        while (!value.isEmpty() && (value.endsWith(".") || value.endsWith(",") || value.endsWith(";"))) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static void probe(Repo repo, String origin, Summary summary, @Nullable Listener listener) {
        String url = origin;
        String name = name(repo, url);
        String json = fetch(url);
        if (json == null) {
            String backup = backup(url);
            if (backup != null) {
                json = fetch(backup);
                if (json != null) url = backup;
            }
        }
        if (json == null) {
            summary.fail++;
            summary.results.add(Result.fail(repo.full, name, url, "无法连接"));
            return;
        }
        Verdict verdict = verify(json);
        int mask = verdict.mask;
        if (mask == 0) {
            summary.fail++;
            summary.results.add(Result.fail(repo.full, name, url, "非法配置"));
            return;
        }
        summary.pass++;
        summary.results.add(Result.ok(repo.full, name, url, mask, verdict.type));
        Log.i(TAG, "add config sourceType=" + verdict.type + " url=" + url);
        // 全自动进源：url+type 去重后直接写 Config 表，既有 parseDepot / parseConfig 会自动识别。
        if ((mask & MASK_VOD) == MASK_VOD) add(url, name, VOD, summary, listener);
        if ((mask & MASK_LIVE) == MASK_LIVE) add(url, name, LIVE, summary, listener);
    }

    /** raw 失败时改走 jsDelivr CDN。 */
    private static String backup(String url) {
        if (!url.startsWith(RAW + "/")) return null;
        String path = url.substring((RAW + "/").length());
        int index = path.indexOf('/');
        if (index <= 0) return null;
        String repo = path.substring(0, index);
        String rest = path.substring(index + 1);
        int branch = rest.indexOf('/');
        if (branch <= 0) return null;
        return CDN + "/" + repo + "@" + rest.substring(0, branch) + "/" + rest.substring(branch + 1);
    }

    private static String name(Repo repo, String url) {
        int index = url.lastIndexOf('/');
        String file = index < 0 ? url : url.substring(index + 1);
        if (file.endsWith(".json")) file = file.substring(0, file.length() - 5);
        return repo.name + " · " + file;
    }

    /**
     * 连通性判定：能解析为合法多仓/配置才算通过；同时顺带判定源类型。
     *
     * @return Verdict，{@code mask==0} 表示不通过。
     */
    private static Verdict verify(String json) {
        if (TextUtils.isEmpty(json)) return Verdict.of(0, TYPE_UNKNOWN);
        try {
            JsonElement element = Json.parse(json);
            if (element.isJsonArray()) return Verdict.of(depot(json) ? MASK_VOD : 0, TYPE_UNKNOWN);
            if (!element.isJsonObject()) return Verdict.of(0, TYPE_UNKNOWN);
            JsonObject object = element.getAsJsonObject();
            if (object.has("msg")) return Verdict.of(0, TYPE_UNKNOWN);
            int mask = 0;
            if (object.has("urls") && depot(object.get("urls").toString())) mask |= MASK_VOD;
            if (object.has("spider") || object.has("sites")) mask |= MASK_VOD;
            if (object.has("lives")) mask |= MASK_LIVE;
            return Verdict.of(mask, detect(object));
        } catch (Throwable e) {
            Log.e(TAG, "invalid config", e);
            return Verdict.of(0, TYPE_UNKNOWN);
        }
    }

    /**
     * 源类型判定。判「爬虫依赖（jar）」的三个信号，全部来自 config 本体，不用额外发请求：
     * <ol>
     *   <li>顶层 {@code spider} 非空 —— 要额外拉一份 JS 引擎代码；</li>
     *   <li>{@code sites[]} / {@code lives[]} 里任意一项 {@code jar} 非空 —— 正是本构建跑不起来的那种
     *       第三方 spider（内部硬编码腾讯 X5，{@code Util.hasX5WebView:false}）；</li>
     *   <li>{@code sites[]} / {@code lives[]} 里任意一项 {@code api} 以 {@code csp_} 开头 —— 需要自定义
     *       类实现，走的就是 spider 那条路。</li>
     * </ol>
     * 三者都不中、且本体确实带了 sites/lives 才是纯 HTTP 接口。
     * 多仓（{@code urls[]}）本体没有站点信息，判定要看子配置，这里老实返回 {@link #TYPE_UNKNOWN}，
     * 不猜——猜错比不猜更坑人。
     */
    private static int detect(JsonObject object) {
        if (object.has("urls")) return TYPE_UNKNOWN;
        if (!Json.safeString(object, "spider").isEmpty()) return TYPE_JAR;
        boolean hasSite = object.has("sites");
        boolean hasLive = object.has("lives");
        if (spiderDep(object, "sites") || spiderDep(object, "lives")) return TYPE_JAR;
        if (hasSite || hasLive) return TYPE_API;
        return TYPE_UNKNOWN;
    }

    private static boolean spiderDep(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray()) return false;
        for (JsonElement opt : element.getAsJsonArray()) {
            if (!opt.isJsonObject()) continue;
            JsonObject item = opt.getAsJsonObject();
            if (!Json.safeString(item, "jar").isEmpty()) return true;
            if (Json.safeString(item, "api").startsWith("csp_")) return true;
        }
        return false;
    }

    /** {@code verify} 的返回值：连通性 mask + 源类型。 */
    private static final class Verdict {

        private final int mask;
        private final int type;

        private Verdict(int mask, int type) {
            this.mask = mask;
            this.type = type;
        }

        private static Verdict of(int mask, int type) {
            return new Verdict(mask, type);
        }
    }

    private static boolean depot(String json) {
        try {
            for (Depot item : Depot.arrayFrom(json)) if (!item.getUrl().isEmpty()) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ---------------------------------------------------------------- 阶段三：自动进源

    /**
     * 写 Config 表。Config 表 (url,type) 唯一，且 {@code BaseDao.insert} 是 IGNORE（返回 -1 而非抛异常），
     * 但 {@code Config.insert()} 会把 -1 写成脏 id，所以这里必须先 find 判空。
     */
    private static void add(String url, String name, int type, Summary summary, @Nullable Listener listener) {
        try {
            if (AppDatabase.get().getConfigDao().find(url, type) != null) {
                summary.duplicate++;
                return;
            }
            Config.create(type, url, name).insert();
            summary.added++;
            Log.i(TAG, "add config type=" + type + " url=" + url);
            // 累计进源条数（total 用已测通条数，跑完后两者相等）。
            progress(listener, STAGE_ADD, summary.added, summary.pass);
        } catch (Throwable e) {
            Log.e(TAG, "insert config failed", e);
        }
    }

    // ---------------------------------------------------------------- 网络

    /** GitHub API 调用；命中 403/429 限流时置 summary.limited 并优雅停止。 */
    private static String get(String url, Summary summary) {
        try (Response res = CLIENT.newCall(new Request.Builder().url(url).build()).execute()) {
            int code = res.code();
            if (code == 403 || code == 429) {
                summary.limited = true;
                Log.w(TAG, "github rate limited: " + code);
                return null;
            }
            if (code != 200) return null;
            ResponseBody body = res.body();
            return body == null ? null : body.string();
        } catch (Throwable e) {
            Log.e(TAG, "get failed: " + url, e);
            if (summary.error == null) summary.error = describe(e);
            return null;
        }
    }

    /** 拉取 raw/jsDelivr 内容，失败返回 null。 */
    private static String fetch(String url) {
        try (Response res = CLIENT.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!res.isSuccessful()) return null;
            ResponseBody body = res.body();
            return body == null ? null : body.string();
        } catch (Throwable e) {
            Log.e(TAG, "fetch failed: " + url, e);
            return null;
        }
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return TextUtils.isEmpty(message) ? e.getClass().getSimpleName() : message;
    }

    /** 进度回调：统一切主线程，绝不阻塞搜索工作线程。 */
    private static void progress(@Nullable Listener listener, String stage, int done, int total) {
        if (listener == null) return;
        Log.i(TAG, "progress " + stage + " " + done + "/" + total);
        App.post(() -> listener.onProgress(stage, done, total));
    }

    private static final class Repo {

        private final String owner;
        private final String name;
        private final String full;
        private final String branch;
        private final int stars;

        private Repo(String owner, String name, String branch, int stars) {
            this.owner = owner;
            this.name = name;
            this.full = owner + "/" + name;
            this.branch = branch;
            this.stars = stars;
        }

        private static Repo parse(JsonObject item) {
            String full = Json.safeString(item, "full_name");
            int index = full.indexOf('/');
            String owner = index < 0 ? full : full.substring(0, index);
            String name = index < 0 ? full : full.substring(index + 1);
            String branch = Json.safeString(item, "default_branch");
            if (TextUtils.isEmpty(branch)) branch = "main";
            int stars = 0;
            try {
                JsonElement element = item.get("stargazers_count");
                if (element != null && element.isJsonPrimitive()) stars = element.getAsInt();
            } catch (Throwable ignored) {
            }
            return new Repo(owner, name, branch, stars);
        }
    }
}