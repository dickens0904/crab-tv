package com.fongmi.android.tv.utils;

/**
 * 蟹老板TV 应用内更新源（2026-11-12 切换为自有仓库）。
 *
 * <p>旧实现指向上游 {@code FongMi/Release/fongmi/apk/}，该路径现已 404，且不属于我们，
 * 无法发布自己的修复包。现在：
 * <ul>
 *   <li>版本描述 JSON：{@code crab-tv-live} 仓库 {@code apk/<flavor>.json}（走 gh-proxy）</li>
 *   <li>APK：{@code crab-tv} 的 GitHub Release {@code latest/download/<资产名>}（走 gh-proxy）</li>
 * </ul>
 *
 * <p>用 {@code releases/latest/download/} 固定路径：每次发版只需发布新 Release +
 * 更新 JSON 里的 name/desc/code，**无需再改代码或重打包本类**，手机在任何网络打开
 * App 即自动弹更新，与局域网 / adb 无关。
 *
 * <p>发版流程：{@code scripts/release.py} 升版本 → 构建 → 打 tag → GitHub Release（4 个 APK）；
 * 再用 {@code live_source_db/publish_github.py} 把 {@code apk/mobile.json}、
 * {@code apk/leanback.json} 推到 crab-tv-live。
 */
public class Github {

    /** 版本描述 JSON 所在目录（crab-tv-live 仓库 main 分支）。 */
    public static final String URL = "https://gh-proxy.com/https://raw.githubusercontent.com/dickens0904/crab-tv-live/main/apk";

    /** APK 资产根目录；latest/download 自动指向最新 Release，支持断点续传。 */
    public static final String APK_URL = "https://gh-proxy.com/https://github.com/dickens0904/crab-tv/releases/latest/download";

    /** flavor 对应的版本 JSON，例如 mobile.json / leanback.json。 */
    public static String getJson(String name) {
        return URL + "/" + name + ".json";
    }

    /**
     * APK 资产直链。name 形如 {@code mobile-arm64_v8a} / {@code leanback-armeabi_v7a}，
     * 与 release.py 上传到 GitHub Release 的文件名一致。
     */
    public static String getApk(String name) {
        return APK_URL + "/" + name + ".apk";
    }
}
