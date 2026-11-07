package com.fongmi.android.tv.utils;

import android.content.ContentResolver;
import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.server.Server;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.UriUtil;
import com.google.common.net.HttpHeaders;

import java.io.File;

public class UrlUtil {

    public static Uri uri(String url) {
        url = url.trim().replace("\\", "");
        return url.startsWith("/") ? Uri.fromFile(new File(url)) : Uri.parse(url);
    }

    public static String scheme(String url) {
        return url == null ? "" : scheme(uri(url));
    }

    public static String scheme(Uri uri) {
        String scheme = uri.getScheme();
        return scheme == null ? "" : scheme.toLowerCase().trim();
    }

    public static String host(String url) {
        return url == null ? "" : host(uri(url));
    }

    public static String host(Uri uri) {
        String host = uri.getHost();
        return host == null ? "" : host.toLowerCase().trim();
    }

    public static String path(String url) {
        return url == null ? "" : path(uri(url));
    }

    public static String path(Uri uri) {
        String path = uri.getLastPathSegment();
        return path == null ? "" : path.trim();
    }

    public static String toLocalUrl(Uri uri) {
        String path = uri.getPath();
        String root = Path.rootPath();
        if (!ContentResolver.SCHEME_FILE.equalsIgnoreCase(uri.getScheme()) || TextUtils.isEmpty(path) || !path.startsWith(root + File.separator)) return uri.toString();
        return "file://" + path.substring(root.length() + 1);
    }

    public static String resolve(String baseUri, String referenceUri) {
        return UriUtil.resolve(baseUri, referenceUri);
    }

    public static String convert(String url) {
        String scheme = scheme(url);
        String prefix = scheme + "://";
        return switch (scheme) {
            case "assets" -> url.replace(prefix, Server.get().getAddress("/"));
            case "proxy" -> url.replace(prefix, Server.get().getAddress("/proxy?"));
            case "file" -> Server.get().getAddress("/file/") + Uri.encode(url.substring((prefix).length()), "/");
            default -> url;
        };
    }

    /**
     * 把 spider 的相对 api 按配置文件地址解析成绝对地址。
     *
     * <p>背景：多仓配置里 js / py 源极常把 api 写成 {@code ./lib/drpy2.min.js} 这种
     * “相对于本配置文件”的路径。这类值原本原样传给 {@code JsLoader} / {@code PyLoader}，
     * 而 {@code Module.fetch()} 只认 {@code http*}、{@code assets*}、{@code lib/} 三种前缀，
     * 于是 fetch 返回 null、evaluateModule 抛异常、被 catch 成 {@code SpiderNull} ——
     * 整批 js 源一个都加载不出来，而且日志里什么都不留。
     *
     * <p>只处理 {@code ./}、{@code ../}、{@code /} 开头的相对路径；
     * {@code csp_*}、{@code http*}、{@code assets://}、{@code proxy://}、{@code file://}
     * 以及 {@code lib/x.js}（走 assets 内置库的老约定）一律原样返回，不改变既有行为。
     */
    public static String resolveApi(String api, String base) {
        if (TextUtils.isEmpty(api) || TextUtils.isEmpty(base)) return api;
        if (!(api.startsWith("./") || api.startsWith("../") || api.startsWith("/"))) return api;
        try {
            String url = UriUtil.resolve(base, api);
            return TextUtils.isEmpty(url) ? api : url;
        } catch (Throwable e) {
            return api;
        }
    }

    public static String getName(String url) {
        Uri uri = uri(url);
        String path = path(uri);
        String host = host(uri);
        return !path.isEmpty() ? path : !host.isEmpty() ? host : url;
    }

    public static String fixHeader(String key) {
        if (HttpHeaders.USER_AGENT.equalsIgnoreCase(key)) return HttpHeaders.USER_AGENT;
        if (HttpHeaders.REFERER.equalsIgnoreCase(key)) return HttpHeaders.REFERER;
        if (HttpHeaders.COOKIE.equalsIgnoreCase(key)) return HttpHeaders.COOKIE;
        return key;
    }
}
