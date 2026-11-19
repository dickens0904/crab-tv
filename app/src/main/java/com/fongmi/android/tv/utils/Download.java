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
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class Download {

    private static final int BUFFER_SIZE = 16 * 1024;
    /** 多线程下载线程数。gh-proxy 等支持 range 的代理，多线程比单线程快 10 倍+（实测 43KB/s→545KB/s）。 */
    private static final int THREADS = 10;
    /** 单片最大重试次数：代理会中途 reset 连接，断在中间必须能续传而不是整体失败。 */
    private static final int MAX_RETRY = 6;
    /** 单片重试退避基准（毫秒），第 n 次等待 n * RETRY_DELAY。 */
    private static final long RETRY_DELAY = 400;
    /** 排查用日志：adb logcat -s CrabTV-DL */
    private static final String LOG = "CrabTV-DL";

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
        deleteParts();
        future = null;
        executor = null;
        return this;
    }

    /**
     * 下载专用 client：不挂 http 缓存。
     * client() 带 16MB 配置缓存，大文件走 Range 分片时会把 206 响应写进缓存再丢弃，
     * 盒子这类慢 IO 设备等于双倍写盘，容易中途断流；下载本身也不需要缓存。
     */
    private static final OkHttpClient DL_CLIENT = OkHttp.player();

    private void doInBackground() {
        try {
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
    }

    /** 多线程分片下载：每线程下载一段到临时分片（失败自动续传重试），全部完成后按序合并。 */
    private void downloadMulti(long total) throws Exception {
        checkSize(total);
        int n = Math.min(THREADS, Math.max(1, (int) Math.ceil(total / (1024L * 1024))));
        long chunk = total / n;
        executor = Executors.newFixedThreadPool(n);
        android.util.Log.i(LOG, "multi start total=" + total + " threads=" + n + " chunk=" + chunk);
        List<Future<?>> futures = new ArrayList<>();
        long[] done = new long[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final long start = i * chunk;
            final long end = (i == n - 1) ? total - 1 : (i + 1) * chunk - 1;
            final File part = partFile(i);
            futures.add(executor.submit((Callable<Void>) () -> {
                downloadPart(idx, start, end, part, done, total);
                return null;
            }));
        }
        try {
            for (Future<?> f : futures) f.get();
        } finally {
            executor.shutdownNow();
        }
        long sum = sumDone(done);
        android.util.Log.i(LOG, "multi done sum=" + sum + "/" + total);
        if (sum < total) throw new IOException("下载不完整 " + sum + "/" + total);
        mergeParts(n);
    }

    private File partFile(int i) {
        return new File(file.getParentFile(), file.getName() + ".part" + i);
    }

    private void deleteParts() {
        try {
            for (int i = 0; i < THREADS; i++) {
                File p = partFile(i);
                if (p.exists()) p.delete();
            }
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
                // 服务端忽略 Range 返回 200 时，只能从头重写，避免内容错位
                if (code == 200) {
                    got = 0;
                    append = false;
                }
                Path.create(part);
                try (InputStream in = response.body().byteStream(); FileOutputStream out = new FileOutputStream(part, append)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int readBytes;
                    while ((readBytes = in.read(buffer)) != -1) {
                        checkCanceled();
                        out.write(buffer, 0, readBytes);
                        got += readBytes;
                        done[idx] = got;
                        updateProgress(sumDone(done), total);
                    }
                }
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

    private void sleepQuietly(long ms) {
        try {
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

    /** 按序合并分片到目标文件。 */
    private void mergeParts(int n) throws IOException {
        try (FileOutputStream out = new FileOutputStream(Path.create(file))) {
            for (int i = 0; i < n; i++) {
                File part = partFile(i);
                try (InputStream in = new FileInputStream(part)) {
                    in.transferTo(out);
                }
                part.delete();
            }
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
        if (callback == null) throw new RuntimeException(error.getMessage(), error);
        postCallback(listener -> listener.error(error.getMessage()));
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
