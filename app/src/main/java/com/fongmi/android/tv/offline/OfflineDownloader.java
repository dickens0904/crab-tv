package com.fongmi.android.tv.offline;

import com.fongmi.android.tv.player.exo.HlsAdFilter;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 把一条点播播放地址真正下载到本地。
 *
 * <p>两种形态都覆盖：
 * <ul>
 *   <li><b>HLS(m3u8)</b>：先把 playlist 落到临时文件再判断（不能靠 URL 后缀，很多源是
 *       {@code /play/abc?token=...} 这种无后缀地址）。master playlist 取带宽最高的一条
 *       variant，再把 media playlist 里所有分片 / AES key / init 段逐个下载，
 *       并把 URI 改写成<b>相对文件名</b> —— 用相对名是因为 9978 本地服务端口每次启动
 *       可能不同，写死绝对 URL 会导致重启后播不了。</li>
 *   <li><b>单文件(mp4/mkv/flv...)</b>：直接流式落盘。</li>
 * </ul>
 *
 * <p>下载走 App 既有 {@link OkHttp} 栈（含统一 UA / 代理拦截器），支持取消。
 */
public class OfflineDownloader {

    private static final int BUFFER = 16 * 1024;
    /** 分片并发数。单连接在多数 CDN 上只有几十~几百 KB/s，16 路能把宽带用起来。 */
    private static final int SEG_WORKERS = 16;
    /** 单片最多尝试次数（含签名刷新后的重试）。 */
    private static final int SEG_ATTEMPTS = 5;
    /** 签名刷新结果的有效期：过期时间戳通常在 ~100 分钟量级，60s 内复用同一份新 playlist。 */
    private static final long REFRESH_TTL_MS = 60_000L;
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern BYTERANGE_ATTR = Pattern.compile(",?BYTERANGE=\"[^\"]*\"");
    private static final Pattern BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)");

    /** 并发下载时需要能一次取消全部在途请求。 */
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private volatile boolean canceled;
    /** 原始播放地址：签名过期后要重新走 master → variant 拿新签。 */
    private volatile String sourceUrl;
    private final Object refreshLock = new Object();
    private volatile Map<String, String> refreshed;
    private volatile long refreshedAt;

    public interface Progress {
        void onProgress(int percent, long bytes);
    }

    public interface Canceled {
        boolean isCanceled();
    }

    private record Downloaded(File file, HttpUrl base, long size) {
    }

    private record Fetch(String url, String name) {
    }

    public void cancel() {
        canceled = true;
        for (Call current : calls) {
            try {
                current.cancel();
            } catch (Exception ignored) {
            }
        }
        calls.clear();
    }

    /**
     * @return 可交给本地 /file/ 服务播放的文件（index.m3u8 或 video.xxx）
     */
    public File run(String url, File dir, Progress progress, Canceled canceledCheck) throws Exception {
        this.sourceUrl = url;
        // 本地下载源要确保 9978 本地 /file/ 服务已启动（App 启动早期可能尚未就绪）
        if (isLocal(hostOf(url))) com.fongmi.android.tv.server.Server.get().start();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create offline dir");
        // 上次进程被杀会留下半截 .part，断点续传只认已 rename 完成的文件
        cleanPartial(dir);
        File source = new File(dir, "source.tmp");
        Downloaded first = downloadTo(url, source, progress, canceledCheck, false);
        if (!isPlaylist(source)) {
            File target = new File(dir, "video" + extensionOf(url));
            if (target.exists() && !target.delete()) throw new IOException("Cannot replace video file");
            if (!source.renameTo(target)) throw new IOException("Cannot rename video file");
            progress.onProgress(100, first.size());
            return target;
        }
        String playlist = Path.read(source);
        HttpUrl base = first.base();
        // 首次探测下载报的是 playlist 自身的字节进度，这里归零，后续按分片数报整体进度
        progress.onProgress(0, 0);
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            String variant = pickVariant(playlist);
            if (variant == null) throw new IOException("Empty master playlist");
            File variantFile = new File(dir, "variant.tmp");
            Downloaded second = null;
            Exception last = null;
            for (int attempt = 0; attempt < 2 && second == null; attempt++) {
                try {
                    second = downloadTo(resolve(base, variant), variantFile, null, canceledCheck, false);
                } catch (Exception e) {
                    // 变体地址带 session，过期后必须重新取 master 才能拿到新的
                    last = e;
                    Downloaded again = downloadTo(url, source, null, canceledCheck, false);
                    base = again.base();
                    String next = pickVariant(Path.read(source));
                    if (next == null) throw e;
                    variant = next;
                }
            }
            if (second == null) throw last == null ? new IOException("Cannot fetch variant") : last;
            playlist = Path.read(variantFile);
            base = second.base();
            variantFile.delete();
        }
        // 2026-11-19: 离线缓存同样剔除广告段，缓存文件更小、回看也没有插播
        playlist = HlsAdFilter.filter(playlist, base == null ? null : base.toString());
        File index = hls(playlist, base, dir, progress, canceledCheck);
        source.delete();
        return index;
    }

    private File hls(String playlist, HttpUrl base, File dir, Progress progress, Canceled canceledCheck) throws Exception {
        List<String> out = new ArrayList<>();
        List<Fetch> fetches = new ArrayList<>();
        int segment = 0;
        int key = 0;
        for (String raw : playlist.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                out.add(raw);
            } else if (line.startsWith("#EXT-X-MEDIA") && line.contains("URI=")) {
                // 外挂音轨/字幕：离线时不应再去公网拉，整行丢弃
            } else if (line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-MAP")) {
                Matcher matcher = URI_ATTR.matcher(line);
                if (!matcher.find()) {
                    out.add(raw);
                    continue;
                }
                String remote = matcher.group(1);
                String local = line.startsWith("#EXT-X-KEY")
                        ? "key" + (key++) + ".key"
                        : "init" + (segment++) + extensionOf(remote);
                fetches.add(new Fetch(resolve(base, remote), local));
                String replaced = URI_ATTR.matcher(raw).replaceFirst("URI=\"" + local + "\"");
                if (line.startsWith("#EXT-X-MAP")) replaced = BYTERANGE_ATTR.matcher(replaced).replaceAll("");
                out.add(replaced);
            } else if (line.startsWith("#")) {
                out.add(raw);
            } else {
                String local = "seg" + String.format(Locale.ROOT, "%04d", segment++) + extensionOf(line);
                fetches.add(new Fetch(resolve(base, line), local));
                out.add(local);
            }
        }
        if (fetches.isEmpty()) throw new IOException("No segments in playlist");
        // 并发下载：单连接跑满也就几十~几百 KB/s，5 路能把宽带用起来。
        // 每片下完先落 .part 再 rename，所以进程被杀后重跑会自动跳过已完成的分片。
        int workers = Math.min(SEG_WORKERS, fetches.size());
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        AtomicInteger finished = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        final Object progressLock = new Object();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < fetches.size(); i++) {
            final Fetch fetch = fetches.get(i);
            final int index = i;
            futures.add(pool.submit((Callable<Void>) () -> {
                Downloaded done = fetchSegment(fetch.url(), new File(dir, fetch.name()), canceledCheck);
                bytes.addAndGet(done.size());
                int percent = Math.min(99, finished.incrementAndGet() * 100 / fetches.size());
                synchronized (progressLock) {
                    progress.onProgress(percent, bytes.get());
                }
                return null;
            }));
        }
        try {
            for (Future<?> future : futures) future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception error) throw error;
            throw new IOException(cause == null ? "segment failed" : cause.getMessage());
        } finally {
            pool.shutdownNow();
        }
        File index = new File(dir, "index.m3u8");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(index), StandardCharsets.UTF_8)) {
            for (String line : out) {
                writer.write(line);
                writer.write('\n');
            }
        }
        progress.onProgress(100, bytes.get());
        return index;
    }

    /**
     * 取一个分片：已完成的直接跳过（断点续传），失败重试；
     * 403/401/410 视为签名过期，重新解析 playlist 换新签名再试。
     */
    private Downloaded fetchSegment(String url, File target, Canceled canceledCheck) throws IOException {
        if (target.isFile() && target.length() > 0) return new Downloaded(target, HttpUrl.parse(url), target.length());
        IOException last = null;
        String current = url;
        for (int attempt = 0; attempt < SEG_ATTEMPTS; attempt++) {
            check(canceledCheck);
            try {
                return downloadTo(current, target, null, canceledCheck, true);
            } catch (HttpCode error) {
                last = error;
                int code = error.code;
                if (code == 401 || code == 403 || code == 410) {
                    String fresh = freshUrlFor(url);
                    if (fresh != null && !fresh.equals(current)) {
                        current = fresh;
                        continue;
                    }
                }
                // 429 是 CDN 限流，退避要长一点，否则越重试越被限
                sleep(code == 429 ? 2000L * (attempt + 1) : 300L * (attempt + 1));
            } catch (IOException error) {
                last = error;
                if (canceled) throw error;
                sleep(300L * (attempt + 1));
            }
        }
        throw last == null ? new IOException("segment failed: " + url) : last;
    }

    /** 重新走 master → variant 取一份最新 playlist，按分片文件名建映射（只有签名变，文件名不变）。 */
    private String freshUrlFor(String url) {
        try {
            Map<String, String> map = refreshed;
            if (map == null || System.currentTimeMillis() - refreshedAt > REFRESH_TTL_MS) {
                synchronized (refreshLock) {
                    if (refreshed == null || System.currentTimeMillis() - refreshedAt > REFRESH_TTL_MS) {
                        refreshed = reloadSegments();
                        refreshedAt = System.currentTimeMillis();
                    }
                    map = refreshed;
                }
            }
            return map == null ? null : map.get(nameOf(url));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> reloadSegments() throws IOException {
        String source = sourceUrl;
        if (source == null) return null;
        HttpUrl base;
        String text;
        try (Response response = execute(source)) {
            if (!response.isSuccessful()) return null;
            text = readAll(response);
            base = response.request().url();
        }
        if (text.contains("#EXT-X-STREAM-INF")) {
            String variant = pickVariant(text);
            if (variant == null) return null;
            String variantUrl = resolve(base, variant);
            try (Response response = execute(variantUrl)) {
                if (!response.isSuccessful()) return null;
                text = readAll(response);
                base = response.request().url();
            }
        }
        Map<String, String> map = new HashMap<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String absolute = resolve(base, line);
            map.put(nameOf(absolute), absolute);
        }
        return map;
    }

    /** 分片身份：URL 最后一段文件名（查询串是签名，刷新后会变）。 */
    private static String nameOf(String url) {
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private void cleanPartial(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".part")) file.delete();
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Downloaded downloadTo(String url, File target, Progress progress, Canceled canceledCheck, boolean resumable) throws IOException {
        check(canceledCheck);
        // 断点续传：只有 rename 成功过的文件才算完整，半截 .part 一律重下
        if (resumable && target.isFile() && target.length() > 0) {
            return new Downloaded(target, HttpUrl.parse(url), target.length());
        }
        File part = new File(target.getPath() + ".part");
        Call request = clientFor(url).newCall(new Request.Builder().url(url).tag("offline").build());
        calls.add(request);
        try (Response response = request.execute()) {
            check(canceledCheck);
            if (!response.isSuccessful()) throw new HttpCode(response.code(), "HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response body");
            long length = body.contentLength();
            long written = 0;
            try (InputStream input = new BufferedInputStream(body.byteStream()); OutputStream output = new FileOutputStream(part)) {
                byte[] buffer = new byte[BUFFER];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    check(canceledCheck);
                    output.write(buffer, 0, read);
                    written += read;
                    if (length > 0 && progress != null) {
                        int percent = (int) Math.min(99, written * 100 / length);
                        progress.onProgress(percent, written);
                    }
                }
            }
            if (written == 0) throw new IOException("Empty download: " + url);
            // 2026-11-19: 有些"源"返回的只是落地页/JSON/一行链接，旧逻辑会把 HTML
            // 直接改名成 video.ts 并标记下载完成 —— 用户看到的就是"缓存了链接不是视频"。
            validateContent(part, response.header("Content-Type"), url);
            if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target.getName());
            if (!part.renameTo(target)) throw new IOException("Cannot rename " + part.getName());
            return new Downloaded(target, response.request().url(), written);
        } catch (IOException error) {
            part.delete();
            throw error;
        } finally {
            calls.remove(request);
        }
    }

    /** 重新解析 playlist 用：短请求，不进 calls（取消时不中断也不影响结果）。 */
    private Response execute(String url) throws IOException {
        Call request = clientFor(url).newCall(new Request.Builder().url(url).tag("offline").build());
        return request.execute();
    }

    private static String readAll(Response response) throws IOException {
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    /** 带状态码的 IOException：用来识别 403/410 这类"签名过期"而不是网络故障。 */
    private static class HttpCode extends IOException {
        final int code;

        HttpCode(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** 拒绝把网页/JSON/纯链接当视频落盘；加密分片是二进制，不受影响。 */
    public static void validateContent(File file, String contentType, String url) throws IOException {
        byte[] head = new byte[512];
        int n;
        try (InputStream in = new FileInputStream(file)) {
            n = in.read(head);
        }
        if (n <= 0) throw new IOException("Empty download: " + url);
        String text = new String(head, 0, n, StandardCharsets.UTF_8).trim();
        if (!text.isEmpty() && text.charAt(0) == '\ufeff') text = text.substring(1);
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("#extm3u")) return;                 // 清单本身就是文本
        String ct = (contentType == null ? "" : contentType.toLowerCase(Locale.ROOT));
        if (ct.contains("text/html") || ct.contains("application/json") || ct.contains("text/xml")) {
            throw new IOException("不是视频文件（返回网页）: " + url);
        }
        if (lower.startsWith("<!doctype") || lower.startsWith("<html") || lower.startsWith("<?xml")
                || lower.startsWith("{") || lower.startsWith("[")) {
            throw new IOException("不是视频文件（返回页面/JSON）: " + url);
        }
        // 只有一行 URL 的"链接文件"也不算视频
        if (!lower.contains("\n") && (lower.startsWith("http://") || lower.startsWith("https://"))
                && file.length() < 2048) {
            throw new IOException("不是视频文件（只有链接）: " + url);
        }
    }

    private void check(Canceled canceledCheck) throws InterruptedIOException {
        // 只认我们自己的取消标志，不读线程池复用线程遗留的中断状态
        if (canceled) throw new InterruptedIOException();
        if (canceledCheck != null && canceledCheck.isCanceled()) throw new InterruptedIOException();
    }

    /**
     * 缓存专用 client。
     *
     * <p>两点与通用 client 不同：
     * <ul>
     *   <li>不挂 16MB 配置缓存（{@code client()}）——分片是几 MB 的二进制，写进 HTTP 缓存等于双倍写盘；</li>
     *   <li>独立 Dispatcher。OkHttp 默认 {@code maxRequestsPerHost = 5}，16 路并发会被压回 5 路，
     *       缓存速度上不去。</li>
     * </ul>
     */
    private static final OkHttpClient OFFLINE_CLIENT = buildClient();

    private static OkHttpClient buildClient() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(SEG_WORKERS * 2);
        dispatcher.setMaxRequestsPerHost(SEG_WORKERS * 2);
        return OkHttp.player().newBuilder().dispatcher(dispatcher).build();
    }

    /**
     * 下载用客户端。本地地址必须直连：全局代理会把 127.0.0.1 的请求转发到代理，
     * 代理不认识本地服务会回 502。其余地址沿用 App 统一 OkHttp（含 UA / 订阅代理）。
     */
    private static OkHttpClient clientFor(String url) {
        OkHttpClient base = OFFLINE_CLIENT;
        String host = hostOf(url);
        if (host == null || !isLocal(host)) return base;
        return base.newBuilder().proxySelector(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return uri == null ? Collections.emptyList() : Collections.singletonList(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        }).build();
    }

    private static boolean isLocal(String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host) || "0.0.0.0".equals(host);
    }

    private static String hostOf(String url) {
        try {
            HttpUrl parsed = HttpUrl.parse(url);
            return parsed == null ? null : parsed.host();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isPlaylist(File file) {
        try (InputStream input = new FileInputStream(file)) {
            byte[] head = new byte[32];
            int read = input.read(head);
            return read > 0 && new String(head, 0, read, StandardCharsets.UTF_8).trim().startsWith("#EXTM3U");
        } catch (IOException e) {
            return false;
        }
    }

    private static String pickVariant(String playlist) {
        int bestBandwidth = -1;
        String best = null;
        String pending = null;
        for (String raw : playlist.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                Matcher matcher = BANDWIDTH.matcher(line);
                pending = matcher.find() ? matcher.group(1) : "0";
            } else if (pending != null && !line.isEmpty() && !line.startsWith("#")) {
                int bandwidth = parseInt(pending);
                if (bandwidth > bestBandwidth) {
                    bestBandwidth = bandwidth;
                    best = line;
                }
                pending = null;
            }
        }
        if (best != null) return best;
        for (String raw : playlist.split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.isEmpty() && !line.startsWith("#")) return line;
        }
        return null;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String resolve(HttpUrl base, String url) {
        if (base == null) return url;
        try {
            HttpUrl resolved = base.resolve(url);
            return resolved == null ? url : resolved.toString();
        } catch (Exception e) {
            return url;
        }
    }

    private static String extensionOf(String url) {
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int dot = path.lastIndexOf('.');
        if (dot < 0) return ".ts";
        String ext = path.substring(dot);
        if (ext.length() > 6 || !ext.matches("\\.[A-Za-z0-9]+")) return ".ts";
        return ext.toLowerCase(Locale.ROOT);
    }
}
