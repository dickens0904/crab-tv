package com.fongmi.android.tv.player.ijk;

import android.net.Uri;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.Size;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.effect.audio.DownmixAudioProcessor;
import com.fongmi.android.tv.player.engine.EngineCommands;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.setting.AudioSetting;
import com.fongmi.android.tv.setting.CacheSetting;
import com.orhanobut.logger.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import tv.danmaku.ijk.media.player.IjkMediaMeta;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.MediaInfo;

/**
 * Third playback engine: IJK (bilibili ijkplayer 0.8.8).
 *
 * <p>Positioning differs from the other two:
 * <ul>
 *   <li>ExoPlayer -- default and floor; best hardware decode; DRM / smb only go here</li>
 *   <li>MPV -- ships its own video/audio filter stack</li>
 *   <li>IJK -- ships an ffmpeg filter chain, so downmix runs through {@code pan} rather
 *       than ExoPlayer's {@code DownmixAudioProcessor}; both read the same
 *       {@code DownmixMatrix} weights</li>
 * </ul>
 *
 * <p><b>Why downmix needs two prepare rounds:</b> ijk only reads the {@code af} option
 * while building its FFmpegContext, and an ffmpeg {@code pan} expression may only
 * reference input channels that actually exist. So the first prepare exists purely to
 * learn the real channel count: mono/stereo sources stop there (no filter, zero risk),
 * and only multichannel sources get rebuilt once with
 * {@link IjkDownmix#downmix(int)}. See {@link IjkDownmix}.
 *
 * <p><b>Why the adapter is separate:</b> {@link IjkPlayerAdapter} is a pure forwarding
 * adapter whose method set matches {@code androidx.media3.mpvplayer.MpvPlayer}
 * (generated against media3-common 1.8.0), so all three engines expose the same
 * contract to the playback framework.
 *
 * <p>ASCII-only comments on purpose (javac defaults to GBK on Chinese Windows and
 * mangles UTF-8 CJK bytes into illegal-character errors).
 */
public class IjkPlayerEngine implements PlayerEngine, Player.Listener {

    private static final String TAG = "IjkEngine";

    private static final int OPT_FORMAT = IjkMediaPlayer.OPT_CATEGORY_FORMAT;
    private static final int OPT_PLAYER = IjkMediaPlayer.OPT_CATEGORY_PLAYER;

    private static final int MAX_BUFFER_SIZE = 15728640;
    private static final int MIN_FRAMES = 25;
    private static final int TIMEOUT_US = 30000000;
    private static final int ON_INFO_VIDEO_RENDERED_START = 3;
    private static final int ON_INFO_BUFFERING_START = 701;
    private static final int ON_INFO_BUFFERING_END = 702;

    private final IjkPlayerAdapter adapter = new IjkPlayerAdapter();

    private final List<Player.Listener> listeners = new ArrayList<>();

    private IjkMediaPlayer mediaPlayer;
    private PlaySpec spec;
    private Surface surface;
    private int decode;
    private long startPositionMs;
    private float volume = 1f;
    private PlaybackParameters playbackParameters = PlaybackParameters.DEFAULT;
    private TrackSelectionParameters trackSelectionParameters = TrackSelectionParameters.DEFAULT;
    private MediaItem mediaItem;
    private boolean prepared;
    private boolean released;
    private boolean seekPending;
    private long seekPositionMs;
    /** The probe prepare used to learn the channel count; never probes twice. */
    private boolean channelProbed;

    public IjkPlayerEngine(int decode, Player.Listener listener) {
        this.decode = decode;
        initPlayer();
        if (listener != null) adapter.addListener(listener);
        adapter.addListener(this);
    }

    /**
     * The AAR being on the classpath is what makes the engine selectable; whether
     * libijkffmpeg.so can really be dlopen()ed is settled by {@code new
     * IjkMediaPlayer()} in {@link #initPlayer()}, and a throw there is caught by
     * PlayerEngineFactory, which falls back to ExoPlayer.
     */
    public static boolean isAvailable() {
        try {
            Class.forName("tv.danmaku.ijk.media.player.IjkMediaPlayer");
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void initPlayer() {
        this.mediaPlayer = new IjkMediaPlayer();
        applyOptions(null);
        this.mediaPlayer.setOption(OPT_PLAYER, "mediacodec-hevc", 1);
        this.mediaPlayer.setOption(OPT_PLAYER, "mediacodec-vt", 0);
        this.mediaPlayer.setOption(OPT_PLAYER, "mediacodec-auto-rotate", 1);
        this.mediaPlayer.setOption(OPT_PLAYER, "mediacodec-handle-resolution-change", 1);
        this.mediaPlayer.setOption(OPT_PLAYER, "max-buffer-size", MAX_BUFFER_SIZE);
        this.mediaPlayer.setOption(OPT_PLAYER, "min-frames", MIN_FRAMES);
        this.mediaPlayer.setOption(OPT_FORMAT, "http-detect-range-support", 1);
        this.mediaPlayer.setOption(OPT_FORMAT, "reconnect", 1);
        this.mediaPlayer.setOption(OPT_FORMAT, "timeout", TIMEOUT_US);
        this.mediaPlayer.setOption(OPT_PLAYER, "infbuf", 1);
        this.mediaPlayer.setOption(OPT_PLAYER, "packet-buffering", 1);
        this.mediaPlayer.setOption(OPT_PLAYER, "soundtouch", 1);
        applyCacheOptions();
        setupListener();
        bindSurface();
    }

    /**
     * Applies the IJK cache gear.
     *
     * <p><b>Memory only, no disk tier.</b> ijkplayer 0.8.8 exposes its read buffers
     * through the ffmpeg AVIOContext option {@code cache}, and the Java API surfaces no
     * disk-persisting equivalent: there is no {@code cache_file}/{@code cache_size}
     * binding in {@code setOption} (the {@code FFP_PROP_INT64_CACHE_STATISTIC_*} constants
     * are read-only counters, not setters), and the red line for this task forbids
     * touching the ijk .so. So the gear drives {@code cache} (the AVIO ring buffer) and
     * {@code infbuf}, both memory-side. Everything here is {@code setOption} only -- no
     * native/kernel change.
     *
     * <p>Gear 0 passes nothing, which leaves ijkplayer on its own defaults and matches
     * the pre-change behaviour.
     */
    private void applyCacheOptions() {
        int sizeMb = CacheSetting.getIjkSizeMb();
        if (sizeMb <= 0) return;
        long bytes = sizeMb * 1024L * 1024L;
        // infbuf=1 is already set in initPlayer(); re-asserting it here keeps the cache
        // gear self-contained if the line above is ever moved.
        this.mediaPlayer.setOption(OPT_FORMAT, "cache", bytes);
        this.mediaPlayer.setOption(OPT_PLAYER, "infbuf", 1);
        Logger.t(TAG).d("ijk cache gear=" + sizeMb + "MB");
    }

    private void setupListener() {
        mediaPlayer.setOnPreparedListener(mp -> {
            prepared = true;
            if (restartWithDownmix()) return;
            onPrepared();
        });
        mediaPlayer.setOnVideoSizeChangedListener((mp, width, height, sarNum, sarDen) -> App.post(() ->
                adapter.notifyListeners(listener -> listener.onVideoSizeChanged(new VideoSize(width, height)))));
        mediaPlayer.setOnCompletionListener(mp -> App.post(() -> {
            adapter.setPlaybackState(Player.STATE_ENDED);
            adapter.notifyPlaying(false);
            adapter.notifyListeners(listener -> {
                listener.onPlaybackStateChanged(Player.STATE_ENDED);
                listener.onIsPlayingChanged(false);
            });
        }));
        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            App.post(() -> onError(what, extra));
            return true;
        });
        mediaPlayer.setOnInfoListener((mp, what, extra) -> {
            App.post(() -> onInfo(what));
            return false;
        });
        mediaPlayer.setOnBufferingUpdateListener((mp, percent) -> {
        });
    }

    private void onPrepared() {
        long position = startPositionMs;
        if (position > 0) {
            startPositionMs = 0;
            mediaPlayer.seekTo(position);
        }
        if (seekPending) {
            seekPending = false;
            mediaPlayer.seekTo(seekPositionMs);
        }
        adapter.setPlaybackState(Player.STATE_READY);
        adapter.notifyListeners(listener -> {
            listener.onPlaybackStateChanged(Player.STATE_READY);
            listener.onTracksChanged(Tracks.EMPTY);
        });
        adapter.play();
    }

    private void onInfo(int what) {
        switch (what) {
            case ON_INFO_BUFFERING_START -> adapter.setPlaybackState(Player.STATE_BUFFERING);
            case ON_INFO_BUFFERING_END -> {
                if (adapter.getPlaybackState() == Player.STATE_BUFFERING) adapter.setPlaybackState(Player.STATE_READY);
                adapter.notifyListeners(listener -> listener.onPlaybackStateChanged(adapter.getPlaybackState()));
            }
            case ON_INFO_VIDEO_RENDERED_START -> adapter.notifyListeners(Player.Listener::onRenderedFirstFrame);
            default -> {
            }
        }
    }

    private void onError(int what, int extra) {
        prepared = false;
        PlaybackException error = new PlaybackException("IJK error: what=" + what + " extra=" + extra,
                new IllegalStateException("IJK playback error"), PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK);
        adapter.notifyListeners(listener -> listener.onPlayerError(error));
    }

    /**
     * After the probe prepare has told us the channel count, decide whether to rebuild
     * with a pan filter. Returns true when it has taken over (caller must not fall
     * through to {@link #onPrepared()}).
     */
    private boolean restartWithDownmix() {
        if (channelProbed) return false;
        channelProbed = true;
        int channels = readChannelCount();
        Log.d(TAG, "probe channelCount=" + channels + " downmixMode=" + describeDownmixMode());
        if (!IjkDownmix.shouldApply(channels)) return false;
        String filter = IjkDownmix.buildAudioFilter(channels);
        if (filter == null) return false;
        Log.d(TAG, "apply ffmpeg af: " + filter);
        try {
            mediaPlayer.stop();
            mediaPlayer.reset();
        } catch (Throwable e) {
            Log.d(TAG, "restart pre-clean failed: " + e);
        }
        applyOptions(filter);
        bindSurface();
        bindDataSource();
        prepared = false;
        mediaPlayer.prepareAsync();
        return true;
    }

    private int readChannelCount() {
        try {
            MediaInfo info = mediaPlayer.getMediaInfo();
            if (info == null || info.mMeta == null || info.mMeta.mAudioStream == null) return 0;
            IjkMediaMeta.IjkStreamMeta audio = info.mMeta.mAudioStream;
            return IjkDownmix.toChannelCount(audio.mChannelLayout, audio.getChannelLayoutInline());
        } catch (Throwable e) {
            Log.d(TAG, "read channel layout failed: " + e);
            return 0;
        }
    }

    private static String describeDownmixMode() {
        return switch (AudioSetting.getDownmixMode()) {
            case DownmixAudioProcessor.MODE_OFF -> "off";
            case DownmixAudioProcessor.MODE_FORCE -> "force";
            default -> "auto";
        };
    }

    private void applyOptions(@Nullable String audioFilter) {
        mediaPlayer.setOption(OPT_PLAYER, "mediacodec", decode == HARD ? 1 : 0);
        if (audioFilter != null) mediaPlayer.setOption(OPT_PLAYER, "af", audioFilter);
    }

    private void bindSurface() {
        if (mediaPlayer != null && surface != null) mediaPlayer.setSurface(surface);
    }

    private void bindDataSource() {
        if (spec == null || spec.getUrl() == null) return;
        Uri uri = Uri.parse(spec.getUrl());
        Map<String, String> headers = spec.getHeaders();
        try {
            if (headers != null && !headers.isEmpty()) mediaPlayer.setDataSource(App.get(), uri, headers);
            else mediaPlayer.setDataSource(App.get(), uri);
        } catch (Exception e) {
            Log.d(TAG, "setDataSource failed: " + e);
            onError(-1, -1);
        }
    }

    // ----- PlayerEngine -----------------------------------------------------

    @Override
    public Type getType() {
        return Type.IJK;
    }

    @Override
    public Player getPlayer() {
        return adapter;
    }

    @Override
    public int getAudioChannelCount() {
        int channels = readChannelCount();
        return channels > 0 ? channels : Format.NO_VALUE;
    }

    @Override
    public void release() {
        released = true;
        adapter.removeListener(this);
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Throwable e) {
                Log.d(TAG, "stop before release failed: " + e);
            }
            try {
                mediaPlayer.release();
            } catch (Throwable e) {
                Log.d(TAG, "release failed: " + e);
            }
            mediaPlayer = null;
        }
        prepared = false;
        adapter.releaseState();
    }

    @Override
    public void setDecode(int decode) {
        // Options are only read when the FFmpegContext is built, so this takes effect
        // on the next start() -- same as ExoPlayer needing a rebuild for a decode change.
        this.decode = decode;
    }

    @Override
    public void start(PlaySpec spec, long startPositionMs) {
        if (released || mediaPlayer == null || spec == null || spec.getUrl() == null) return;
        this.spec = spec;
        this.startPositionMs = Math.max(0, startPositionMs);
        this.channelProbed = false;
        this.prepared = false;
        adapter.setMediaItem(MediaItemFactory.from(spec), startPositionMs);
        try {
            mediaPlayer.reset();
            applyOptions(null);
            bindSurface();
            bindDataSource();
            adapter.setPlaybackState(Player.STATE_BUFFERING);
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.d(TAG, "start failed: " + e);
            onError(-1, -1);
        }
    }

    @Override
    public void stop() {
        if (mediaPlayer == null) return;
        try {
            mediaPlayer.stop();
        } catch (Throwable e) {
            Log.d(TAG, "stop failed: " + e);
        }
        prepared = false;
        adapter.setPlaybackState(Player.STATE_IDLE);
        adapter.notifyPlaying(false);
        adapter.notifyListeners(listener -> listener.onPlaybackStateChanged(Player.STATE_IDLE));
    }

    @Override
    public boolean addSubtitle(Sub sub) {
        // Honest: ijkplayer's Java layer exposes no external-subtitle entry point (there
        // is no sub-add binding), so this returns false and PlayerManager takes its
        // existing restart branch instead of pretending the subtitle was added.
        // In-file subtitle track selection (selectTrack) does work, but this adapter does
        // not expose Tracks, so the track panel stays hidden on IJK exactly as on MPV.
        return false;
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return "IJK: " + e.getMessage();
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED -> ErrorAction.DECODE;
            default -> ErrorAction.FATAL;
        };
    }

    @Override
    public void onTracksChanged(@NonNull Tracks tracks) {
    }

    // ----- media3 Player adapter -------------------------------------------

    private class IjkPlayerAdapter implements Player {

        private int playbackState = Player.STATE_IDLE;
        private boolean playWhenReady;
        private boolean playing;
        private long positionMs;

        void setPlaybackState(int state) {
            playbackState = state;
        }


        void notifyPlaying(boolean value) {
            if (playing == value) return;
            playing = value;
            notifyListeners(listener -> listener.onIsPlayingChanged(value));
        }

        void releaseState() {
            playbackState = Player.STATE_IDLE;
            playing = false;
            IjkPlayerEngine.this.listeners.clear();
        }

        void notifyListeners(Consumer<Player.Listener> action) {
            for (Player.Listener listener : new ArrayList<>(IjkPlayerEngine.this.listeners)) {
                try {
                    action.accept(listener);
                } catch (Throwable e) {
                    Log.d(TAG, "listener failed: " + e);
                }
            }
        }

        @Override
        public void addListener(Player.Listener listener) {
            if (listener != null && !IjkPlayerEngine.this.listeners.contains(listener)) IjkPlayerEngine.this.listeners.add(listener);
        }

        @Override
        public void removeListener(Player.Listener listener) {
            IjkPlayerEngine.this.listeners.remove(listener);
        }

        @Override
        public Looper getApplicationLooper() {
            return Looper.getMainLooper();
        }

        @Override
        public void setMediaItems(List<MediaItem> items) {
            if (items == null || items.isEmpty()) return;
            setMediaItem(items.get(0), 0);
        }

        @Override
        public void setMediaItems(List<MediaItem> items, boolean resetPosition) {
            setMediaItems(items);
        }

        @Override
        public void setMediaItems(List<MediaItem> items, int startIndex, long startPositionMs) {
            setMediaItems(items);
        }

        @Override
        public void setMediaItem(MediaItem item) {
            setMediaItem(item, 0);
        }

        @Override
        public void setMediaItem(MediaItem item, long startPositionMs) {
            IjkPlayerEngine.this.mediaItem = item;
            this.positionMs = startPositionMs;
        }

        @Override
        public void setMediaItem(MediaItem item, boolean resetPosition) {
            setMediaItem(item, resetPosition ? 0 : positionMs);
        }

        @Override
        public void addMediaItem(MediaItem item) {
        }

        @Override
        public void addMediaItem(int index, MediaItem item) {
        }

        @Override
        public void addMediaItems(List<MediaItem> items) {
        }

        @Override
        public void addMediaItems(int index, List<MediaItem> items) {
        }

        @Override
        public void moveMediaItem(int currentIndex, int newIndex) {
        }

        @Override
        public void moveMediaItems(int fromIndex, int toIndex, int newIndex) {
        }

        @Override
        public void replaceMediaItem(int index, MediaItem item) {
            if (index == 0) mediaItem = item;
        }

        @Override
        public void replaceMediaItems(int fromIndex, int toIndex, List<MediaItem> items) {
        }

        @Override
        public void removeMediaItem(int index) {
            if (index == 0) mediaItem = null;
        }

        @Override
        public void removeMediaItems(int fromIndex, int toIndex) {
        }

        @Override
        public void clearMediaItems() {
            mediaItem = null;
        }

        @Override
        public boolean isCommandAvailable(int command) {
            return EngineCommands.isAvailable(command);
        }

        @Override
        public boolean canAdvertiseSession() {
            // Matches ExoPlayer. media3's MediaSession.BuilderBase asserts on this in
            // its constructor, so returning false took PlaybackService.onCreate down with
            // a bare IllegalArgumentException (no message) before playback even started.
            // These adapters do implement the full Player contract, so advertising them
            // is honest, not a workaround.
            return true;
        }

        @Override
        public Commands getAvailableCommands() {
            return EngineCommands.all();
        }

        @Override
        public void prepare() {
            // The real prepare is inside PlayerEngine.start(); PlaybackSnapshot.restore
            // never calls this.
        }

        @Override
        public int getPlaybackState() {
            return playbackState;
        }

        @Override
        public int getPlaybackSuppressionReason() {
            return PLAYBACK_SUPPRESSION_REASON_NONE;
        }

        @Override
        public boolean isPlaying() {
            return playing;
        }

        @Override
        public PlaybackException getPlayerError() {
            return null;
        }

        @Override
        public void play() {
            playWhenReady = true;
            if (mediaPlayer == null || !prepared) return;
            try {
                mediaPlayer.start();
            } catch (Throwable e) {
                Log.d(TAG, "start failed: " + e);
            }
            notifyPlaying(true);
        }

        @Override
        public void pause() {
            playWhenReady = false;
            if (mediaPlayer != null && prepared) {
                try {
                    mediaPlayer.pause();
                } catch (Throwable e) {
                    Log.d(TAG, "pause failed: " + e);
                }
            }
            notifyPlaying(false);
        }

        @Override
        public void setPlayWhenReady(boolean playWhenReady) {
            if (playWhenReady) play();
            else pause();
        }

        @Override
        public boolean getPlayWhenReady() {
            return playWhenReady;
        }

        @Override
        public void setRepeatMode(int repeatMode) {
            // Single-item playlist, so repeat has no real meaning; accepted so that
            // PlaybackSnapshot capture/restore round-trips without erroring.
        }

        @Override
        public int getRepeatMode() {
            return REPEAT_MODE_OFF;
        }

        @Override
        public void setShuffleModeEnabled(boolean shuffleModeEnabled) {
        }

        @Override
        public boolean getShuffleModeEnabled() {
            return false;
        }

        @Override
        public boolean isLoading() {
            return playbackState == Player.STATE_BUFFERING;
        }

        @Override
        public void seekToDefaultPosition() {
            seekTo(0);
        }

        @Override
        public void seekToDefaultPosition(int mediaItemIndex) {
            seekTo(0);
        }

        @Override
        public void seekTo(long positionMs) {
            this.positionMs = positionMs;
            if (mediaPlayer == null) return;
            if (!prepared) {
                seekPending = true;
                seekPositionMs = positionMs;
                return;
            }
            mediaPlayer.seekTo(positionMs);
        }

        @Override
        public void seekTo(int mediaItemIndex, long positionMs) {
            seekTo(positionMs);
        }

        @Override
        public long getSeekBackIncrement() {
            return C.DEFAULT_SEEK_BACK_INCREMENT_MS;
        }

        @Override
        public void seekBack() {
            seekTo(Math.max(0, getCurrentPosition() - getSeekBackIncrement()));
        }

        @Override
        public long getSeekForwardIncrement() {
            return C.DEFAULT_SEEK_FORWARD_INCREMENT_MS;
        }

        @Override
        public void seekForward() {
            seekTo(getCurrentPosition() + getSeekForwardIncrement());
        }

        @Override
        public boolean hasPreviousMediaItem() {
            return false;
        }

        @Override
        public void seekToPreviousMediaItem() {
        }

        @Override
        public long getMaxSeekToPreviousPosition() {
            return 0;
        }

        @Override
        public void seekToPrevious() {
        }

        @Override
        public boolean hasNextMediaItem() {
            return false;
        }

        @Override
        public void seekToNextMediaItem() {
        }

        @Override
        public void seekToNext() {
        }

        @Override
        public void setPlaybackParameters(PlaybackParameters playbackParameters) {
            if (playbackParameters == null) return;
            IjkPlayerEngine.this.playbackParameters = playbackParameters;
            applySpeed(playbackParameters.speed);
        }

        @Override
        public void setPlaybackSpeed(float speed) {
            setPlaybackParameters(new PlaybackParameters(speed));
        }

        private void applySpeed(float speed) {
            if (mediaPlayer == null) return;
            try {
                mediaPlayer.setSpeed(speed);
            } catch (Throwable e) {
                Log.d(TAG, "setSpeed failed: " + e);
            }
        }

        @Override
        public PlaybackParameters getPlaybackParameters() {
            return IjkPlayerEngine.this.playbackParameters;
        }

        @Override
        public Tracks getCurrentTracks() {
            return Tracks.EMPTY;
        }

        @Override
        public TrackSelectionParameters getTrackSelectionParameters() {
            return IjkPlayerEngine.this.trackSelectionParameters;
        }

        @Override
        public void setTrackSelectionParameters(TrackSelectionParameters parameters) {
            if (parameters != null) IjkPlayerEngine.this.trackSelectionParameters = parameters;
        }

        @Override
        public MediaMetadata getMediaMetadata() {
            return IjkPlayerEngine.this.mediaItem == null ? MediaMetadata.EMPTY : mediaItem.mediaMetadata;
        }

        @Override
        public MediaMetadata getPlaylistMetadata() {
            return MediaMetadata.EMPTY;
        }

        @Override
        public void setPlaylistMetadata(MediaMetadata mediaMetadata) {
        }

        @Override
        public Object getCurrentManifest() {
            return null;
        }

        @Override
        public Timeline getCurrentTimeline() {
            return Timeline.EMPTY;
        }

        @Override
        public int getCurrentPeriodIndex() {
            return 0;
        }

        @Override
        public int getCurrentWindowIndex() {
            return 0;
        }

        @Override
        public int getCurrentMediaItemIndex() {
            return 0;
        }

        @Override
        public int getNextWindowIndex() {
            return C.INDEX_UNSET;
        }

        @Override
        public int getNextMediaItemIndex() {
            return C.INDEX_UNSET;
        }

        @Override
        public int getPreviousWindowIndex() {
            return C.INDEX_UNSET;
        }

        @Override
        public int getPreviousMediaItemIndex() {
            return C.INDEX_UNSET;
        }

        @Override
        public MediaItem getCurrentMediaItem() {
            return IjkPlayerEngine.this.mediaItem;
        }

        @Override
        public int getMediaItemCount() {
            return IjkPlayerEngine.this.mediaItem == null ? 0 : 1;
        }

        @Override
        public MediaItem getMediaItemAt(int index) {
            return index == 0 ? mediaItem : null;
        }

        @Override
        public long getDuration() {
            if (mediaPlayer == null || !prepared) return C.TIME_UNSET;
            long duration = mediaPlayer.getDuration();
            return duration > 0 ? duration : C.TIME_UNSET;
        }

        @Override
        public long getCurrentPosition() {
            if (mediaPlayer == null || !prepared) return positionMs;
            return mediaPlayer.getCurrentPosition();
        }

        @Override
        public long getBufferedPosition() {
            return getCurrentPosition();
        }

        @Override
        public int getBufferedPercentage() {
            return 0;
        }

        @Override
        public long getTotalBufferedDuration() {
            return 0;
        }

        @Override
        public boolean isCurrentWindowDynamic() {
            return false;
        }

        @Override
        public boolean isCurrentMediaItemDynamic() {
            return false;
        }

        @Override
        public boolean isCurrentWindowLive() {
            return isLive();
        }

        @Override
        public boolean isCurrentMediaItemLive() {
            return isLive();
        }

        private boolean isLive() {
            if (mediaPlayer == null || !prepared) return false;
            return mediaPlayer.getDuration() <= 0;
        }

        @Override
        public long getCurrentLiveOffset() {
            return C.TIME_UNSET;
        }

        @Override
        public boolean isCurrentWindowSeekable() {
            return prepared;
        }

        @Override
        public boolean isCurrentMediaItemSeekable() {
            return prepared;
        }

        @Override
        public boolean isPlayingAd() {
            return false;
        }

        @Override
        public int getCurrentAdGroupIndex() {
            return C.INDEX_UNSET;
        }

        @Override
        public int getCurrentAdIndexInAdGroup() {
            return C.INDEX_UNSET;
        }

        @Override
        public long getContentDuration() {
            return getDuration();
        }

        @Override
        public long getContentPosition() {
            return getCurrentPosition();
        }

        @Override
        public long getContentBufferedPosition() {
            return getBufferedPosition();
        }

        @Override
        public AudioAttributes getAudioAttributes() {
            return AudioAttributes.DEFAULT;
        }

        @Override
        public void setAudioAttributes(AudioAttributes audioAttributes, boolean handleAudioFocus) {
        }

        @Override
        public void setVolume(float volume) {
            IjkPlayerEngine.this.volume = volume;
            if (mediaPlayer == null) return;
            try {
                mediaPlayer.setVolume(volume, volume);
            } catch (Throwable e) {
                Log.d(TAG, "setVolume failed: " + e);
            }
        }

        @Override
        public float getVolume() {
            return IjkPlayerEngine.this.volume;
        }

        @Override
        public void clearVideoSurface() {
            setVideoSurface(null);
        }

        @Override
        public void clearVideoSurface(@Nullable Surface surface) {
            if (surface == IjkPlayerEngine.this.surface) setVideoSurface(null);
        }

        @Override
        public void setVideoSurface(@Nullable Surface surface) {
            IjkPlayerEngine.this.surface = surface;
            bindSurface();
        }

        @Override
        public void clearVideoSurfaceHolder(@Nullable SurfaceHolder surfaceHolder) {
            clearVideoSurface();
        }

        @Override
        public void setVideoSurfaceHolder(@Nullable SurfaceHolder surfaceHolder) {
            setVideoSurface(surfaceHolder == null ? null : surfaceHolder.getSurface());
        }

        @Override
        public void clearVideoSurfaceView(@Nullable SurfaceView surfaceView) {
            clearVideoSurface();
        }

        @Override
        public void setVideoSurfaceView(@Nullable SurfaceView surfaceView) {
            // media3 PlayerView hands a SurfaceView-backed player the view itself and
            // only reaches the Surface later via the SurfaceHolder callback. Reading the
            // holder synchronously here is what guarantees ijk gets its Surface before
            // prepareAsync().
            setVideoSurfaceHolder(surfaceView == null ? null : surfaceView.getHolder());
        }

        @Override
        public void clearVideoTextureView(@Nullable TextureView textureView) {
            clearVideoSurface();
        }

        @Override
        public void setVideoTextureView(@Nullable TextureView textureView) {
        }

        @Override
        public VideoSize getVideoSize() {
            if (mediaPlayer == null) return VideoSize.UNKNOWN;
            int width = mediaPlayer.getVideoWidth();
            int height = mediaPlayer.getVideoHeight();
            return width <= 0 || height <= 0 ? VideoSize.UNKNOWN : new VideoSize(width, height);
        }

        @Override
        public Size getSurfaceSize() {
            return Size.UNKNOWN;
        }

        @Override
        public CueGroup getCurrentCues() {
            return CueGroup.EMPTY_TIME_ZERO;
        }

        @Override
        public DeviceInfo getDeviceInfo() {
            return DeviceInfo.UNKNOWN;
        }

        @Override
        public int getDeviceVolume() {
            return 0;
        }

        @Override
        public boolean isDeviceMuted() {
            return false;
        }

        @Override
        public void setDeviceVolume(int volume) {
        }

        @Override
        public void setDeviceVolume(int volume, int flags) {
        }

        @Override
        public void increaseDeviceVolume() {
        }

        @Override
        public void increaseDeviceVolume(int flags) {
        }

        @Override
        public void decreaseDeviceVolume() {
        }

        @Override
        public void decreaseDeviceVolume(int flags) {
        }

        @Override
        public void setDeviceMuted(boolean muted) {
        }

        @Override
        public void setDeviceMuted(boolean muted, int flags) {
        }

        @Override
        public void stop() {
            IjkPlayerEngine.this.stop();
        }

        @Override
        public void release() {
            IjkPlayerEngine.this.release();
        }
    }
}
