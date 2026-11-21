package androidx.media3.exoplayer.source.preload;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PriorityTaskManager;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;

/**
 * [LOCAL COMPILE STUB]
 *
 * <p>WHY: not part of any published Media3 release; it comes from upstream FongMi's
 * Media3 fork. Used by {@code ExoDiskPreload} to warm the disk cache for the next
 * episode while the current one plays.
 *
 * <p>WHAT: {@link #start} is intentionally a no-op, so preloading simply does not
 * happen. Everything upstream checks first is preserved, so there is no behaviour
 * change beyond losing the speculative buffering:
 * <pre>
 *   if (!PreloadSetting.isEnabled()) return;   // caller-side guard, kept
 *   manager.start(...)                        // no-op
 * </pre>
 * Playback itself is unaffected -- this is pure prefetch, and the app still plays the
 * selected episode normally. Flipping "preload" off in settings is equivalent.
 *
 * <p>ASCII-only comments on purpose (javac defaults to GBK on Chinese Windows).
 */
public class DiskPreloadManager {

    public static final class Builder {

        public Builder(Cache cache, DataSource.Factory upstreamDataSourceFactory, Object renderersFactory) {
        }

        public Builder setPriorityTaskManager(PriorityTaskManager priorityTaskManager) {
            return this;
        }

        public DiskPreloadManager build() {
            return new DiskPreloadManager();
        }
    }

    /** Options for {@link #start}. */
    public static final class Options {

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {

            public Builder setDurationMs(long durationMs) {
                return this;
            }

            public Builder setMaxThreads(int maxThreads) {
                return this;
            }

            public Options build() {
                return new Options();
            }
        }
    }

    /** No-op: there is no disk preload in this build. */
    public void start(ExoPlayer player, MediaItem mediaItem, Options options) {
    }

    public void release() {
    }
}