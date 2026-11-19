package com.fongmi.android.tv.player.exo;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSink;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadata;
import androidx.media3.datasource.cache.ContentMetadataMutations;

import com.orhanobut.logger.Logger;

import java.io.IOException;
import java.util.NavigableSet;
import java.util.Set;

import android.net.Uri;

/**
 * Two-tier ExoPlayer cache: memory L1 in front of the disk L2 {@link SimpleCache}.
 *
 * <p><b>Why this is not a {@code Cache} subclass.</b> The task brief assumed the
 * ExoPlayer 2.x {@code Cache} shape with {@code openWrite}/{@code openRead} and a
 * memory sink. media3 1.8.0 removed that: {@code Cache} is now span/File based
 * ({@code startFile}/{@code commitFile}/{@code startReadWrite}), and
 * {@code CacheDataSource.openNextSource} serves every cached read by doing
 * {@code Uri.fromFile(nextSpan.file)} and handing it to {@code cacheReadDataSourceFactory}
 * (default {@code FileDataSource}). A byte[]-backed span therefore cannot be expressed
 * through this interface at all -- there is no hook where a memory tier could be read
 * from, and faking one with a File subclass would break the moment media3 seeks.
 *
 * <p>So the L1 tier is attached at the only two places media3 does allow it, both on
 * {@code CacheDataSource.Factory}:
 * <ul>
 *   <li>{@link #readDataSourceFactory()} -- a {@code DataSource} that answers from L1 and
 *       otherwise delegates to the real {@code FileDataSource} over the disk span.</li>
 *   <li>{@link #writeDataSinkFactory()} -- a {@code DataSink} that mirrors every
 *       downloaded byte into L1 before handing it to the real {@code CacheDataSink}.</li>
 * </ul>
 *
 * <p>This instance is still a {@code Cache} (it delegates to the disk
 * {@code SimpleCache} for every span operation) so that
 * {@code ExoDiskPreload}/{@code DiskPreloadManager}, which take a {@code Cache}
 * directly, keep working untouched. The disk tier therefore remains the single source of
 * truth and stays authoritative for {@code isCached}/{@code getCachedBytes}: L1 only
 * ever avoids disk IO for ranges the disk has already confirmed, so a stale, evicted, or
 * missing L1 entry costs a miss and nothing else.
 *
 * <p>Read order is therefore L1 -> L2 -> network, as required, and a fresh install that
 * never fills L1 behaves exactly like the plain disk cache.
 *
 * <p>ASCII-only comments on purpose (javac defaults to GBK on Chinese Windows and
 * mangles UTF-8 CJK bytes into illegal-character errors).
 */
@UnstableApi
public class TwoTierCache implements Cache {

    private static final String TAG = "TwoTierCache";

    private final Cache disk;
    private final MemoryCache memory;

    public TwoTierCache(Cache disk, MemoryCache memory) {
        this.disk = disk;
        this.memory = memory;
    }

    public MemoryCache getMemory() {
        return memory;
    }

    public Cache getDisk() {
        return disk;
    }

    /**
     * Read side: serve the requested range from L1 when it is fully resident, else
     * delegate verbatim to the disk span {@code FileDataSource}.
     *
     * <p>The absolute resource position is {@code uriPositionOffset + position}: for a
     * cached span media3 sets {@code uriPositionOffset} to the span's start position and
     * {@code position} to the offset within that span's file (see
     * {@code CacheDataSource.openNextSource}), and {@code key} is carried over from the
     * request spec, so L1 can be addressed by the same cache key the sink wrote under.
     */
    public DataSource.Factory readDataSourceFactory() {
        return () -> new MemoryFirstDataSource(memory, new FileDataSource());
    }

    /**
     * Write side: mirror into L1, then forward to the real disk {@code CacheDataSink}.
     *
     * <p>The disk sink is built here rather than injected so it always binds to the same
     * {@link Cache} this facade wraps, and so a fresh sink is created per resource (a
     * {@code CacheDataSink} binds to its concrete span at {@code open(DataSpec)} time and
     * cannot be reused across resources).
     */
    public DataSink.Factory writeDataSinkFactory() {
        return () -> new MirrorDataSink(memory, disk);
    }

    // ---------------------------------------------------------------- Cache (disk) --

    @Override
    public long getUid() {
        return disk.getUid();
    }

    @Override
    public void release() {
        disk.release();
    }

    @Override
    public NavigableSet<CacheSpan> addListener(String key, Listener listener) {
        return disk.addListener(key, listener);
    }

    @Override
    public void removeListener(String key, Listener listener) {
        disk.removeListener(key, listener);
    }

    @Override
    public NavigableSet<CacheSpan> getCachedSpans(String key) {
        return disk.getCachedSpans(key);
    }

    @Override
    public Set<String> getKeys() {
        return disk.getKeys();
    }

    @Override
    public long getCacheSpace() {
        return disk.getCacheSpace();
    }

    @Override
    public CacheSpan startReadWrite(String key, long position, long length) throws InterruptedException, CacheException {
        return disk.startReadWrite(key, position, length);
    }

    @Override
    public CacheSpan startReadWriteNonBlocking(String key, long position, long length) throws CacheException {
        return disk.startReadWriteNonBlocking(key, position, length);
    }

    @Override
    public java.io.File startFile(String key, long position, long length) throws CacheException {
        return disk.startFile(key, position, length);
    }

    @Override
    public void commitFile(java.io.File file, long length) throws CacheException {
        disk.commitFile(file, length);
    }

    @Override
    public void releaseHoleSpan(CacheSpan span) {
        disk.releaseHoleSpan(span);
    }

    @Override
    public void removeResource(String key) {
        memory.remove(key);
        disk.removeResource(key);
    }

    @Override
    public void removeSpan(CacheSpan span) {
        disk.removeSpan(span);
    }

    @Override
    public boolean isCached(String key, long position, long length) {
        return disk.isCached(key, position, length);
    }

    @Override
    public long getCachedLength(String key, long position, long length) {
        return disk.getCachedLength(key, position, length);
    }

    @Override
    public long getCachedBytes(String key, long position, long length) {
        return disk.getCachedBytes(key, position, length);
    }

    @Override
    public void applyContentMetadataMutations(String key, ContentMetadataMutations mutations) throws CacheException {
        disk.applyContentMetadataMutations(key, mutations);
    }

    @Override
    public ContentMetadata getContentMetadata(String key) {
        return disk.getContentMetadata(key);
    }

    // ------------------------------------------------------------------ L1 data source --

    /**
     * L1-first reader over a cached span. Falls back to the delegate the instant the
     * request is not fully resident, so the only behaviour change vs. a bare
     * {@code FileDataSource} is where the bytes physically come from.
     */
    private static final class MemoryFirstDataSource implements DataSource {

        private final MemoryCache memory;
        private final DataSource delegate;

        private DataSpec spec;
        private byte[] buffer;
        private int bufferOffset;
        private boolean servedFromMemory;

        MemoryFirstDataSource(MemoryCache memory, DataSource delegate) {
            this.memory = memory;
            this.delegate = delegate;
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            this.spec = dataSpec;
            this.servedFromMemory = false;
            long position = dataSpec.uriPositionOffset + dataSpec.position;
            long length = dataSpec.length;
            if (dataSpec.key != null && length != C.LENGTH_UNSET && length <= Integer.MAX_VALUE) {
                buffer = new byte[(int) length];
                if (memory.copyOut(dataSpec.key, position, buffer, 0, (int) length)) {
                    servedFromMemory = true;
                    bufferOffset = 0;
                    log("L1 hit  key=" + dataSpec.key + " pos=" + position + " len=" + length + " | " + memory.stats());
                    return length;
                }
            }
            buffer = null;
            return delegate.open(dataSpec);
        }

        @Override
        public int read(byte[] dest, int offset, int length) throws IOException {
            if (!servedFromMemory) return delegate.read(dest, offset, length);
            if (bufferOffset >= buffer.length) return C.RESULT_END_OF_INPUT;
            int count = Math.min(length, buffer.length - bufferOffset);
            System.arraycopy(buffer, bufferOffset, dest, offset, count);
            bufferOffset += count;
            return count;
        }

        @Override
        public Uri getUri() {
            return spec != null ? spec.uri : null;
        }

        @Override
        public void close() throws IOException {
            buffer = null;
            if (!servedFromMemory) delegate.close();
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            // The delegate owns the real span file handle; only it has something to report.
            delegate.addTransferListener(transferListener);
        }
    }

    // ------------------------------------------------------------------ L1 data sink --

    /**
     * Mirrors bytes into L1 on their way to the disk {@code CacheDataSink}.
     *
     * <p>The delegate is created lazily inside {@link #open} because
     * {@code CacheDataSink} binds to the concrete span at {@code open(DataSpec)} time and
     * cannot be reused across resources. Writes go to L1 first and then to disk; if the
     * disk write throws, the exception still propagates so media3 tears the span down as
     * usual -- L1 holding a few extra bytes for a span that disk then drops is harmless,
     * because L1 is never consulted unless disk reports the range as cached.
     */
    private static final class MirrorDataSink implements DataSink {

        private final MemoryCache memory;
        private final Cache disk;
        private DataSink delegate;
        private String key;
        private long position;

        MirrorDataSink(MemoryCache memory, Cache disk) {
            this.memory = memory;
            this.disk = disk;
        }

        @Override
        public void open(DataSpec dataSpec) throws IOException {
            this.key = dataSpec.key;
            this.position = dataSpec.position;
            // Match CacheDataSink: an unknown-length spec flagged DONT_CACHE is dropped
            // rather than written, so L1 must not mirror it either.
            if (dataSpec.length == C.LENGTH_UNSET && dataSpec.isFlagSet(DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN)) {
                this.key = null;
                this.delegate = null;
                return;
            }
            this.delegate = new CacheDataSink(disk, CacheDataSink.DEFAULT_FRAGMENT_SIZE);
            this.delegate.open(dataSpec);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            if (key != null) memory.put(key, position, buffer, offset, length);
            if (delegate != null) {
                delegate.write(buffer, offset, length);
                position += length;
            }
        }

        @Override
        public void close() throws IOException {
            if (delegate != null) delegate.close();
            delegate = null;
            key = null;
        }
    }

    static void log(String message) {
        Logger.t(TAG).d(message);
    }
}