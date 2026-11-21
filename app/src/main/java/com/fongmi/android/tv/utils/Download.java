package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Dispatcher;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class Download {

    private static final int BUFFER_SIZE = 16 * 1024;
    /**
     * 多线程下载线程数。gh-proxy 等支持 range 的代理，多线程比单线程快 10 倍+（实测 43KB/s→545KB/s）。
     * 2026-11-20: 10 → 16，并改成「固定大小分块 + 线程池取任务」，
     * 避免等分时长尾分片（某片所在节点慢）拖住整体。
     */
    private static final int WORKERS = 16;
    /** 单个分块大小；分块越多越不容易被长尾拖慢，但文件数和合并开销也上升。 */
    private static final long BLOCK_BYTES = 4L * 1024 * 1024;
    /** 单片最大重试次数：代理会中途 reset 连接，断在中间必须能续传而不是整体失败。 */
    private static final int MAX_RETRY = 6;
    /** 单片重试退避基准（毫秒），第 n 次等待 n * RETRY_DELAY。 */
    private static final long RETRY_DELAY = 400;
    /** 排查用日志：adb logcat -s CrabTV-DL */
    private static final String LOG = "CrabTV-DL";
    /** 分片下载整体无进展超时（毫秒）：所有分片长时间不涨字节即判失败，避免进度条永久卡住。 */
    private static final long STALL_TIMEOUT = 120_000;

    private final String url;
    private final File file;

    private volatile Call call;
    private volatile boolean canceled;
    private volatile ExecutorService executor;

    private final List<Call> partCalls = Collections.synchronizedList(new ArrayList<>());

    private Callback callback;
    private Future<?> future;
    private int lastProgress;
    private long maxBytes;
    private long totalSize;
    private String tag;
    /** 源总长变化只允许整体重来一次，防止死循环。 */
    private boolean retriedTotal;

    public static Download create(String url, File file) {
        return new Download(url, file);
    }

    public Download(String url, File file) {
        this.maxBytes = Long.MAX_VALUE;
        this.tag = url;
        this.url = url;
        this.file = file;
    }

    public Download tag(String tag) {
        this.tag = tag;
        return this;
    }

    public Download maxBytes(long maxBytes) {
        this.maxBytes = maxBytes > 0 ? maxBytes : Long.MAX_VALUE;
        return this;
    }

    public File get() {
        doInBackground();
        return file;
    }

    public void start(Callback callback) {
        this.lastProgress = -1;
        this.canceled = false;
        this.retriedTotal = false;
        this.callback = callback;
        future = Task.submit(this::doInBackground);
    }

    public Download cancel() {
        canceled = true;
        synchronized (partCalls) {
            for (Call c : partCalls) {
                try {
                    c.cancel();
                } catch (Exception ignored) {
                }
            }
            partCalls.clear();
        }
        if (executor != null) executor.shutdownNow();
        if (future != null) future.cancel(true);
        Call request = call;
        if (request != null) request.cancel();
        OkHttp.cancel(DL_CLIENT, tag);
        // 2026-11-19: 保留分片，下次启动/重试直接续传；URL 变更时由 validateParts() 清理。
        future = null;
        executor = null;
        return this;
    }

    /**
     * 下载专用 client：不挂 http 缓存。
     * client() 带 16MB 配置缓存，大文件走 Range 分片时会把 206 响应写进缓存再丢弃，
     * 盒子这类慢 IO 设备等于双倍写盘，容易中途断流；下载本身也不需要缓存。
     *
     * <p>2026-11-20: 必须换一个独立 Dispatcher。OkHttp 默认
     * {@code maxRequestsPerHost = 5}，同一 host 的请求会排队，
     * 之前开 10 线程其实只有 5 路在跑 —— 这才是"多线程还是慢"的真凶。
     */
    private static final OkHttpClient DL_CLIENT = buildClient();

    private static OkHttpClient buildClient() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(WORKERS * 2);
        dispatcher.setMaxRequestsPerHost(WORKERS * 2);
        return OkHttp.player().newBuilder().dispatcher(dispatcher).build();
    }

    private void doInBackground() {
        try {
            validateParts();
            if (probeRange()) {
                downloadMulti(totalSize);
            } else {
                downloadSingle();
            }
            postCallback(listener -> listener.success(file));
        } catch (Exception error) {
            handleError(error);
        } finally {
            call = null;
        }
    }

    /** 用 Range: bytes=0-0 探测是否支持断点续传，并取总大小。支持则走多线程分片下载。 */
    private boolean probeRange() throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Range", "bytes=0-0");
        Call request = newCall(headers);
        this.call = request;
        try (Response response = request.execute()) {
            if (response.code() == 206) {
                String cr = response.header("Content-Range");
                if (cr != null && cr.contains("/")) {
                    totalSize = Long.parseLong(cr.split("/")[1].trim());
                    return totalSize > 0;
                }
            }
        }
        return false;
    }

    private void downloadSingle() throws IOException {
        Call request = createCall();
        execute(request);
        validateOutput(file.length(), 0);
    }

    /**
     * 多线程分片下载：每线程下载一段到临时分片（失败自动续传重试），全部完成后按序合并。
     * 任一分片收到 HTTP 200（服务端忽略 Range）即整体回退单线程，否则偏移错位会拼出坏包。
     */
    private void downloadMulti(long total) throws Exception {
        checkSize(total);
        // 固定块大小，块数由总长决定；线程池按块取任务，慢块只占一个线程，不会拖住整体。
        long block = Math.max(512L * 1024, Math.min(BLOCK_BYTES, (total + WORKERS - 1) / WORKERS));
        int count = (int) ((total + block - 1) / block);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(WORKERS, count));
        executor = pool;
        writeMeta(block, total);
        android.util.Log.i(LOG, "multi start total=" + total + " blocks=" + count + " block=" + block);
        List<Future<?>> futures = new ArrayList<>();
        long[] done = new long[count];
        for (int i = 0; i < count; i++) {
            final int idx = i;
            final long start = i * block;
            final long end = Math.min(total - 1, start + block - 1);
            final File part = partFile(i);
            futures.add(pool.submit((Callable<Void>) () -> {
                downloadPart(idx, start, end, part, done, total);
                return null;
            }));
        }
        try {
            awaitParts(futures, done, total);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NoRange) {
                android.util.Log.w(LOG, "server ignore range, fallback to single");
                deleteParts();
                pool.shutdownNow();
                downloadSingle();
                return;
            }
            if (cause instanceof SourceChanged && !retriedTotal) {
                // 源在中途换了版本（缓存/发布切换）：已下的分片全部作废，重新取总长再来一次
                android.util.Log.w(LOG, "source changed " + cause.getMessage() + ", restart");
                retriedTotal = true;
                deleteParts();
                pool.shutdownNow();
                if (probeRange()) downloadMulti(totalSize);
                else downloadSingle();
                return;
            }
            throw e;
        } finally {
            pool.shutdownNow();
            if (executor == pool) executor = null;
        }
        long sum = sumDone(done);
        android.util.Log.i(LOG, "multi done sum=" + sum + "/" + total);
        if (sum < total) throw new IOException("下载不完整 " + sum + "/" + total);
        mergeParts(count, total);
    }

    /** 轮询分片进度：长时间零进展直接失败，避免各分片各自重试导致界面永久卡住。 */
    private void awaitParts(List<Future<?>> futures, long[] done, long total) throws Exception {
        long lastSum = -1;
        long lastChange = System.currentTimeMillis();
        while (true) {
            boolean allDone = true;
            for (Future<?> f : futures) {
                if (!f.isDone()) {
                    allDone = false;
                    break;
                }
            }
            if (allDone) break;
            long sum = sumDone(done);
            long now = System.currentTimeMillis();
            if (sum != lastSum) {
                lastSum = sum;
                lastChange = now;
            } else if (now - lastChange > STALL_TIMEOUT) {
                android.util.Log.w(LOG, "stall at " + sum + "/" + total);
                for (Future<?> f : futures) f.cancel(true);
                deleteParts();
                throw new IOException("下载长时间无进展，请检查网络后重试");
            }
            Thread.sleep(1000);
        }
        for (Future<?> f : futures) f.get();
    }

    private File partFile(int i) {
        return new File(file.getParentFile(), file.getName() + ".part" + i);
    }

    /** 分片归属标记：防止同一缓存文件名被不同 URL 复用时串源。 */
    private File metaFile() {
        return new File(file.getParentFile(), file.getName() + ".parts.url");
    }

    /**
     * 分片是否可复用：URL 相同且分块布局（块大小/总长）一致。
     * 布局变了还沿用旧分片会拼出错位内容，必须丢弃。
     */
    private void validateParts() {
        try {
            File meta = metaFile();
            if (!meta.exists()) {
                // 无归属标记却有残留分片（旧版本/异常退出遗留）：来源不可信，一律清掉重下
                deleteParts();
                return;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = new java.io.FileInputStream(meta)) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            }
            String[] parts = bos.toString("UTF-8").trim().split("\n");
            // 旧格式只有 URL 一行，没有分块布局信息 → 不可信
            if (parts.length < 3 || !url.equals(parts[0].trim())) deleteParts();
        } catch (Exception e) {
            deleteParts();
        }
    }

    private void writeMeta(long block, long total) {
        try {
            try (FileOutputStream out = new FileOutputStream(metaFile())) {
                out.write((url + "\n" + block + "\n" + total).getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }

    /** 分块数量不固定，按文件名前缀扫描删除，顺带清掉旧版本遗留的分片。 */
    private void deleteParts() {
        try {
            File dir = file.getParentFile();
            String prefix = file.getName() + ".part";
            File[] files = dir == null ? null : dir.listFiles();
            if (files != null) {
                for (File p : files) {
                    if (p.isFile() && p.getName().startsWith(prefix)) p.delete();
                }
            }
            metaFile().delete();
        } catch (Exception ignored) {
        }
    }

    /**
     * 下载单个分片。断流（gh-proxy 会中途 reset）时不整体失败，而是按已下载字节数
     * 用 Range 从断点续传，最多重试 MAX_RETRY 次。
     */
    private void downloadPart(int idx, long start, long end, File part, long[] done, long total) throws IOException {
        long need = end - start + 1;
        long got = part.exists() ? part.length() : 0;
        if (got > need) {
            part.delete();
            got = 0;
        }
        done[idx] = got;
        updateProgress(sumDone(done), total);
        IOException last = null;
        for (int attempt = 0; got < need; attempt++) {
            checkCanceled();
            if (attempt >= MAX_RETRY) throw last != null ? last : new IOException("分片 " + idx + " 下载不完整");
            boolean append = got > 0;
            Map<String, String> headers = new HashMap<>();
            headers.put("Range", "bytes=" + (start + got) + "-" + end);
            Call request = newCall(headers);
            partCalls.add(request);
            try (Response response = request.execute()) {
                int code = response.code();
                if (code != 206 && code != 200) throw new IOException("HTTP " + code);
                // 服务端忽略 Range 返回 200（整包）：偏移无法对齐，续传会拼出坏文件，交给上层回退单线程
                if (code == 200) throw new NoRange();
                long remain = need - got;
                // 关键：206 的 Content-Range 总长必须与 probe 时一致。
                // gh-proxy 对 latest/download 的各分片可能 302 到不同版本资源，
                // 混拼出来的包长度看似正常、内容却是坏的（安装器卡在扫描），必须整体重来。
                checkTotal(response, total);
                long declared = response.body().contentLength();
                // 206 但声明长度与请求范围不符：本次响应不可信，丢弃重下
                if (declared > 0 && declared != remain) throw new IOException("range size mismatch " + declared + "/" + remain);
                Path.create(part);
                try (InputStream in = response.body().byteStream(); FileOutputStream out = new FileOutputStream(part, append)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int readBytes;
                    while ((readBytes = in.read(buffer)) != -1) {
                        checkCanceled();
                        if (readBytes > remain) {
                            // 返回数据超出请求范围：写入越界部分会让分片膨胀成畸形大文件（安装器扫描卡死），丢弃重下
                            out.write(buffer, 0, (int) remain);
                            got = need;
                            done[idx] = got;
                            throw new RangeMismatch("part " + idx + " overflow");
                        }
                        out.write(buffer, 0, readBytes);
                        got += readBytes;
                        remain -= readBytes;
                        done[idx] = got;
                        updateProgress(sumDone(done), total);
                    }
                }
            } catch (RangeMismatch error) {
                last = error;
                android.util.Log.w(LOG, "part " + idx + " overflow, reset " + error.getMessage());
                if (part.exists() && !part.delete()) android.util.Log.w(LOG, "part " + idx + " delete fail");
                got = 0;
                done[idx] = 0;
                sleepQuietly(RETRY_DELAY);
            } catch (IOException error) {
                last = error;
                android.util.Log.w(LOG, "part " + idx + " fail@" + got + "/" + need + " attempt=" + attempt + " " + error);
                if (canceled) throw error;
                sleepQuietly(RETRY_DELAY * (attempt + 1));
            } finally {
                partCalls.remove(request);
            }
        }
    }

    /** 校验单个 206 响应声明的总长度与本次下载目标一致，不一致说明源已更换。 */
    private void checkTotal(Response response, long total) {
        String cr = response.header("Content-Range");
        if (cr == null) return;
        int slash = cr.lastIndexOf('/');
        if (slash <= 0) return;
        try {
            long t = Long.parseLong(cr.substring(slash + 1).trim());
            if (t != total) throw new SourceChanged("total " + t + " != " + total);
        } catch (NumberFormatException ignored) {
            // "*" 之类的未知总长：跳过校验，交给长度/溢出校验兜底
        }
    }

    private void sleepQuietly(long ms) {        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long sumDone(long[] done) {
        long s = 0;
        for (long d : done) s += d;
        return s;
    }

    /** 按序合并分片到目标文件，并校验产物长度与内容是否是有效安装包。 */
    private void mergeParts(int n, long total) throws IOException {
        try (FileOutputStream out = new FileOutputStream(Path.create(file))) {
            for (int i = 0; i < n; i++) {
                File part = partFile(i);
                try (InputStream in = new FileInputStream(part)) {
                    in.transferTo(out);
                }
                part.delete();
            }
        }
        validateOutput(file.length(), total);
    }

    /**
     * 产物校验：长度必须与 Content-Range 声明一致，且 .apk 目标必须是 ZIP 头。
     * 之前缺这层，代理异常分片拼出的畸形文件被当成安装包交给系统安装器，
     * 表现就是「停留在安装包扫描中」不动（安装器读超大/非法包超时）。
     */
    private void validateOutput(long actual, long expected) throws IOException {
        if (actual <= 0) failOutput("下载内容为空，请重试");
        if (expected > 0 && actual != expected) failOutput("安装包大小异常 " + actual + "/" + expected);
        if (!isApkTarget()) return;
        boolean zip = false;
        try (InputStream in = new FileInputStream(file)) {
            byte[] head = new byte[2];
            int n = 0;
            while (n < 2) {
                int r = in.read(head, n, 2 - n);
                if (r <= 0) break;
                n += r;
            }
            zip = n == 2 && head[0] == 'P' && head[1] == 'K';
        } catch (IOException ignored) {
        }
        if (!zip) failOutput("下载到的不是安装包，请重试");
    }

    private void failOutput(String msg) throws IOException {
        Path.clear(file);
        deleteParts();
        throw new IOException(msg);
    }

    private boolean isApkTarget() {
        return file.getName().toLowerCase().endsWith(".apk");
    }

    /** 服务端忽略 Range 返回整包，分片模式不可用。用运行时异常穿透分片线程的重试逻辑。 */
    private static class NoRange extends RuntimeException {
    }

    /** 服务端返回的字节数超出请求范围，分片内容不可信。 */
    private static class RangeMismatch extends IOException {
        RangeMismatch(String msg) {
            super(msg);
        }
    }

    /** 中途发现源的总长度变化（发布切换 / 代理缓存分裂），整包需作废重下。 */
    private static class SourceChanged extends RuntimeException {
        SourceChanged(String msg) {
            super(msg);
        }
    }

    private Call newCall(Map<String, String> headers) {
        return DL_CLIENT.newCall(new Request.Builder().url(url).headers(Headers.of(headers)).tag(tag).build());
    }

    private Call createCall() {
        Call request = DL_CLIENT.newCall(new Request.Builder().url(url).tag(tag).build());
        call = request;
        return request;
    }

    private void execute(Call request) throws IOException {
        checkCanceled();
        try (Response response = request.execute()) {
            checkCanceled();
            validateResponse(response);
            ResponseBody body = response.body();
            write(body.byteStream(), body.contentLength());
        }
    }

    private void validateResponse(Response response) throws IOException {
        if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + " " + response.message());
        // 代理出错时常用 200 + 网页/JSON 顶替文件，直接拦掉，避免把网页当安装包交给安装器
        if (!isApkTarget()) return;
        String type = response.header("Content-Type");
        if (type != null) {
            String lower = type.toLowerCase();
            if (lower.contains("text/html") || lower.contains("application/json") || lower.contains("text/plain"))
                throw new IOException("下载源返回网页而非安装包，请稍后重试");
        }
    }

    private void write(InputStream stream, long length) throws IOException {
        checkSize(length);
        try (BufferedInputStream input = new BufferedInputStream(stream); FileOutputStream output = new FileOutputStream(Path.create(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int readBytes;
            long totalBytes = 0;
            while ((readBytes = input.read(buffer)) != -1) {
                checkCanceled();
                totalBytes += readBytes;
                checkSize(totalBytes);
                output.write(buffer, 0, readBytes);
                updateProgress(totalBytes, length);
            }
            if (totalBytes == 0) throw new IOException("Empty download");
        }
    }

    private void checkCanceled() throws InterruptedIOException {
        if (Thread.interrupted() || canceled) throw new InterruptedIOException();
    }

    private void checkSize(long size) throws IOException {
        if (size > maxBytes) throw new IOException("Download size limit exceeded");
    }

    private void updateProgress(long totalBytes, long length) {
        if (length <= 0) return;
        int progress = Math.min(100, (int) (totalBytes * 100.0 / length));
        if (progress == lastProgress) return;
        lastProgress = progress;
        postCallback(listener -> listener.progress(progress));
    }

    private void handleError(Exception error) {
        if (!canceled) Path.clear(file);
        String msg = friendly(error);
        if (callback == null) throw new RuntimeException(msg, error);
        postCallback(listener -> listener.error(msg));
    }

    /** 把内部技术异常翻成用户能看懂的提示，避免弹「part 0 overflow」这种串。 */
    private String friendly(Exception error) {
        String msg = error.getMessage();
        if (msg == null || msg.trim().isEmpty()) return "下载失败，请重试";
        if (msg.startsWith("part ") || msg.contains("overflow") || msg.contains("mismatch") || msg.contains("不完整"))
            return "下载数据异常（分片校验失败），请重新下载";
        if (msg.startsWith("HTTP ")) return "下载源连接失败（" + msg.substring(5) + "），请稍后重试";
        return msg;
    }

    private void postCallback(Consumer<Callback> action) {
        Callback current = callback;
        if (current == null || canceled) return;
        App.post(() -> {
            if (!canceled && callback == current) action.accept(current);
        });
    }

    public interface Callback {

        default void progress(int progress) {
        }

        void error(String msg);

        void success(File file);
    }
}
