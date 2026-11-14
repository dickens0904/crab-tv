package com.fongmi.android.tv.offline;

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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
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
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern BYTERANGE_ATTR = Pattern.compile(",?BYTERANGE=\"[^\"]*\"");
    private static final Pattern BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)");

    private volatile Call call;
    private volatile boolean canceled;

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
        Call current = call;
        if (current != null) current.cancel();
    }

    /**
     * @return 可交给本地 /file/ 服务播放的文件（index.m3u8 或 video.xxx）
     */
    public File run(String url, File dir, Progress progress, Canceled canceledCheck) throws Exception {
        // 本地下载源要确保 9978 本地 /file/ 服务已启动（App 启动早期可能尚未就绪）
        if (isLocal(hostOf(url))) com.fongmi.android.tv.server.Server.get().start();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create offline dir");
        File source = new File(dir, "source.tmp");
        Downloaded first = downloadTo(url, source, progress, canceledCheck);
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
            String variantUrl = resolve(base, variant);
            File variantFile = new File(dir, "variant.tmp");
            Downloaded second = downloadTo(variantUrl, variantFile, null, canceledCheck);
            playlist = Path.read(variantFile);
            base = second.base();
            variantFile.delete();
        }
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
        long bytes = 0;
        for (int i = 0; i < fetches.size(); i++) {
            Fetch fetch = fetches.get(i);
            // 分片本身不单独报进度，否则会覆盖按「分片数」计算的整体百分比
            Downloaded done = downloadTo(fetch.url(), new File(dir, fetch.name()), null, canceledCheck);
            bytes += done.size();
            progress.onProgress(Math.min(99, (i + 1) * 100 / fetches.size()), bytes);
        }
        File index = new File(dir, "index.m3u8");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(index), StandardCharsets.UTF_8)) {
            for (String line : out) {
                writer.write(line);
                writer.write('\n');
            }
        }
        progress.onProgress(100, bytes);
        return index;
    }

    private Downloaded downloadTo(String url, File target, Progress progress, Canceled canceledCheck) throws IOException {
        check(canceledCheck);
        Call request = clientFor(url).newCall(new Request.Builder().url(url).tag("offline").build());
        call = request;
        try (Response response = request.execute()) {
            check(canceledCheck);
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response body");
            long length = body.contentLength();
            long written = 0;
            try (InputStream input = new BufferedInputStream(body.byteStream()); OutputStream output = new FileOutputStream(target)) {
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
            return new Downloaded(target, response.request().url(), written);
        } finally {
            call = null;
        }
    }

    private void check(Canceled canceledCheck) throws InterruptedIOException {
        // 只认我们自己的取消标志，不读线程池复用线程遗留的中断状态
        if (canceled) throw new InterruptedIOException();
        if (canceledCheck != null && canceledCheck.isCanceled()) throw new InterruptedIOException();
    }

    /**
     * 下载用客户端。本地地址必须直连：全局代理会把 127.0.0.1 的请求转发到代理，
     * 代理不认识本地服务会回 502。其余地址沿用 App 统一 OkHttp（含 UA / 订阅代理）。
     */
    private static OkHttpClient clientFor(String url) {
        OkHttpClient base = OkHttp.client();
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
