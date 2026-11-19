package com.fongmi.android.tv.player.exo;

import com.orhanobut.logger.Logger;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L1 in-memory tier for the ExoPlayer cache: a bounded byte pool keyed by cache key.
 *
 * <p>media3's {@code Cache} contract is span/File based -- {@code CacheDataSource} only
 * ever reads cached bytes back through {@code Uri.fromFile(span.file)} and a
 * {@code FileDataSource}. There is no in-memory read hook in the {@code Cache}
 * interface itself, so the memory tier is NOT implemented as a {@code Cache}; it is
 * hung off the two extension points {@code CacheDataSource} does expose:
 * {@code setCacheReadDataSourceFactory} and {@code setCacheWriteDataSinkFactory}.
 * See {@link TwoTierCache}.
 *
 * <p>Semantics: writes are mirrored here as well as to disk, reads consult this pool
 * first and fall through to the disk span. Nothing here is authoritative -- disk stays
 * the source of truth, so a stale or evicted memory entry can only cost a miss, never
 * correctness. That asymmetry is what makes it safe to run this pool without any of the
 * bookkeeping (hole spans, locks, content metadata) that {@code Cache} would require.
 *
 * <p><b>Threading:</b> every public method is {@code synchronized}. The playback thread
 * writes through {@link #put} while the render/preload threads may read through
 * {@link #copyOut}, and {@link LinkedHashMap} is not safe for concurrent structural
 * modification. Contiguity is enforced on write (see {@link #put}) so {@link #copyOut}
 * can never stitch two discontiguous runs into one contiguous answer.
 *
 * <p>ASCII-only comments on purpose (javac defaults to GBK on Chinese Windows and
 * mangles UTF-8 CJK bytes into illegal-character errors).
 */
public class MemoryCache {

    private static final String TAG = "MemoryCache";

    /** Fixed L1 budget. Overflow is LRU-evicted; the disk tier is the backstop. */
    public static final long DEFAULT_MAX_BYTES = 128L * 1024 * 1024;

    /**
     * One contiguous run of bytes for a key.
     *
     * <p>{@code data[0]} is the byte at absolute resource position {@link #start}, and
     * only {@code data[0..length)} is valid.
     */
    private static final class Entry {

        final long start;
        byte[] data;
        int length;

        Entry(long start, byte[] data, int length) {
            this.start = start;
            this.data = data;
            this.length = length;
        }
    }

    private final long maxBytes;
    private final LinkedHashMap<String, Entry> entries;
    private long usedBytes;
    private long hits;
    private long misses;
    private long lastLoggedBytes;

    /** Log L1 occupancy every 8MB written, so a session leaves a bounded trail in logcat. */
    private static final long LOG_STEP_BYTES = 8L * 1024 * 1024;

    public MemoryCache() {
        this(DEFAULT_MAX_BYTES);
    }

    public MemoryCache(long maxBytes) {
        this.maxBytes = maxBytes;
        // accessOrder=true turns LinkedHashMap into the LRU list the eviction relies on.
        this.entries = new LinkedHashMap<>(16, 0.75f, true);
    }

    /**
     * Mirrors a contiguous run of freshly downloaded bytes into L1.
     *
     * <p>Only a write that continues exactly where the stored run ends is appended. Any
     * other position (a gap, a re-read from an earlier seek point, a restart) restarts the
     * entry at that position rather than trying to bridge the hole -- a run that
     * pretended to be contiguous would let {@link #copyOut} answer a range from bytes that
     * were never actually downloaded together.
     *
     * <p>Storage failures are swallowed on purpose. L1 is an accelerator; an OOM here
     * must not take playback down, and the disk tier already holds the same bytes.
     */
    public synchronized void put(String key, long position, byte[] buffer, int offset, int length) {
        if (key == null || length <= 0 || maxBytes <= 0) return;
        if (length > maxBytes) return; // A single run larger than the pool is never useful here.
        try {
            Entry entry = entries.get(key);
            if (entry == null || position != entry.start + entry.length) {
                replace(key, position, buffer, offset, length);
            } else {
                append(entry, buffer, offset, length);
            }
            evict();
            if (usedBytes - lastLoggedBytes >= LOG_STEP_BYTES) {
                lastLoggedBytes = usedBytes;
                Logger.t(TAG).d("put " + stats());
            }
        } catch (OutOfMemoryError e) {
            Logger.t(TAG).d("put dropped, pool exhausted: " + e);
        }
    }

    private void replace(String key, long position, byte[] buffer, int offset, int length) {
        Entry previous = entries.put(key, new Entry(position, copyOf(buffer, offset, length), length));
        // Accounting tracks logical length, not array capacity: growth is geometric so
        // capacity deliberately overshoots, and counting it would evict too early.
        if (previous != null) usedBytes -= previous.length;
        usedBytes += length;
    }

    private void append(Entry entry, byte[] buffer, int offset, int length) {
        int required = entry.length + length;
        if (required > entry.data.length) {
            int grown = Math.max(required, entry.length + (entry.length >> 1) + 1024);
            byte[] expanded = new byte[grown];
            System.arraycopy(entry.data, 0, expanded, 0, entry.length);
            entry.data = expanded;
        }
        System.arraycopy(buffer, offset, entry.data, entry.length, length);
        entry.length = required;
        usedBytes += length;
    }

    /**
     * Copies a cached range out of L1 into {@code dest}.
     *
     * @return true only when the whole requested range is resident. A partial hit returns
     *     false rather than a short copy, because the caller (a {@code DataSource} read)
     *     must either serve the full length or fall through to the disk tier.
     */
    public synchronized boolean copyOut(String key, long position, byte[] dest, int destOffset, int length) {
        if (key == null || length <= 0) return false;
        Entry entry = entries.get(key);
        if (entry == null || position < entry.start) {
            misses++;
            return false;
        }
        long offset = position - entry.start;
        if (offset + length > entry.length) {
            misses++;
            return false;
        }
        System.arraycopy(entry.data, (int) offset, dest, destOffset, length);
        hits++;
        return true;
    }

    private void evict() {
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (usedBytes > maxBytes && it.hasNext()) {
            usedBytes -= it.next().getValue().length;
            it.remove();
        }
    }

    private static byte[] copyOf(byte[] buffer, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(buffer, offset, out, 0, length);
        return out;
    }

    public synchronized void remove(String key) {
        Entry removed = entries.remove(key);
        if (removed != null) usedBytes -= removed.length;
    }

    public synchronized void clear() {
        entries.clear();
        usedBytes = 0;
    }

    public synchronized long getUsedBytes() {
        return usedBytes;
    }

    public synchronized long getMaxBytes() {
        return maxBytes;
    }

    /** One-line hit/miss summary for logcat verification. */
    public synchronized String stats() {
        return "L1 " + FileUtilFormat.mb(usedBytes) + "/" + FileUtilFormat.mb(maxBytes)
                + " keys=" + entries.size() + " hits=" + hits + " misses=" + misses;
    }

    /** Local MB formatter so this class stays independent of the UI layer. */
    static final class FileUtilFormat {

        private FileUtilFormat() {
        }

        static String mb(long bytes) {
            return (bytes / (1024 * 1024)) + "MB";
        }
    }
}