package com.fongmi.android.tv.player.exo;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.source.MediaSource;

import com.fongmi.android.tv.player.effect.PlayerEffect;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;

public class ExoPlayerEngine implements PlayerEngine, AnalyticsListener {

    private static final long TICK_MS = 2000L;

    private final ExoErrorMessageProvider provider;
    private final ExoPlayerSession session;
    private final ExoPlayerEffect effect;
    private final ExoDiskPreload preload;
    private final CachePrefetcher prefetch;
    private final ExoPlayer player;
    private final Handler handler;
    private final Runnable sampler;
    private PlaySpec spec;

    public ExoPlayerEngine(int decode, Player.Listener listener) {
        this.effect = new ExoPlayerEffect();
        this.preload = new ExoDiskPreload();
        this.prefetch = new CachePrefetcher();
        this.provider = new ExoErrorMessageProvider();
        this.session = new ExoPlayerSession(decode, listener, effect.getAudioProcessors());
        this.player = this.session.player();
        this.player.addAnalyticsListener(this);
        this.effect.setPlayer(player);
        // Runs on the player's thread, so tick() may read player state directly.
        this.handler = new Handler(Looper.getMainLooper());
        this.sampler = new Runnable() {
            @Override
            public void run() {
                CachePrefetcher.tick(player);
                handler.postDelayed(this, TICK_MS);
            }
        };
        this.handler.postDelayed(sampler, TICK_MS);
    }

    @Override
    public void onAudioTrackInitialized(@NonNull EventTime eventTime, @NonNull AudioSink.AudioTrackConfig audioTrackConfig) {
        effect.applyAudioEffect();
    }

    @Override
    public void onAudioTrackReleased(@NonNull EventTime eventTime, @NonNull AudioSink.AudioTrackConfig audioTrackConfig) {
        effect.applyAudioEffect();
    }

    @Override
    public void onTracksChanged(@NonNull EventTime eventTime, @NonNull Tracks tracks) {
        effect.applyVideoEffect();
    }

    // Counters only. They read player state and change nothing the player does,
    // but they give the prefetch work a measurable before/after (first frame,
    // rebuffer count) in logcat on a release build, where media3's own
    // EventLogger is not installed.
    @Override
    public void onPlaybackStateChanged(@NonNull EventTime eventTime, int state) {
        CachePrefetcher.markState(state);
    }

    @Override
    public void onIsPlayingChanged(@NonNull EventTime eventTime, boolean isPlaying) {
        CachePrefetcher.markPlaying(isPlaying);
    }

    @Override
    public void onRenderedFirstFrame(@NonNull EventTime eventTime, @NonNull Object outputStream, long presentationTimeUs) {
        CachePrefetcher.markFirstFrame();
    }

    @Override
    public void onAudioUnderrun(@NonNull EventTime eventTime, int playbackPositionUs, long elapsedRealtimeMs, long durationUs) {
        CachePrefetcher.markAudioUnderrun();
    }

    @Override
    public Type getType() {
        return Type.EXO;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public int getAudioChannelCount() {
        Format format = player.getAudioFormat();
        return format == null ? Format.NO_VALUE : format.channelCount;
    }

    @Override
    public PlayerEffect getEffect() {
        return effect;
    }

    @Override
    public void release() {
        player.removeAnalyticsListener(this);
        handler.removeCallbacks(sampler);
        CachePrefetcher.resetMetrics();
        prefetch.release();
        preload.release();
        effect.release();
        session.release();
    }

    @Override
    public void setDecode(int decode) {
        session.setDecode(decode);
    }

    @Override
    public void start(PlaySpec spec, long startPositionMs) {
        this.spec = spec;
        startInternal(startPositionMs);
    }

    @Override
    public void preload(PlaySpec spec, long startPositionMs) {
        session.preload(MediaItemFactory.from(spec), startPositionMs);
    }

    @Override
    public void clearPreload() {
        session.clearPreload();
    }

    @Override
    public void stop() {
        CachePrefetcher.resetMetrics();
        prefetch.stop();
        preload.stop();
        player.stop();
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return provider.get(e);
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> seekToDefaultPosition();
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> ErrorAction.DECODE;
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> retryFormat(e.errorCode);
            default -> ErrorAction.FATAL;
        };
    }

    private void startInternal(long position) {
        MediaItem item = MediaItemFactory.from(spec);
        MediaSource source = session.usePreloadedMediaSource(item);
        effect.clearAudioEffect();
        if (source == null) player.setMediaItem(item, position);
        else player.setMediaSource(source, position);
        // Armed before prepare() so the look-ahead fetches run alongside the
        // player's own manifest and first-segment loads rather than after them.
        CachePrefetcher.markPrepare();
        prefetch.start(item, spec.getUri(), player, position);
        preload.start(player, item);
        prepareAndPlay();
    }

    private void prepareAndPlay() {
        player.prepare();
        player.play();
    }

    private ErrorAction seekToDefaultPosition() {
        player.seekToDefaultPosition();
        player.prepare();
        return ErrorAction.RECOVERED;
    }

    private ErrorAction retryFormat(int errorCode) {
        spec.setFormat(ExoUtil.getMimeType(errorCode));
        startInternal(player.getCurrentPosition());
        return ErrorAction.RECOVERED;
    }
}
