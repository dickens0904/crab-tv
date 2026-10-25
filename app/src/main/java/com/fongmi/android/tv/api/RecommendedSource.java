package com.fongmi.android.tv.api;

import android.util.Log;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.db.AppDatabase;

/**
 * 推荐源：一键添加「实测可用」的源，免手输地址。
 *
 * <p>为什么要做这个：多仓搜索出来的源十有八九带 spider jar，用户只能挨个试。
 * 这里内置几条在局域网实测跑通的源，点一下就走
 * {@link Config#find(String, String, int)} → {@code VodConfig/LiveConfig.load}
 * 这条既有进源路径，和手输地址在配置对话框里点确定完全等价。
 *
 * <p>地址写死在代码里（按需求不做远程配置）：局域网地址变了用户自己改这几行。
 *
 * <ul>
 *   <li>index 0（点播 CMS）：纯 HTTP，不带 spider / jar，{@link GitHubDepotSearch#TYPE_API}</li>
 *   <li>index 1（直播）：纯 m3u</li>
 *   <li>index 2（点播 · 网盘72源）：24 个 type1 CMS + 48 个 type3 网盘源，
 *       顶层带 {@code spider} 字段，会走 {@code JarLoader} 下载加载 jar</li>
 * </ul>
 */
public class RecommendedSource {

    private static final String TAG = "RecommendedSource";

    /** 点播：bdzy / hhzy 两个 CMS 站，实测手机直连 530/588ms，纯 HTTP 无 jar。 */
    private static final String VOD_URL = "http://192.168.31.29:8099/mobile_vod.json";
    private static final String VOD_NAME = "推荐点播 · bdzy/hhzy";

    /** 直播：142 频道存活筛选后的合并表，纯 m3u，无 jar。 */
    private static final String LIVE_URL = "http://192.168.31.29:8099/merged.m3u";
    private static final String LIVE_NAME = "推荐直播 · 142 频道";

    /** 网盘：24 个 CMS（原 8099 点播，一字未动）+ 48 个 type3 网盘源，共 72 源，带 Guard spider jar。 */
    private static final String PAN_URL = "http://192.168.31.29:8099/mobile_vod_pan.json";
    private static final String PAN_NAME = "推荐点播 · 网盘72源";

    private RecommendedSource() {
    }

    /** 推荐源条数。 */
    public static int size() {
        return 3;
    }

    /**
     * 第 index 条的显示名。
     *
     * @param index 0..{@link #size()}-1
     */
    public static int nameRes(int index) {
        if (index == 1) return R.string.depot_rec_live;
        if (index == 2) return R.string.depot_rec_vod_pan;
        return R.string.depot_rec_vod;
    }

    /**
     * 第 index 条要写进 Config 表的 {@code type}（{@link GitHubDepotSearch#VOD} / {@link GitHubDepotSearch#LIVE}）。
     */
    public static int type(int index) {
        return index == 1 ? GitHubDepotSearch.LIVE : GitHubDepotSearch.VOD;
    }

    /** 第 index 条的源名（存 Config 表用，ASCII，不随语言变）。 */
    @NonNull
    public static String sourceName(int index) {
        if (index == 1) return LIVE_NAME;
        if (index == 2) return PAN_NAME;
        return VOD_NAME;
    }

    /** 第 index 条的地址。 */
    @NonNull
    public static String url(int index) {
        if (index == 1) return LIVE_URL;
        if (index == 2) return PAN_URL;
        return VOD_URL;
    }

    /**
     * 取（或建）第 index 条对应的 Config，**已经写进 Config 表**。
     * 和配置对话框「确定」按钮走的是同一条路，调用方只要再喂给
     * {@code VodConfig.load} / {@code LiveConfig.load} 即可。
     */
    @NonNull
    public static Config find(int index) {
        int type = type(index);
        String url = url(index);
        String name = sourceName(index);
        boolean exists = AppDatabase.get().getConfigDao().find(url, type) != null;
        Config config = Config.find(url, name, type);
        // 和 GitHubDepotSearch.add() 的 "add config" 日志同格式，方便抓 logcat 验收。
        Log.i(TAG, (exists ? "add config (exists) " : "add config ") + "type=" + type + " url=" + url);
        return config;
    }
}