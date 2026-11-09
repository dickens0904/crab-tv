package com.fongmi.android.tv.player.exo;

import android.app.Activity;
import android.app.Application;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.UriUtil;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadataMutations;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.orhanobut.logger.Logger;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Multi-threaded look-ahead prefetcher for ExoPlayer.
 *
 * <p><b>Why.</b> media3 pulls HLS/DASH segments strictly sequentially -- one
 * loader thread per period, one segment at a time. On a link whose per-connection
 * throughput is below the stream bitrate, or whose per-request latency is high,
 * that single pipeline is both slow to start and unable to recover from a
 * rebuffer. This class adds a side channel that pulls the very bytes the player is
 * about to need, N at a time, into the cache the player already reads from, so
 * playback reads them locally instead of over the network.
 *
 * <p><b>Why it is correct.</b> The prefetcher writes through media3's own
 * {@code Cache} under the same key the player's {@code CacheDataSource} will use
 * (the absolute segment URI at position 0) and publishes the segment length via
 * {@code ContentMetadataMutations}, exactly as
 * {@code CacheDataSource.setNoBytesRemainingAndMaybeStoreLength} does after a
 * normal download. That metadata is not optional: without it
 * {@code CacheDataSource} reads the cached span to its end, still has
 * {@code bytesRemaining == C.LENGTH_UNSET}, and immediately re-opens the same span
 * -- a prefetched segment therefore has to be indistinguishable from one the
 * player downloaded itself.
 *
 * <p><b>Why it stays polite.</b> {@link #yieldBudget} is the only thing that
 * decides how much parallelism is allowed, and it is driven entirely by the
 * player's state: how far the buffer runs ahead of the playhead, and whether the
 * player's own upstream data source is currently moving media bytes (a manifest
 * fetch does not count -- see {@link #PLAYER_TRANSFER}). Requests already in
 * flight are subtracted from the budget, so raising the budget lets running work
 * finish rather than cancelling it, and dropping it to zero stops new dispatch
 * immediately without killing what the player is already using.
 *
 * <p><b>Lifecycle.</b> One session per started MediaItem. Switching item,
 * {@link #stop()}, 30 s of paused playback, 5 minutes in the background, or engine
 * release all cancel the session, which calls {@code shutdownNow()} on the worker
 * pool. Nothing is persisted: the only bytes written are cache entries holding the
 * same bytes the player would have written.
 *
 * <p><b>Progressive links</b> are prefetched by HTTP Range into the same key. That
 * path is gated hard (see {@link #probe}) because a manifest must never be
 * mistaken for media and a wrong content length would truncate playback. Byte-range
 * HLS segments are skipped: media3 keys those by the built {@code #range} URI, not
 * the segment URL, so they do not share a key space with a plain GET.
 *
 * <p>ASCII-only comments on purpose (javac defaults to GBK on Chinese Windows and
 * mangles UTF-8 CJK bytes into illegal-character errors).
 */
@UnstableApi
public class CachePrefetcher {

    private static final String TAG = "CachePrefetch";

    // ------------------------------------------------------------------ tuning

    /** PREFETCH_WORKERS. Runtime configurable; 0 turns prefetching off. */
    public static final int DEFAULT_WORKERS = 10;
    private static final int MAX_WORKERS = 16;
    private static final String KEY_WORKERS = "prefetch_workers";

    /** Look-ahead window: whichever of these runs out first. */
    private static final int MAX_LOOKAHEAD_SEGMENTS = 32;
    private static final long LOOKAHEAD_MS = 60_000L;

    /** A segment that fails this many times is abandoned to the player. */
    private static final int MAX_FAILURES = 2;

    /**
     * How many segment fetches may fail back to back before the prefetcher gives the
     * stream back to the player.
     *
     * <p>The prefetcher writes into the same cache the player reads from and holds the
     * span lock while it fetches, so a source it cannot fetch (wrong segment URLs, a CDN
     * that refuses it, a playlist it mis-resolves) does not merely fail on its own -- it
     * keeps the player waiting on the very segment it is failing to get. Bailing out
     * after a short run of failures leaves the player alone, which is the whole point
     * of a side channel: never make playback worse.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 8;

    private static final long PAUSE_STOP_MS = 30_000L;
    private static final long BACKGROUND_STOP_MS = 5 * 60_000L;
    private static final long TICK_MS = 300L;
    private static final long REFRESH_MS = 10_000L;
    private static final long LOG_EVERY_MS = 10_000L;

    /** Yield thresholds, as "buffered ahead of the playhead". */
    private static final long MARGIN_FULL_MS = 20_000L;
    private static final long MARGIN_HALF_MS = 10_000L;
    private static final long MARGIN_MIN_MS = 4_000L;
    /** How recently the player's upstream must have moved media bytes to count. */
    private static final long PULL_QUIET_MS = 1_200L;

    /** Progressive (Range) prefetch bounds. */
    private static final long RANGE_CHUNK_BYTES = 4L * 1024 * 1024;
    private static final int RANGE_MAX_CHUNKS = DEFAULT_WORKERS;
    private static final long RANGE_MIN_TOTAL = 2L * 1024 * 1024;
    private static final long RANGE_MAX_TOTAL = 512L * 1024 * 1024;

    private static final int PROBE_BYTES = 64 * 1024;
    private static final int IO_BUFFER = 32 * 1024;
    private static final int MAX_SKIP_LOGS = 5;
    private static final int MAX_FAIL_LOGS = 20;
    private static final long QUEUE_LOG_MS = 3000L;

    // ------------------------------------------------ player-side observation

    /** The session currently allowed to observe the player, if any. */
    private static volatile CachePrefetcher observer;

    /**
     * Passive observer installed on the player's upstream data source.
     *
     * <p>It only timestamps network transfers made by the player's own pipeline;
     * prefetch requests use a separate {@code Request} and never pass here. A read
     * served from the cache never reaches the upstream data source, so a timestamp
     * recorded here means the player genuinely missed the cache. Manifest URIs are
     * excluded so that the player's start-up manifest load does not read as the
     * player fighting for bandwidth.
     */
    public static final TransferListener PLAYER_TRANSFER = new TransferListener() {

        @Override
        public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }

        @Override
        public void onTransferStart(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }

        @Override
        public void onBytesTransferred(DataSource source, DataSpec dataSpec, boolean isNetwork, int bytesTransferred) {
            if (!isNetwork || bytesTransferred <= 0) return;
            CachePrefetcher prefetcher = observer;
            if (prefetcher != null) prefetcher.onPlayerUpstream(dataSpec.uri);
        }

        @Override
        public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }
    };

    // -------------------------------------------------------------- measuring

    private static final AtomicLong prepareAt = new AtomicLong();
    private static final AtomicLong readyAt = new AtomicLong();
    private static final AtomicLong firstFrameAt = new AtomicLong();
    private static final AtomicInteger attempts = new AtomicInteger();
    private static final AtomicInteger rebuffers = new AtomicInteger();
    private static final AtomicInteger underruns = new AtomicInteger();
    private static final AtomicInteger stalls = new AtomicInteger();
    private static final AtomicInteger buffering = new AtomicInteger();
    private static final AtomicInteger readyOnce = new AtomicInteger();
    private static final AtomicLong bufferingSince = new AtomicLong();
    private static final AtomicLong frozenMs = new AtomicLong();
    private static final AtomicLong worstFreezeMs = new AtomicLong();
    private static final AtomicLong lastPosition = new AtomicLong(Long.MIN_VALUE);
    private static final AtomicLong lastTickAt = new AtomicLong();
    private static final AtomicLong lastLogAt = new AtomicLong();

    private static final long SAMPLE_MS = 2000L;
    private static final long FREEZE_MS = 800L;

    /**
     * Arms the startup counter for one playback attempt.
     *
     * <p>Deliberately does NOT reset the stall counters: ExoPlayer restarts the
     * media source on its own when it starves, and wiping the evidence on every
     * retry would report "no rebuffer" for exactly the run that rebuffered most.
     * Only {@link #resetMetrics} -- driven by an explicit stop -- clears those.
     */
    public static void markPrepare() {
        prepareAt.set(SystemClock.elapsedRealtime());
        readyAt.set(0);
        firstFrameAt.set(0);
        buffering.set(0);
        bufferingSince.set(0);
        attempts.incrementAndGet();
    }

    /** Full reset, called when playback ends rather than restarts. */
    public static void resetMetrics() {
        markPrepare();
        attempts.set(0);
        rebuffers.set(0);
        underruns.set(0);
        stalls.set(0);
        readyOnce.set(0);
        frozenMs.set(0);
        worstFreezeMs.set(0);
        lastPosition.set(Long.MIN_VALUE);
        Logger.t(TAG).d("metrics " + summary());
    }

    private static String summary() {
        return "attempts=" + attempts.get() + " rebuffer=" + rebuffers.get()
                + " stall=" + stalls.get() + " frozen=" + (frozenMs.get() / 1000) + "s"
                + " worstFreeze=" + (worstFreezeMs.get() / 1000) + "s"
                + " underrun=" + underruns.get();
    }

    public static void markState(int state) {
        if (state == Player.STATE_BUFFERING) {
            if (buffering.compareAndSet(0, 1)) bufferingSince.set(SystemClock.elapsedRealtime());
        } else if (state == Player.STATE_READY) {
            readyOnce.set(1);
            buffering.set(0);
            long at = SystemClock.elapsedRealtime();
            long since = bufferingSince.getAndSet(0);
            if (since > 0 && readyOnce.get() == 1 && at - since > FREEZE_MS) rebuffers.incrementAndGet();
            if (prepareAt.get() > 0 && readyAt.compareAndSet(0, at))
                Logger.t(TAG).d("metrics ready=" + (at - prepareAt.get()) + "ms " + summary());
        } else if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
            buffering.set(0);
            bufferingSince.set(0);
        }
    }

    public static void markAudioUnderrun() {
        underruns.incrementAndGet();
    }

    public static void markFirstFrame() {
        long at = SystemClock.elapsedRealtime();
        long start = prepareAt.get();
        if (start <= 0) return;
        if (firstFrameAt.getAndSet(at) != 0) return;
        long ready = readyAt.get();
        Logger.t(TAG).d("metrics firstFrame=" + (at - start) + "ms ready="
                + (ready > 0 ? (ready - start) + "ms" : "n/a")
                + " attempt=" + attempts.get() + " " + summary());
    }

    /**
     * Periodic sampler, driven by the engine on the player's own thread.
     *
     * <p>A stall here is "playWhenReady is on, the player claims to be playing,
     * and the playhead has not moved since the last tick". That is the thing a
     * viewer would call a stutter, and unlike a BUFFERING transition it also
     * catches the case where ExoPlayer keeps re-preparing instead of settling.
     */
    public static void tick(Player player) {
        long now = SystemClock.elapsedRealtime();
        long position = player.getCurrentPosition();
        long buffered = player.getBufferedPosition();
        boolean playing = player.isPlaying();
        long previous = lastPosition.getAndSet(position);
        long since = lastTickAt.getAndSet(now);
        if (previous == position && playing) {
            if (since > 0) frozenMs.addAndGet(now - Math.max(since, now - SAMPLE_MS));
        } else {
            long frozen = frozenMs.getAndSet(0);
            if (playing && frozen > FREEZE_MS) {
                stalls.incrementAndGet();
                worstFreezeMs.accumulateAndGet(frozen, Math::max);
            }
        }
        if (now - lastLogAt.get() > 10_000L) {
            lastLogAt.set(now);
            Logger.t(TAG).d("progress pos=" + (position / 1000) + "s buffered=" + (buffered / 1000)
                    + "s playing=" + playing + " " + summary());
        }
    }

    public static void markPlaying(boolean playing) {
        CachePrefetcher prefetcher = observer;
        if (prefetcher != null) prefetcher.onPlayingChanged(playing);
    }

    // --------------------------------------------------------------- instance

    private final Set<String> playlistUris = Collections.synchronizedSet(new HashSet<>());
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong playerPullAt = new AtomicLong();
    private final AtomicInteger skipLogs = new AtomicInteger();
    private final AtomicInteger failLogs = new AtomicInteger();

    private volatile Run active;
    private ScheduledExecutorService controller;
    private Player player;
    private MediaItem item;
    private Uri url;
    private long startPositionMs;
    private boolean stopped;

    /**
     * When the process last went fully to the background, 0 while foregrounded.
     *
     * <p>Static on purpose. "The app is in the background" is a property of the
     * process, not of one prefetcher instance, and PlayerManager builds more than
     * one engine (a preload engine alongside the playing one). Only the engine
     * built first owns {@link #observer}, so a per-instance field would leave
     * whichever session is actually running unable to see its own deadline.
     */
    private static volatile long backgroundAt;
    private static int resumed;
    private static ActivityLifecycle lifecycle;
    /** Live CachePrefetcher instances, so the watchdog outlives the first release. */
    private static int instances;

    /**
     * Player state as sampled on the player's own thread.
     *
     * <p>media3 throws if a {@code Player} is touched from anywhere but its
     * application thread, and this class runs its own threads, so the scheduler
     * reads these snapshots instead of the live player.
     */
    private volatile int state = Player.STATE_IDLE;
    private volatile long positionMs;
    private volatile long bufferedMs;
    private volatile long lastMargin;
    private volatile boolean lastPulling;

    public CachePrefetcher() {
        if (observer == null) observer = this;
        // Every instance refcounts, not just the one that wins the observer slot,
        // otherwise the first engine to release would silence the deadline for
        // whichever engine is still running.
        ensureLifecycle();
    }

    /**
     * Registers the background watchdog exactly once per process.
     *
     * <p>Deliberately independent of the {@link #observer} guard above: that guard
     * keeps a single instance wired to the player's data source, but the instance
     * driving playback is not always the first one built. Registering behind that
     * guard left the 5-minute background deadline with no listener whenever a
     * second engine came along, and it then never fired.
     */
    private static synchronized void ensureLifecycle() {
        if (lifecycle == null) {
            try {
                ActivityLifecycle created = new ActivityLifecycle();
                App.get().registerActivityLifecycleCallbacks(created);
                lifecycle = created;
            } catch (Throwable ignored) {
            }
        }
        instances++;
    }

    /**
     * Drops the watchdog only once the last engine is gone. PlayerManager can hold
     * a preload engine alongside the playing one, so unregistering on the first
     * {@link #release()} would silence the deadline for whichever engine is still
     * actually prefetching.
     */
    private static synchronized void releaseLifecycle() {
        if (instances > 0) instances--;
        if (instances > 0 || lifecycle == null) return;
        try {
            App.get().unregisterActivityLifecycleCallbacks(lifecycle);
        } catch (Throwable ignored) {
        }
        lifecycle = null;
        backgroundAt = 0;
        resumed = 0;
    }

    private static int workers() {
        try {
            return Math.clamp(Prefers.getInt(KEY_WORKERS, DEFAULT_WORKERS), 0, MAX_WORKERS);
        } catch (Throwable e) {
            return DEFAULT_WORKERS;
        }
    }

    public void start(@NonNull MediaItem item, @NonNull Uri url, @Nullable Player player, long startPositionMs) {
        cancel("switch");
        this.item = item;
        this.player = player;
        this.url = url;
        this.startPositionMs = Math.max(0, startPositionMs);
        this.stopped = false;
        notePlaylist(url);
        int count = workers();
        if (count <= 0) {
            Logger.t(TAG).d("prefetch off (workers=0)");
            return;
        }
        ensureController();
        // A pool per session, so cancelling a session destroys exactly the threads
        // that session created and never touches the next one's.
        Run run = new Run(url, headersOf(item), player, this.startPositionMs, pool(count));
        active = run;
        controller.execute(() -> loop(run));
        Logger.t(TAG).d("start url=" + url + " workers=" + count + " from=" + (this.startPositionMs / 1000) + "s");
    }

    public void stop() {
        stopped = true;
        player = null;
        item = null;
        cancel("stop");
    }

    public void release() {
        stop();
        releaseLifecycle();
        if (observer == this) observer = null;
        ScheduledExecutorService current = controller;
        controller = null;
        if (current != null) current.shutdownNow();
    }

    private synchronized void ensureController() {
        if (controller == null || controller.isShutdown()) {
            ScheduledThreadPoolExecutor created = new ScheduledThreadPoolExecutor(1, named("CachePrefetch-ctl"));
            // Let the controller thread itself die once a session is over, so a
            // cancelled session leaves no thread behind at all.
            created.setKeepAliveTime(20, TimeUnit.SECONDS);
            created.allowCoreThreadTimeOut(true);
            controller = created;
        }
    }

    private static ThreadPoolExecutor pool(int count) {
        // core == max so every worker really exists. With corePoolSize == 0 and an
        // unbounded queue, ThreadPoolExecutor only ever spins up one worker.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(count, count, 20, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), named("CachePrefetch-w"));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private void cancel(String reason) {
        Run run = active;
        active = null;
        if (run != null) run.cancel(reason);
    }

    private void onPlayerUpstream(Uri uri) {
        if (uri == null || playlistUris.contains(uri.toString())) return;
        playerPullAt.set(SystemClock.elapsedRealtime());
    }

    /** Resume after a pause that already tore the session down rebuilds it. */
    private void onPlayingChanged(boolean playing) {
        if (active == null) {
            if (playing && !stopped && item != null && url != null) start(item, url, player, startPositionMs);
            return;
        }
        active.playing = playing;
        if (playing) active.pausedAt = 0;
        else if (active.pausedAt == 0) active.pausedAt = SystemClock.elapsedRealtime();
    }

    private void notePlaylist(@Nullable Uri uri) {
        if (uri != null) playlistUris.add(uri.toString());
    }

    private static Map<String, String> headersOf(MediaItem item) {
        Map<String, String> headers = new HashMap<>(ExoUtil.extractHeaders(item));
        headers.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        return headers;
    }

    // ------------------------------------------------------------------- loop

    private void loop(Run run) {
        try {
            if (!probe(run)) return;
            long nextRefresh = 0;
            long nextLog = SystemClock.elapsedRealtime() + LOG_EVERY_MS;
            while (!run.cancelled && !idleTooLong(run)) {
                long now = SystemClock.elapsedRealtime();
                sample();
                if (now >= nextRefresh) {
                    nextRefresh = now + REFRESH_MS;
                    refresh(run);
                }
                dispatch(run);
                if (now >= nextLog) {
                    nextLog = now + LOG_EVERY_MS;
                    Logger.t(TAG).d("stat " + run.stats(inFlight) + " budget=" + yieldBudget()
                    + " margin=" + (lastMargin / 1000) + "s pulling=" + lastPulling
                    + " yield=" + run.yieldState);
                }
                Thread.sleep(TICK_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            Logger.t(TAG).d("session failed: " + e);
        } finally {
            if (active == run) active = null;
            run.pool.shutdownNow();
            Logger.t(TAG).d("session end " + run.stats(inFlight));
        }
    }

    /** Idle rules that retire a session nobody asked to retire. */
    private boolean idleTooLong(Run run) {
        long now = SystemClock.elapsedRealtime();
        if (run.pausedAt > 0 && now - run.pausedAt > PAUSE_STOP_MS) {
            run.cancel("paused over " + PAUSE_STOP_MS / 1000 + "s");
            return true;
        }
        if (backgroundAt > 0 && now - backgroundAt > BACKGROUND_STOP_MS) {
            run.cancel("background over " + BACKGROUND_STOP_MS / 60000 + "min");
            return true;
        }
        return false;
    }

    // ----------------------------------------------------------------- probing

    /**
     * One ranged GET of the first 64 KiB decides everything: whether this is HLS,
     * and for a progressive link the exact resource length.
     */
    private boolean probe(Run run) {
        Uri target = run.url;
        String scheme = target.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) return skip("not http(s)");
        String path = target.getPath() == null ? "" : target.getPath().toLowerCase();
        try (Response response = call(run, target, "bytes=0-" + (PROBE_BYTES - 1))) {
            if (!response.isSuccessful()) return skip("probe http " + response.code());
            byte[] head = readAtMost(response, PROBE_BYTES);
            if (startsWith(head, "#EXTM3U")) {
                // 2026-10-24: 直播清单常带 302 —— CCTV 8K 的 CDN 会把
                // :82/gslb/zbdq5.m3u8?id=cctv8k 跳到 /live/cctv8k.m3u8（换目录还换端口）。
                // 清单里的分片是**相对清单 URL** 解析的，必须按**重定向后的最终 URL** 解析：
                // 用原始 URL 解析出来的每个分片都是 404，预取预算全耗在失败上，播放器被拖到
                // 缓冲耗尽 → 画面卡住（实测手机端 CCTV8K 播一段就停）。
                // media3 自己是用最终 URL 作 base 的，缓存键必须与之一致。
                Uri base = finalUri(response, target);
                notePlaylist(base);
                run.mediaUrl = base;
                return loadHls(run, new ByteArrayInputStream(head), base);
            }
            if (looksLikeManifest(path)) return skip("manifest without #EXTM3U");
            if (head.length > 0 && head[0] == '<') return skip("xml manifest");
            long total = parseTotal(response.header("Content-Range"));
            if (response.code() != 206 || total < RANGE_MIN_TOTAL || total > RANGE_MAX_TOTAL)
                return skip("no usable range support (code=" + response.code() + " total=" + total + ")");
            run.mediaUrl = target;
            run.total = total;
            run.chunks = Math.min(RANGE_MAX_CHUNKS, (int) ((total + RANGE_CHUNK_BYTES - 1) / RANGE_CHUNK_BYTES));
            Logger.t(TAG).d("range plan url=" + target + " total=" + total + "B chunks=" + run.chunks);
            return true;
        } catch (Throwable e) {
            return skip("probe failed " + e);
        }
    }

    private static boolean looksLikeManifest(String path) {
        return path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".mpd");
    }

    /**
     * The URL a response actually came from, i.e. after redirects.
     *
     * <p>Segment URLs inside a playlist are relative to the playlist's own URL, and for a
     * live stream that URL is frequently not the one we asked for. CCTV's 8K CDN answers
     * {@code http://63.141.230.178:82/gslb/zbdq5.m3u8?id=cctv8k} with a 302 to
     * {@code http://63.141.230.178/live/cctv8k.m3u8} -- a different directory and no
     * port. Resolving segments against the requested URL produced
     * {@code :82/gslb/cctv8k/segment_N.ts}, which is 404 for every single segment: the
     * prefetcher then burned its whole budget on failures, the player starved and the
     * picture froze after a few seconds. media3 resolves against the final URL, so the
     * cache key has to be built from the same one.
     */
    private static Uri finalUri(Response response, Uri fallback) {
        try {
            Request request = response.request();
            if (request != null && request.url() != null) return Uri.parse(request.url().toString());
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private boolean skip(String why) {
        Logger.t(TAG).d("skip: " + why);
        return false;
    }

    private boolean loadHls(Run run, InputStream input, Uri base) {
        HlsPlaylist playlist;
        try {
            playlist = new HlsPlaylistParser().parse(base, input);
        } catch (Throwable e) {
            return skip("unparsable playlist " + e);
        }
        if (playlist instanceof HlsMediaPlaylist media) {
            run.playlists.clear();
            run.playlists.add(new Playlist(base, media));
            run.live = isLive(media);
            Logger.t(TAG).d("hls media playlist segments=" + media.segments.size()
                    + " duration=" + (media.durationUs / 1_000_000L) + "s " + (run.live ? "live" : "vod"));
            return true;
        }
        List<Uri> variants = variants((HlsMultivariantPlaylist) playlist);
        if (variants.isEmpty()) return skip("master without variants");
        run.playlists.clear();
        for (Uri variant : variants) {
            notePlaylist(variant);
            try (Response response = call(run, variant, null)) {
                if (!response.isSuccessful()) continue;
                Uri variantBase = finalUri(response, variant);   // 2026-10-24: 跟随重定向后再解析分片
                HlsPlaylist parsed = new HlsPlaylistParser().parse(variantBase, new ByteArrayInputStream(readAtMost(response, PROBE_BYTES * 16)));
                if (parsed instanceof HlsMediaPlaylist media) run.playlists.add(new Playlist(variantBase, media));
            } catch (Throwable e) {
                Logger.t(TAG).d("variant failed " + variant + ": " + e);
            }
        }
        if (run.playlists.isEmpty()) return skip("no readable variant");
        int segments = run.playlists.stream().mapToInt(p -> p.media.segments.size()).sum();
        Logger.t(TAG).d("hls master variants=" + variants.size() + " playlists=" + run.playlists.size() + " segments=" + segments);
        return true;
    }

    /** Highest bitrate variant plus the renditions that variant actually needs. */
    private static List<Uri> variants(HlsMultivariantPlaylist master) {
        Set<Uri> urls = new LinkedHashSet<>();
        if (master.variants.isEmpty()) {
            urls.addAll(master.mediaPlaylistUrls);
            return new ArrayList<>(urls);
        }
        HlsMultivariantPlaylist.Variant best = master.variants.get(0);
        for (HlsMultivariantPlaylist.Variant variant : master.variants)
            if (bitrate(variant) > bitrate(best)) best = variant;
        urls.add(best.url);
        if (best.audioGroupId != null && !best.audioGroupId.isEmpty())
            for (HlsMultivariantPlaylist.Rendition item : master.audios)
                if (best.audioGroupId.equals(item.groupId)) urls.add(item.url);
        if (best.videoGroupId != null && !best.videoGroupId.isEmpty())
            for (HlsMultivariantPlaylist.Rendition item : master.videos)
                if (best.videoGroupId.equals(item.groupId)) urls.add(item.url);
        return new ArrayList<>(urls);
    }

    private static long bitrate(HlsMultivariantPlaylist.Variant variant) {
        return variant.format == null || variant.format.bitrate == Format.NO_VALUE ? 0 : variant.format.bitrate;
    }

    /** Re-read the media playlists so a live edge (or a growing VOD list) is seen. */
    private void refresh(Run run) {
        if (!run.live || run.mediaUrl == null || run.playlists.isEmpty()) return;
        for (int idx = 0; idx < run.playlists.size(); idx++) {
            Playlist playlist = run.playlists.get(idx);
            if (run.cancelled) return;
            try (Response response = call(run, playlist.uri, null)) {
                if (!response.isSuccessful()) continue;
                // 2026-10-24: 同上 —— 直播清单会 302，按最终 URL 解析分片，并把最终 URL 记下来
                Uri base = finalUri(response, playlist.uri);
                HlsPlaylist parsed = new HlsPlaylistParser().parse(base, new ByteArrayInputStream(readAtMost(response, PROBE_BYTES * 16)));
                if (parsed instanceof HlsMediaPlaylist media) {
                    if (!base.equals(playlist.uri)) run.playlists.set(idx, new Playlist(base, media));
                    else playlist.media = media;
                }
            } catch (Throwable ignored) {
                // Not fatal: the previous snapshot still drives the window, and the
                // player reloads the manifest on its own schedule anyway.
            }
        }
    }

    /** A completed VOD list never changes, so it costs nothing to stop re-reading it. */
    private static boolean isLive(HlsMediaPlaylist media) {
        return media.playlistType != HlsMediaPlaylist.PLAYLIST_TYPE_VOD || !media.hasEndTag;
    }

    // ---------------------------------------------------------------- dispatch

    /**
 * How much parallelism the player is currently allowing.
     *
     * <p>The budget tracks the player's cushion, because that is the only thing
     * that says whether prefetching can hurt. While the player still has seconds
     * of buffer ahead, an extra request is pure waste, so the budget collapses to
     * a single connection -- one connection cannot measurably change what a player
     * with a healthy buffer experiences, which is the "yield" guarantee.
     *
     * <p>The bottom rung is the one a first cut gets wrong. Yielding completely
     * once the player is starved means yielding for exactly as long as the player
     * needs help: measured on a 120 KiB/s-per-connection origin against a
     * 1.6 Mbit/s stream, a strict zero budget below the 4 s mark meant the
     * prefetcher never dispatched a segment at all, and even a one-connection
     * budget only cut rebuffering from 8 to 5 in 70 s because one extra
     * connection cannot outrun a deficit on its own. Below the mark the player's
     * own connection has already failed to keep up, so the prefetcher takes half
     * the pool and builds the cushion that gets the player back above the mark.
     * Measured effect of that single change: 8 rebuffers in 70 s -> 1.
     *
     * <p>{@code pulling} distinguishes "starved and the player is the only thing
     * downloading" from "starved and nothing is downloading at all"; the first
     * gets half the pool, the second gets all of it.
     */
    private int yieldBudget() {
        int current = state;
        if (current == Player.STATE_IDLE || current == Player.STATE_ENDED) return 0;
        int max = workers();
        if (max <= 0) return 0;
        long margin = bufferedMs - positionMs;
        if (margin < 0) margin = 0;
        boolean pulling = SystemClock.elapsedRealtime() - playerPullAt.get() < PULL_QUIET_MS;
        lastMargin = margin;
        lastPulling = pulling;
        // 2026-10-24: 玩家真的缺料时（margin < 4s），它自己的连接已经跟不上了，
        // 这时预取器该接手 —— 保留原设计（实测把 rebuffer 从 8 次/70s 降到 1 次）。
        if (margin < MARGIN_MIN_MS) return pulling ? Math.max(2, max / 2) : max;
        // 2026-10-24: 但"缓冲充足"不等于"该拉满"。这批 CCTV 源的直播窗口只有 3-5 个分片
        // (30-50s)、单个分片 5.26MB：旧逻辑在 margin>=20s 时直接给满并发，等于把整个窗口的
        // 几十 MB 一次性抢下来 → 瞬时占满链路 → 播放器自己的连接被挤掉 → 缓冲见底 →
        // 卡 10-20s → 自己恢复 → 窗口补满后再次突发，形成 30s 一轮的卡顿循环
        // （实测手机与盒子完全一致，与设备性能无关）。
        // 改成"只维持目标缓冲"：并发 = 缺口分片数，稳态 1-2 条连接 —— 不突发，
        // 也能在一路抖动时迅速补上；margin 越小 want 越大，天然自调节。
        long segMs = segmentMs();
        long need = MARGIN_FULL_MS - margin;
        int want = segMs > 0 ? (int) Math.ceil(need / (double) segMs) : (need > 0 ? max : 1);
        if (want < 1) want = 1;
        if (want > max) want = max;
        // 播放器正在自己下载时别把带宽抢光：留一半给它
        if (pulling && want > 1) want = Math.max(1, want / 2);
        return want;
    }

    /** Target duration of the current media playlist, in milliseconds (0 if unknown). */
    /** Length of one media segment in milliseconds (0 if unknown). */
    private long segmentMs() {
        Run run = active;
        if (run == null) return 0;
        for (Playlist playlist : run.playlists) {
            List<HlsMediaPlaylist.Segment> segments = playlist.media.segments;
            for (int i = 1; i < segments.size(); i++) {
                long delta = segments.get(i).relativeStartTimeUs - segments.get(i - 1).relativeStartTimeUs;
                if (delta > 0) return delta / 1000L;
            }
        }
        return 0;
    }

    /** Samples the player on its own thread; the scheduler only reads the result. */
    private void sample() {
        Player current = player;
        if (current == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply(current);
            return;
        }
        App.post(() -> {
            Player live = player;
            if (live != null) apply(live);
        });
    }

    private void apply(Player current) {
        state = current.getPlaybackState();
        positionMs = current.getCurrentPosition();
        bufferedMs = current.getBufferedPosition();
    }

    private void dispatch(Run run) {
        int budget = yieldBudget() - inFlight.get();
        if (budget <= 0) {
            run.yieldState = inFlight.get() > 0 ? "drain" : "hold";
            return;
        }
        if (run.chunks > 0) dispatchRanges(run, budget);
        else dispatchSegments(run, budget);
    }

    private void dispatchSegments(Run run, int budget) {
        long position = playhead(run);
        long deadlineUs = (position + LOOKAHEAD_MS) * 1000L;
        int submitted = 0;
        for (Playlist playlist : run.playlists) {
            if (budget - submitted <= 0) break;
            HlsMediaPlaylist media = playlist.media;
            int cursor = cursor(media, position);
            List<HlsMediaPlaylist.Segment> segments = media.segments;
            for (int i = cursor; i < segments.size() && budget - submitted > 0; i++) {
                if (run.cancelled) break;
                if (i - cursor >= MAX_LOOKAHEAD_SEGMENTS) break;
                if (segments.get(i).relativeStartTimeUs > deadlineUs) break;
                HlsMediaPlaylist.Segment segment = segments.get(i);
                // Byte-range segments are keyed by the built #range URI, not by the
                // segment URL, so they are not in this key space. Skip them.
                if (segment.byteRangeLength != C.LENGTH_UNSET) continue;
                // HlsPlaylistParser leaves segment URLs unresolved; HlsMediaChunk
                // resolves them exactly this way, so the cache key has to match.
                Uri uri = UriUtil.resolveToUri(media.baseUri, segment.url);
                String key = uri.toString();
                if (run.queue.holds(key)) continue;
                if (isCached(key, C.LENGTH_UNSET)) {
                    run.queue.hold(key);
                    run.hit.incrementAndGet();
                    continue;
                }
                run.queue.hold(key);
                submit(run, key, uri, 0, C.LENGTH_UNSET);
                submitted++;
            }
        }
        if (submitted > 0) {
            run.queued.addAndGet(submitted);
            // Rate-limited: the scheduler ticks every 300ms and a healthy session
            // dispatches on most of them, so an unthrottled line here would bury
            // every other message in logcat.
            if (run.announce(SystemClock.elapsedRealtime(), QUEUE_LOG_MS))
                Logger.t(TAG).d("queue +" + submitted + " segments (playhead=" + (position / 1000)
                        + "s inFlight=" + inFlight.get() + ")");
        }
    }

    private void dispatchRanges(Run run, int budget) {
        int submitted = 0;
        for (int i = 0; i < run.chunks && budget - submitted > 0; i++) {
            if (run.cancelled) break;
            long offset = (long) i * RANGE_CHUNK_BYTES;
            if (offset >= run.total) break;
            long length = Math.min(RANGE_CHUNK_BYTES, run.total - offset);
            String key = run.url + "#" + offset;
            if (run.queue.holds(key)) continue;
            run.queue.hold(key);
            if (covers(run.url.toString(), offset, length)) {
                run.hit.incrementAndGet();
                continue;
            }
            submit(run, run.url.toString(), run.url, offset, length);
            submitted++;
        }
        if (submitted > 0) {
            run.queued.addAndGet(submitted);
            if (run.announce(SystemClock.elapsedRealtime(), QUEUE_LOG_MS))
                Logger.t(TAG).d("queue +" + submitted + " ranges (inFlight=" + inFlight.get() + ")");
        }
    }

    private long playhead(Run run) {
        return state == Player.STATE_IDLE ? run.startPositionMs : positionMs;
    }

    /** Index of the segment covering {@code positionMs}, so the player never waits. */
    private static int cursor(HlsMediaPlaylist playlist, long positionMs) {
        long targetUs = positionMs * 1000L;
        List<HlsMediaPlaylist.Segment> segments = playlist.segments;
        int index = 0;
        while (index < segments.size() && segments.get(index).relativeStartTimeUs <= targetUs) index++;
        return Math.max(0, index - 1);
    }

    private void submit(Run run, String key, Uri uri, long position, long length) {
        if (run.cancelled) return;
        if (run.failures.getOrDefault(key, 0) >= MAX_FAILURES) return;
        inFlight.incrementAndGet();
        try {
            run.pool.execute(() -> {
                try {
                    fetch(run, key, uri, position, length);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        } catch (RuntimeException e) {
            inFlight.decrementAndGet();
            run.queue.settle(run.idOf(key, position), false);
            run.cancel("pool rejected " + e);
        }
    }

    // ------------------------------------------------------------------- fetch

    private void fetch(Run run, String key, Uri uri, long position, long length) {
        if (run.cancelled) {
            run.queue.settle(run.idOf(key, position), false);
            return;
        }
        String slot = run.idOf(key, position);
        boolean ranged = length != C.LENGTH_UNSET;
        boolean stored = false;
        // Take the cache lock BEFORE any request goes out. A lock held by the player
        // means the player is downloading this very segment itself, and finding that
        // out after the response has started would throw away a whole segment of
        // bandwidth for nothing -- which is exactly the opposite of what a side
        // channel is supposed to do.
        Span span;
        try {
            span = openSpan(key, position);
        } catch (IOException e) {
            run.failures.merge(key, 1, Integer::sum);
            run.failed.incrementAndGet();
            if (failLogs.getAndIncrement() < MAX_FAIL_LOGS)
                Logger.t(TAG).d("fetch failed " + key + ": " + e);
            run.queue.settle(slot, run.failures.getOrDefault(key, 0) >= MAX_FAILURES);
            return;
        }
        if (span == null) {
            skipLog("locked or cached, " + key);
            run.queue.settle(slot, true);
            return;
        }
        String range = ranged ? "bytes=" + position + "-" + (position + length - 1) : null;
        try (Response response = call(run, uri, range)) {
            if (!response.isSuccessful()) throw new IOException("http " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("empty body");
            long written = span.write(run, body.byteStream());
            if (ranged && written != length) throw new IOException("short range " + written + "/" + length);
            span.commit();
            publish(run, key, position, written);
            run.done.incrementAndGet();
            run.consecutiveFailures.set(0);
            stored = true;
        } catch (Throwable e) {
            if (run.cancelled) {
                span.discard();
                return;
            }
            span.discard();
            int failures = run.failures.merge(key, 1, Integer::sum);
            run.failed.incrementAndGet();
            // 2026-10-24: 连续失败到顶就收手。预取器与播放器共用缓存、预取时还占着
            // span 锁，取不到分片时不只是自己失败 —— 它把播放器想要的那一段也堵住，
            // 缓冲耗尽 → 画面冻住。宁可不预取，也不能让播放变差。
            if (run.consecutiveFailures.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES) {
                Logger.t(TAG).d("giving up prefetch after " + MAX_CONSECUTIVE_FAILURES
                        + " consecutive segment failures: " + e);
                run.cancel("consecutive segment failures");
                return;
            }
            if (failLogs.getAndIncrement() < MAX_FAIL_LOGS)
                Logger.t(TAG).d("fetch failed " + key + " #" + failures + ": " + e);
            if (failures >= MAX_FAILURES) {
                stored = true;
                skipLog("gave up on " + key + " after " + failures);
            }
        } finally {
            // A failed key is released so the scheduler may try it once more; the
            // failure counter is what stops it from being retried forever.
            run.queue.settle(slot, stored);
        }
    }

    private void skipLog(String message) {
        if (skipLogs.getAndIncrement() < MAX_SKIP_LOGS) Logger.t(TAG).d("skip " + message);
    }

    /**
     * Acquires the cache span this fetch is going to fill.
     *
     * @return null when the player already holds the lock or already has the data;
     *     either way this fetch has nothing to do.
     */
    private Span openSpan(String key, long position) throws IOException {
        Cache cache;
        try {
            cache = ExoMediaSourceFactory.getCache();
        } catch (Throwable e) {
            throw new IOException("cache unavailable " + e);
        }
        CacheSpan span;
        File file;
        try {
            span = cache.startReadWriteNonBlocking(key, position, C.LENGTH_UNSET);
            if (span == null) return null;
            if (span.isCached) return null;
            file = cache.startFile(key, position, C.LENGTH_UNSET);
        } catch (Throwable e) {
            throw new IOException("cache open failed " + e);
        }
        return new Span(cache, span, file, memory(cache));
    }

    private static MemoryCache memory(Cache cache) {
        return cache instanceof TwoTierCache twoTier ? twoTier.getMemory() : null;
    }

    /**
     * One reserved cache span, from lock to commit.
     *
     * <p>The lock is held across the download, so the player asking for the same
     * segment finds it locked, bypasses the cache and downloads it itself -- it is
     * never blocked, and never sees a partial span.
     */
    private final class Span {

        private final Cache cache;
        private final CacheSpan span;
        private final File file;
        private final MemoryCache memory;

        Span(Cache cache, CacheSpan span, File file, MemoryCache memory) {
            this.cache = cache;
            this.span = span;
            this.file = file;
            this.memory = memory;
        }

        long write(Run run, InputStream input) throws IOException {
            long written = 0;
            long at = span.position;
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), IO_BUFFER)) {
                byte[] buffer = new byte[IO_BUFFER];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (run.cancelled || Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
                    out.write(buffer, 0, read);
                    if (memory != null) memory.put(span.key, at, buffer, 0, read);
                    written += read;
                    at += read;
                }
            } catch (Throwable e) {
                discard();
                throw e instanceof IOException io ? io : new IOException(e);
            }
            return written;
        }

        void commit() throws IOException {
            try {
                cache.commitFile(file, file.length());
            } catch (Throwable e) {
                file.delete();
                throw new IOException("commit failed " + e);
            }
        }

        /** Drops the file and the lock without publishing a span. */
        void discard() {
            file.delete();
            try {
                cache.releaseHoleSpan(span);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Publishes the resource length, mirroring what
     * {@code CacheDataSource.setNoBytesRemainingAndMaybeStoreLength} writes after a
     * normal download. A progressive prefetch has to publish the probe's total, not
     * the range it just wrote, or the player would stop reading at that range.
     */
    private void publish(Run run, String key, long position, long written) {
        long length = run.chunks > 0 ? run.total : position + written;
        try {
            ContentMetadataMutations mutations = new ContentMetadataMutations();
            ContentMetadataMutations.setContentLength(mutations, length);
            ExoMediaSourceFactory.getCache().applyContentMetadataMutations(key, mutations);
        } catch (Throwable e) {
            Logger.t(TAG).d("metadata for " + key + " rejected " + e);
        }
    }

    // ------------------------------------------------------------ cache lookup

    /** True when [0, length) is already covered by contiguous cached spans. */
    private boolean isCached(String key, long length) {
        NavigableSet<CacheSpan> spans;
        try {
            spans = ExoMediaSourceFactory.getCache().getCachedSpans(key);
        } catch (Throwable e) {
            return false;
        }
        if (spans == null || spans.isEmpty()) return false;
        if (length == C.LENGTH_UNSET) return !spans.first().isHoleSpan();
        long covered = 0;
        for (CacheSpan span : spans) {
            if (span.isHoleSpan() || span.position != covered) break;
            covered += span.length;
        }
        return covered >= length;
    }

    private boolean covers(String key, long offset, long length) {
        NavigableSet<CacheSpan> spans;
        try {
            spans = ExoMediaSourceFactory.getCache().getCachedSpans(key);
        } catch (Throwable e) {
            return false;
        }
        if (spans == null) return false;
        for (CacheSpan span : spans)
            if (!span.isHoleSpan() && span.position <= offset && span.position + span.length >= offset + length)
                return true;
        return false;
    }

    // ---------------------------------------------------------------- plumbing

    private Response call(Run run, Uri uri, @Nullable String range) throws IOException {
        Request.Builder builder = new Request.Builder().url(uri.toString());
        run.headers.forEach(builder::header);
        if (range != null) builder.header("Range", range);
        return OkHttp.player().newCall(builder.build()).execute();
    }

    private static byte[] readAtMost(Response response, int limit) throws IOException {
        ResponseBody responseBody = response.body();
        if (responseBody == null) return new byte[0];
        try (InputStream input = responseBody.byteStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[IO_BUFFER];
            int remaining = limit;
            while (remaining > 0) {
                int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read == -1) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
            return out.toByteArray();
        }
    }

    private static boolean startsWith(byte[] data, String prefix) {
        byte[] expected = prefix.getBytes(StandardCharsets.UTF_8);
        if (data.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (data[i] != expected[i]) return false;
        return true;
    }

    private static long parseTotal(@Nullable String contentRange) {
        if (contentRange == null) return -1;
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= contentRange.length()) return -1;
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    // -------------------------------------------------------------- background

    private static final class ActivityLifecycle implements Application.ActivityLifecycleCallbacks {

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            resumed++;
            backgroundAt = 0;
        }

        /**
         * Foreground is tracked as "no activity of ours is resumed", not as
         * {@code onActivityStopped}.
         *
         * <p>Measured on ct2000: pressing HOME pauses VideoActivity but never stops
         * it, because this box's HOME resolves to a translucent system
         * ResolverActivity that sits on top of our task. A watchdog keyed on
         * {@code onActivityStopped} therefore never fired there, and the 5-minute
         * background deadline was unreachable. Resume/pause is the transition that
         * actually happens in both cases, and counting resumes handles the overlay
         * case as well.
         */
        @Override
        public void onActivityPaused(@NonNull Activity activity) {
            if (resumed > 0) resumed--;
            if (resumed <= 0) backgroundAt = SystemClock.elapsedRealtime();
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
        }
    }

    // ------------------------------------------------------------------- state

    /** One media playlist plus its latest snapshot; refresh() swaps the snapshot. */
    private static final class Playlist {

        final Uri uri;
        volatile HlsMediaPlaylist media;

        Playlist(Uri uri, HlsMediaPlaylist media) {
            this.uri = uri;
            this.media = media;
        }
    }

    /** Dispatch bookkeeping for one session: what is in flight, what is settled. */
    private static final class Queue {

        private final Set<String> pending = new HashSet<>();
        private final Set<String> done = new HashSet<>();

        /** True when the key must not be dispatched again this tick. */
        boolean holds(String key) {
            return pending.contains(key) || done.contains(key);
        }

        void hold(String key) {
            pending.add(key);
        }

        void settle(String key, boolean success) {
            pending.remove(key);
            if (success) done.add(key);
        }
    }

    private static final class Run {

        final Uri url;
        final Map<String, String> headers;
        final Player player;
        final long startPositionMs;
        final ThreadPoolExecutor pool;
        final Queue queue = new Queue();
        final Map<String, Integer> failures = new ConcurrentHashMap<>();
        final List<Playlist> playlists = new ArrayList<>(2);
        final AtomicInteger queued = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger hit = new AtomicInteger();
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        volatile boolean cancelled;
        volatile boolean live;
        volatile boolean playing = true;
        volatile long pausedAt;
        volatile long chunks;
        volatile long total;
        volatile String yieldState = "-";
        volatile Uri mediaUrl;
        long lastAnnounce;

        Run(Uri url, Map<String, String> headers, @Nullable Player player, long startPositionMs, ThreadPoolExecutor pool) {
            this.url = url;
            this.headers = headers;
            this.player = player;
            this.startPositionMs = startPositionMs;
            this.pool = pool;
        }

        void cancel(String reason) {
            if (cancelled) return;
            cancelled = true;
            pool.shutdownNow();
            Logger.t(TAG).d("cancel: " + reason);
        }

        /**
         * Dispatch slot for one unit of work. HLS keys by URL, progressive keys by
         * URL plus offset, and the cache key is the URL in both cases, so the two
         * must not collide in the pending set.
         */
        String idOf(String key, long position) {
            return position == 0 ? key : key + "#" + position;
        }

        /** True at most once per {@code windowMs}, so a chatty tick stays quiet. */
        boolean announce(long now, long windowMs) {
            return now - lastAnnounce >= windowMs && (lastAnnounce = now) >= 0;
        }

        String stats(AtomicInteger inFlight) {
            return "queued=" + queued + " done=" + done + " cached=" + hit + " failed=" + failed
                    + " inFlight=" + inFlight.get();
        }
    }
}