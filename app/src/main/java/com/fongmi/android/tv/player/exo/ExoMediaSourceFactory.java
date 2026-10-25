package com.fongmi.android.tv.player.exo;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.CacheSetting;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.orhanobut.logger.Logger;

import java.io.File;
import java.util.Map;

public class ExoMediaSourceFactory implements MediaSource.Factory {

    private static final String TAG = "ExoCache";
    private static final int CACHE_SPACE_PERCENT = 80;

    private static StandaloneDatabaseProvider databaseProvider;
    private static Cache cache;

    private final DefaultMediaSourceFactory defaultMediaSourceFactory;
    private HttpDataSource.Factory httpDataSourceFactory;
    private DataSource.Factory dataSourceFactory;
    private ExtractorsFactory extractorsFactory;

    public ExoMediaSourceFactory() {
        defaultMediaSourceFactory = new DefaultMediaSourceFactory(getDataSourceFactory(), getExtractorsFactory());
    }

    // 2026-10-09: upstream returned a MediaSourceFactorySupplier -- a fork-only
    // indirection so DefaultPreloadManager.Builder could be handed a factory before the
    // cache and DataSource were configured, and get a fresh factory per preload.
    // Official media3-exoplayer 1.8.0 only has Builder.setMediaSourceFactory(...), so the
    // supplier is inlined into a lazy MediaSource.Factory here. Lazy creation is
    // preserved on purpose: constructing ExoMediaSourceFactory opens the SimpleCache
    // and a StandaloneDatabaseProvider, which is exactly what the supplier deferred.
    static MediaSource.Factory supplier() {
        return new MediaSource.Factory() {

            private ExoMediaSourceFactory delegate;

            private ExoMediaSourceFactory delegate() {
                if (delegate == null) delegate = new ExoMediaSourceFactory();
                return delegate;
            }

            @NonNull
            @Override
            public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
                return delegate().createMediaSource(mediaItem);
            }

            @NonNull
            @Override
            public int[] getSupportedTypes() {
                return delegate().getSupportedTypes();
            }

            @NonNull
            @Override
            public MediaSource.Factory setDrmSessionManagerProvider(@NonNull DrmSessionManagerProvider provider) {
                delegate().setDrmSessionManagerProvider(provider);
                return this;
            }

            @NonNull
            @Override
            public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy policy) {
                delegate().setLoadErrorHandlingPolicy(policy);
                return this;
            }
        };
    }

    static DataSource.Factory createUpstreamDataSourceFactory(Map<String, String> headers) {
        HttpDataSource.Factory factory = new OkHttpDataSource.Factory(OkHttp.player());
        factory.setDefaultRequestProperties(headers);
        return new DefaultDataSource.Factory(App.get(), factory);
    }

    static synchronized Cache getCache() {
        if (cache != null) return cache;
        int sizeMb = CacheSetting.getExoSizeMb();
        // Gear 0 keeps the pre-existing shape exactly: a disk-only cache object that is
        // never written to. This is the no-regression path and is deliberately NOT the
        // same as "no cache at all", so existing behaviour is bit-for-bit preserved.
        File dir = sizeMb > 0 ? Path.exoExternalCache() : Path.exoCache();
        SimpleCache disk = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(getMaxCacheSize(dir, sizeMb)), getDatabaseProvider());
        if (sizeMb <= 0) {
            Logger.t(TAG).d("exo cache gear=0 (no write sink) dir=" + dir.getAbsolutePath());
            return cache = disk;
        }
        Logger.t(TAG).d("exo cache gear=" + sizeMb + "MB two-tier L1=" + MemoryCache.DEFAULT_MAX_BYTES / (1024 * 1024) + "MB dir=" + dir.getAbsolutePath());
        return cache = new TwoTierCache(disk, new MemoryCache());
    }

    private static StandaloneDatabaseProvider getDatabaseProvider() {
        if (databaseProvider == null) databaseProvider = new StandaloneDatabaseProvider(App.get());
        return databaseProvider;
    }

    /**
     * Disk budget = the EXO gear, further clamped so the cache can never fill more than
     * 80% of the volume. The old code took this from {@code PreloadSetting}, which is a
     * preload-buffer knob; caching is now driven by {@link CacheSetting} instead.
     */
    private static long getMaxCacheSize(File dir, int sizeMb) {
        if (sizeMb <= 0) return CACHE_SPACE_PERCENT * 1024L * 1024L;
        long usedBytes = Path.size(dir);
        long availableBytes = Math.max(0, Path.available(dir));
        long storageBudget = (usedBytes + availableBytes) * CACHE_SPACE_PERCENT / 100;
        return Math.min(sizeMb * 1024L * 1024L, storageBudget);
    }

    @NonNull
    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(@NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
        return this;
    }

    @NonNull
    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
        return this;
    }

    @NonNull
    @Override
    public @C.ContentType int[] getSupportedTypes() {
        return defaultMediaSourceFactory.getSupportedTypes();
    }

    @NonNull
    @Override
    public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
        getHttpDataSourceFactory().setDefaultRequestProperties(ExoUtil.extractHeaders(mediaItem));
        return defaultMediaSourceFactory.createMediaSource(mediaItem);
    }

    private ExtractorsFactory getExtractorsFactory() {
        if (extractorsFactory == null) extractorsFactory = new DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 10);
        return extractorsFactory;
    }

    private DataSource.Factory getDataSourceFactory() {
        if (dataSourceFactory == null) dataSourceFactory = () -> getCacheDataSource(new DefaultDataSource.Factory(App.get(), observeUpstream(getHttpDataSourceFactory()))).createDataSource();
        return dataSourceFactory;
    }

    /**
     * Wraps the player's upstream HTTP source with a passive observer.
     *
     * <p>A transfer seen here is by definition a cache miss served from the network:
     * {@code CacheDataSource} only reaches the upstream source when the span is not
     * available, and a cache read is reported to the cache read source instead. That
     * is the signal the prefetcher's yield rule needs, and it cannot be derived from
     * player state alone. The listener only records a timestamp -- it does not
     * consume, retry or alter the transfer.
     */
    private static DataSource.Factory observeUpstream(HttpDataSource.Factory factory) {
        return () -> {
            HttpDataSource source = factory.createDataSource();
            source.addTransferListener(CachePrefetcher.PLAYER_TRANSFER);
            return source;
        };
    }

    private CacheDataSource.Factory getCacheDataSource(DataSource.Factory upstreamFactory) {
        Cache cache = getCache();
        CacheDataSource.Factory factory = new CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        if (cache instanceof TwoTierCache twoTier) {
            return factory
                    .setCacheReadDataSourceFactory(twoTier.readDataSourceFactory())
                    .setCacheWriteDataSinkFactory(twoTier.writeDataSinkFactory());
        }
        // Gear 0: no write sink, exactly as before this change.
        return factory.setCacheWriteDataSinkFactory(null);
    }

    private HttpDataSource.Factory getHttpDataSourceFactory() {
        if (httpDataSourceFactory == null) httpDataSourceFactory = new OkHttpDataSource.Factory(OkHttp.player());
        return httpDataSourceFactory;
    }
}
