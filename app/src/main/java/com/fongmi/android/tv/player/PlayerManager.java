package com.fongmi.android.tv.player;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaChapter;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.danmaku.DanmakuConfig;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.effect.PlayerEffectManager;
import com.fongmi.android.tv.player.effect.audio.AudioEffectBands;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.engine.PlayerEngineFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.parse.ParseJob;
import com.fongmi.android.tv.player.track.TrackUtil;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.setting.SpeedSetting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.common.net.HttpHeaders;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerManager implements ParseCallback {

    private final PlayerEffectManager effects;
    private final Runnable runnable;
    private final Callback callback;
    private PendingPreload pendingPreload;
    private PlayerEngine engine;
    private VideoSize videoSize;
    private ParseJob parseJob;
    private PlaySpec spec;
    private Player player;

    private DanmakuConfig danmakuConfig;
    private long pendingStartPositionMs;
    private boolean danmakuEnabled;
    private boolean initTrack;
    private int retry;
    private int decode;
    // Default 0, matching what the old Player-backed getters returned when the command
    // was unavailable -- the setting panels display these directly as a number.
    private long audioOffsetMs;
    private long textOffsetMs;

    // 2026-10-24: 直播冻结看门狗。
    //
    // <p>解码器跟不上时（例如 8K Dolby Vision 在没有硬解的手机上），播放器**既不报错
    // 也不推进**：没有 PlaybackException，直播就永远停在首帧，用户看到的就是播一段
    // 就卡住"。已有的错误回退（LivePlaybackController.fallbackAfterError → 切下一条
    // 线路 -> 换台）因此完全用不上。
    //
    // <p>这里盯住播放位置：直播播放中、位置连续 FREEZE_LIMIT_MS 不动，就当作一次
    // 播放失败上报，走和真实报错完全相同的回退路径 —— 多线路的备用源这时才派上用场。
    private static final long FREEZE_WATCH_MS = 2_000L;
    // 20s 而非 8s：这批 CCTV 源的直播窗口只有 3-5 个分片（30-50s）且每 30s 滚动一次，
    // 每次滚动播放器要重新对齐直播边缘，会卡 10-20s 然后**自己恢复**（实测手机与盒子
    // 完全一致）。8s 的阈值会把"自愈中"误判成故障 → 切线反而打断恢复、造成抖动。
    // 真正卡死的流（frozen 171s 那种）仍然会在 20s 内被判定并换线。
    private static final long FREEZE_LIMIT_MS = 20_000L;
    private static final int FREEZE_WINDOW = 6;                 // 6 × 2s = 12s 观察窗
    private static final double FREEZE_MIN_RATE = 0.5;          // 播放速率低于实时的 50% 即算卡
    private final Handler freezeHandler = new Handler(Looper.getMainLooper());
    private final Runnable freezeWatcher = this::checkFreeze;
    private final long[] freezeTimes = new long[FREEZE_WINDOW];
    private final long[] freezePositions = new long[FREEZE_WINDOW];
    private int freezeIndex = 0;
    private int freezeCount = 0;
    private long frozenSince = 0;
    private boolean freezeReported = false;

    public PlayerManager(Callback callback) {
        this.callback = callback;
        this.decode = PlayerEngine.HARD;
        this.runnable = this::onPlayTimeout;
        this.pendingStartPositionMs = C.TIME_UNSET;
        this.engine = PlayerEngineFactory.create(decode, listener);
        this.effects = new PlayerEffectManager(() -> engine);
        this.danmakuConfig = DanmakuSetting.getConfig();
        this.danmakuEnabled = DanmakuSetting.isShow();
        this.player = engine.getPlayer();
        this.freezeHandler.postDelayed(freezeWatcher, FREEZE_WATCH_MS);
    }

    public void release() {
        App.removeCallbacks(runnable);
        freezeHandler.removeCallbacks(freezeWatcher);
        if (player != null) player.removeListener(listener);
        if (engine != null) engine.release();
        engine = null;
        player = null;
    }

    /**
     * Watch how fast a live stream is actually advancing.
     *
     * <p>A stream that cannot be decoded on this device does not necessarily stop:
     * the playhead crawls (measured on an 8K Dolby Vision stream on a phone without
     * DV hardware: 11 seconds of programme in 30 seconds of wall clock) while the
     * picture sits on one frame. No PlaybackException is ever raised, so the error
     * fallback never runs.
     *
     * <p>So compare the playhead advance against real time over a 12 second window.
     * Below half of real time for more than FREEZE_LIMIT_MS means the stream is not
     * keeping up, and we report it as a playback failure -- the same path a real
     * error takes, so the live layer switches to the next line or the next channel.
     */
    private void checkFreeze() {
        try {
            Player p = player;
            // getPlayWhenReady() rather than isPlaying(): a user pause must not look
            // like a freeze, but a rebuffer must (playWhenReady stays true there).
            if (p == null || !isLive() || !p.getPlayWhenReady()) {
                resetFreeze();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            long position = p.getCurrentPosition();
            freezeTimes[freezeIndex] = now;
            freezePositions[freezeIndex] = position;
            freezeIndex = (freezeIndex + 1) % FREEZE_WINDOW;
            if (freezeCount < FREEZE_WINDOW) freezeCount++;
            if (freezeCount < FREEZE_WINDOW) return;          // 窗口未满，先看
            int newest = (freezeIndex - 1 + FREEZE_WINDOW) % FREEZE_WINDOW;
            int oldest = freezeIndex;                         // 下一个写入位 = 最老样本
            long elapsed = freezeTimes[newest] - freezeTimes[oldest];
            long advanced = freezePositions[newest] - freezePositions[oldest];
            if (elapsed <= 0) return;
            double rate = advanced * 1000.0 / elapsed;
            if (rate >= FREEZE_MIN_RATE) {
                frozenSince = 0;
                freezeReported = false;
                return;
            }
            // 进度负增长（直播窗口重新对齐）不算卡
            if (advanced < 0) {
                frozenSince = 0;
                freezeReported = false;
                return;
            }
            if (frozenSince == 0) frozenSince = now;
            if (!freezeReported && now - frozenSince > FREEZE_LIMIT_MS) {
                freezeReported = true;
                Notify.show("直播无响应，已自动切换线路");
                callback.onError("直播无响应，已自动切换线路");
            }
        } catch (Throwable ignored) {
        } finally {
            freezeHandler.postDelayed(freezeWatcher, FREEZE_WATCH_MS);
        }
    }

    private void resetFreeze() {
        freezeCount = 0;
        freezeIndex = 0;
        frozenSince = 0;
        freezeReported = false;
    }

    public Player getPlayer() {
        return player;
    }

    private void setPlayer(Player player) {
        this.player = player;
        callback.onPlayerRebuild(player);
    }

    public Tracks getCurrentTracks() {
        return player.getCurrentTracks();
    }

    public int getAudioChannelCount() {
        return engine == null ? Format.NO_VALUE : engine.getAudioChannelCount();
    }

    // 2026-10-09: these four were thin pass-throughs to fork-only Player methods
    // (getCurrentMediaChapters / getCurrentMediaEditions / selectChapter /
    // selectEdition). None of them exist in a published Media3 release, and nothing in
    // official Media3 ever populates either list -- chapters and alternate editions
    // come from the fork's patched extractors, which read metadata the standard
    // demuxers discard. Returning empty lists keeps haveChapter()/haveEdition()
    // false, so the chapter and edition rows stay hidden, which is the same thing the
    // user would have seen on a stream with no such metadata anyway.
    public List<MediaChapter> getCurrentMediaChapters() {
        return Collections.emptyList();
    }

    public List<MediaEdition> getCurrentMediaEditions() {
        return Collections.emptyList();
    }

    public MediaItem getCurrentMediaItem() {
        return player.getCurrentMediaItem();
    }

    public int getPlaybackState() {
        return player.getPlaybackState();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public boolean isReleased() {
        return player == null;
    }

    public String getUrl() {
        return spec != null ? spec.getUrl() : null;
    }

    public String getKey() {
        return spec != null ? spec.getKey() : null;
    }

    public List<Danmaku> getDanmakus() {
        return spec != null ? spec.getDanmakus() : List.of();
    }

    private void notifyDanmakuSourceChanged() {
        callback.onDanmakuSourceChanged(getSelectedDanmakuUri());
    }

    public MediaMetadata getMetadata() {
        return spec != null ? spec.getMetadata() : null;
    }

    public String getMediaTitle() {
        MediaMetadata metadata = getMetadata();
        if (metadata == null) return "";
        CharSequence title = !TextUtils.isEmpty(metadata.displayTitle) ? metadata.displayTitle : metadata.title;
        if (TextUtils.isEmpty(title)) title = metadata.artist;
        return TextUtils.isEmpty(title) ? "" : title.toString();
    }

    public void setMetadata(@NonNull MediaMetadata metadata) {
        if (spec == null || metadata.equals(spec.getMetadata())) return;
        spec.setMetadata(metadata);
        if (TextUtils.isEmpty(spec.getUrl())) return;
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) player.replaceMediaItem(player.getCurrentMediaItemIndex(), current.buildUpon().setMediaMetadata(metadata).build());
    }

    public Map<String, String> getHeaders() {
        return spec == null || spec.getHeaders() == null ? new HashMap<>() : spec.getHeaders();
    }

    public float getSpeed() {
        return player.getPlaybackParameters().speed;
    }

    public boolean isEmpty() {
        return spec == null || TextUtils.isEmpty(spec.getUrl());
    }

    public boolean hasPlaySpec() {
        return spec != null;
    }

    public boolean canPreloadNext() {
        return PreloadSetting.isEnabled() && PreloadSetting.isNextEpisodeEnabled() && engine != null && engine.getType() == PlayerEngine.Type.EXO;
    }

    public boolean isPortrait() {
        return getVideoHeight() > getVideoWidth();
    }

    public boolean isLandscape() {
        return getVideoWidth() > getVideoHeight();
    }

    public boolean isLive() {
        return player.getCurrentMediaItem() != null && player.isCurrentMediaItemLive();
    }

    public boolean isVod() {
        return player.getCurrentMediaItem() != null && !player.isCurrentMediaItemLive();
    }

    public boolean haveTrack(int type) {
        return TrackUtil.count(getCurrentTracks(), type) > 0;
    }

    public boolean haveEdition() {
        return !getCurrentMediaEditions().isEmpty();
    }

    public boolean haveChapter() {
        return !getCurrentMediaChapters().isEmpty();
    }

    public boolean haveDanmaku() {
        return spec != null && spec.getSelectedDanmaku() != null;
    }

    public boolean canSetOpening(long position, long duration) {
        return position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration);
    }

    public boolean canSetEnding(long position, long duration) {
        return position > 0 && duration > 0 && duration - position <= Constant.getOpEdLimit(duration);
    }

    public int getVideoWidth() {
        return videoSize == null ? 0 : videoSize.width;
    }

    public int getVideoHeight() {
        return videoSize == null ? 0 : videoSize.height;
    }

    public long getPosition() {
        return player.getCurrentPosition();
    }

    public String getSizeText() {
        return (getVideoWidth() == 0 && getVideoHeight() == 0) ? "" : getVideoWidth() + " x " + getVideoHeight();
    }

    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    public boolean canSetAudioSetting() {
        return effects.canSetAudioSetting();
    }

    public AudioEffectBands getAudioSettingBands() {
        return effects.getAudioSettingBands();
    }

    public int getAudioSettingError() {
        return effects.getAudioSettingError();
    }

    public boolean canSetVideoSetting() {
        return effects.canSetVideoSetting();
    }

    public int getVideoSettingError() {
        return effects.getVideoSettingError();
    }

    public boolean supportsVideoSharpness() {
        return effects.supportsVideoSharpness();
    }

    public int getEngine() {
        return switch (PlayerEngineFactory.getEngineType(engine)) {
            case MPV -> PlayerSetting.ENGINE_MPV;
            case IJK -> PlayerSetting.ENGINE_IJK;
            default -> PlayerSetting.ENGINE_EXO;
        };
    }

    public void setEngine(int targetEngine) {
        PlayerSetting.putEngine(targetEngine);
        if (isEmpty() || PlayerEngineFactory.matches(engine, spec)) return;
        PlaybackSnapshot snapshot = PlaybackSnapshot.capture(player);
        startCurrent(snapshot.positionMs());
        snapshot.restore(player);
    }

    public String getPositionTime(long delta) {
        return Util.timeMs(Math.clamp(getPosition() + delta, 0, Math.max(0, getDuration())));
    }

    public long getDuration() {
        return player.getDuration();
    }

    public String getDurationTime() {
        return Util.timeMs(Math.max(0, getDuration()));
    }

    public void setSub(Sub sub) {
        if (sub == null || sub.isEmpty()) return;
        if (spec != null) spec.setSub(sub);
        if (engine.addSubtitle(sub)) play();
        else startCurrent();
    }

    public void setFormat(String format) {
        if (spec != null) spec.setFormat(format);
        startCurrent();
    }

    // Unreachable in practice: both dialogs are only opened when haveChapter() /
    // haveEdition() is true, and those now report false. Kept so the dialogs still
    // compile and behave sanely if the lists ever become non-empty.
    public void selectChapter(MediaChapter chapter) {
    }

    public void selectEdition(MediaEdition edition) {
    }

    // See the note in PlaybackSnapshot: audio/text sync offsets were Player methods in
    // FongMi's Media3 fork. They are held here now so the setting panels keep working
    // and the values survive a player rebuild. C.TIME_UNSET means "not adjusted".
    public long getAudioOffsetMs() {
        return audioOffsetMs;
    }

    public void setAudioOffsetMs(long audioOffsetMs) {
        this.audioOffsetMs = audioOffsetMs;
    }

    public long getTextOffsetMs() {
        return textOffsetMs;
    }

    public void setTextOffsetMs(long textOffsetMs) {
        this.textOffsetMs = textOffsetMs;
    }

    public void setDanmakuConfig(DanmakuConfig config) {
        danmakuConfig = config;
        callback.onDanmakuConfigChanged(danmakuConfig);
    }

    public void setDanmakuEnabled(boolean enabled) {
        if (danmakuEnabled == enabled) return;
        danmakuEnabled = enabled;
        callback.onDanmakuEnabledChanged(danmakuEnabled);
    }

    public void setSubtitleSettingStyle() {
        if (engine != null) engine.setSubtitleStyle();
    }

    public void sendDanmaku(String text) {
        callback.onDanmakuSent(text);
    }

    public float setSpeed(float speed) {
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return getSpeed();
        player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(SpeedSetting.clamp(speed)));
        return getSpeed();
    }

    public float toggleSpeed() {
        return setSpeed(getSpeed() == 1 ? SpeedSetting.getLongPress() : 1);
    }

    public boolean supportsSkipSilence() {
        return effects.supportsSkipSilence();
    }

    public boolean isSkipSilence() {
        return effects.isSkipSilence();
    }

    public void setSkipSilenceEnabled(boolean enabled) {
        effects.setSkipSilenceEnabled(enabled);
    }

    public void setTrack(List<Track> tracks) {
        if (!tracks.isEmpty()) TrackUtil.setTrackSelection(player, tracks);
    }

    public void setVideoSetting(int preset) {
        effects.setVideoSetting(preset);
    }

    public void refreshVideoSetting() {
        effects.refreshVideoSetting();
    }

    public void previewVideoSetting(boolean original) {
        effects.previewVideoSetting(original);
    }

    public void setAudioSetting(int preset) {
        effects.setAudioSetting(preset);
    }

    public void refreshAudioSetting() {
        effects.refreshAudioSetting();
    }

    public void previewAudioSetting(boolean original) {
        effects.previewAudioSetting(original);
    }

    private boolean isMpvEngine() {
        return engine != null && engine.getType() == PlayerEngine.Type.MPV;
    }

    public void play() {
        player.play();
    }

    public void pause() {
        player.pause();
    }

    public void stop() {
        engine.stop();
        stopParse();
    }

    public void clearMediaItems() {
        player.clearMediaItems();
    }

    public boolean isRepeatOne() {
        return player.getRepeatMode() == Player.REPEAT_MODE_ONE;
    }

    public void setRepeatOne(boolean repeat) {
        player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    public void replay(long positionMs) {
        if (positionMs == C.TIME_UNSET) player.seekToDefaultPosition();
        else player.seekTo(positionMs);
        player.play();
    }

    public void seekTo(long time) {
        player.seekTo(time);
    }

    // 2026-10-09: the four offset methods that used to live here delegated to the
    // fork-only Player.get/setTextOffsetMs and get/setAudioOffsetMs (guarded by
    // COMMAND_GET/SET_TEXT_OFFSET and COMMAND_GET/SET_AUDIO_OFFSET). Replaced by the
    // locally-backed versions further up this file; see the note there.
    public void reset() {
        App.removeCallbacks(runnable);
        retry = 0;
    }

    public void clear() {
        spec = null;
    }

    public boolean preload(PlaySpec spec, long startPositionMs) {
        if (!canPreloadNext() || spec == null || !PlayerEngineFactory.matches(engine, spec)) return false;
        pendingPreload = new PendingPreload(spec.checkUa(), Math.max(0, startPositionMs));
        startPreloadIfReady();
        return true;
    }

    public void clearPreload() {
        pendingPreload = null;
        if (engine != null) engine.clearPreload();
    }

    public void resetTrack() {
        TrackUtil.reset(player);
    }

    public void toggleDecode() {
        setDecode(isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD);
    }

    private void handleDecodeError(PlaybackException e) {
        if (++retry > 1) callback.onError(engine.getErrorMessage(e));
        else retryDecode(isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD);
    }

    private void retryDecode(int decode) {
        setDecode(decode);
        PlaybackSnapshot snapshot = PlaybackSnapshot.capture(player);
        startCurrent(snapshot.positionMs());
        snapshot.restore(player);
    }

    private void setDecode(int decode) {
        this.decode = decode;
        engine.setDecode(decode);
        callback.onDecodeChanged();
    }

    private boolean isHard() {
        return decode == PlayerEngine.HARD;
    }

    private void onPlayTimeout() {
        callback.onError(ResUtil.getString(R.string.error_play_timeout));
        stop();
    }

    private void ensureEngine(PlaySpec spec) {
        if (PlayerEngineFactory.matches(engine, spec)) return;
        PlayerEngine old = engine;
        player.removeListener(listener);
        engine = PlayerEngineFactory.create(decode, spec, listener);
        setPlayer(engine.getPlayer());
        old.release();
    }

    public void browse(PlaySpec spec, long startPositionMs) {
        reset();
        clear();
        stopParse();
        start(spec, Constant.TIMEOUT_PLAY, startPositionMs);
    }

    public void start(PlaySpec spec, long timeout) {
        start(spec, timeout, C.TIME_UNSET);
    }

    public void start(PlaySpec spec, long timeout, long startPositionMs) {
        this.spec = spec;
        setMediaItem(timeout, startPositionMs);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata) {
        parse(key, result, useParse, metadata, C.TIME_UNSET);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata, long startPositionMs) {
        stopParse();
        pendingStartPositionMs = startPositionMs;
        spec = PlaySpec.fromParse(result, key, metadata);
        parseJob = ParseJob.create(this).start(result, useParse);
    }

    private void stopParse() {
        if (parseJob != null) parseJob.stop();
        parseJob = null;
        pendingStartPositionMs = C.TIME_UNSET;
    }

    private void setMediaItem(long timeout, long startPositionMs) {
        if (spec == null || spec.getUrl() == null) return;
        ensureEngine(spec.checkUa());
        pendingPreload = null;
        initTrack = false;
        engine.start(spec, startPositionMs);
        notifyDanmakuSourceChanged();
        App.post(runnable, timeout);
        callback.onPrepare();
    }

    private void startCurrent() {
        startCurrent(getPosition());
    }

    private void startCurrent(long startPositionMs) {
        setMediaItem(Constant.TIMEOUT_PLAY, startPositionMs);
    }

    private void startPreloadIfReady() {
        PendingPreload preload = pendingPreload;
        if (preload == null || player.getPlaybackState() != Player.STATE_READY) return;
        pendingPreload = null;
        engine.preload(preload.spec(), preload.startPositionMs());
    }

    @Nullable
    public Uri getSelectedDanmakuUri() {
        Danmaku item = spec != null ? spec.getSelectedDanmaku() : null;
        return item == null ? null : item.getUri();
    }

    public void setDanmaku(Danmaku item) {
        if (spec == null) return;
        spec.selectDanmaku(item);
        notifyDanmakuSourceChanged();
    }

    public void toggleDanmaku(Danmaku item) {
        if (spec == null) return;
        spec.toggleDanmaku(item);
        notifyDanmakuSourceChanged();
    }

    public void addDanmaku(Danmaku item) {
        if (spec != null) spec.addDanmaku(item);
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!TextUtils.isEmpty(from)) Notify.show(ResUtil.getString(R.string.parse_from, from));
        if (headers != null) headers.remove(HttpHeaders.RANGE);
        if (spec != null) spec.setHeaders(headers);
        if (spec != null) spec.setUrl(url);
        startCurrent(pendingStartPositionMs);
        pendingStartPositionMs = C.TIME_UNSET;
    }

    @Override
    public void onParseError() {
        pendingStartPositionMs = C.TIME_UNSET;
        callback.onError(ResUtil.getString(R.string.error_play_parse));
    }

    public interface Callback {

        void onPrepare();

        void onTracksChanged();

        void onDecodeChanged();

        void onMediaOptionsChanged();

        void onError(String msg);

        void onPlayerRebuild(Player newPlayer);

        void onDanmakuSourceChanged(Uri uri);

        void onDanmakuConfigChanged(DanmakuConfig config);

        void onDanmakuEnabledChanged(boolean enabled);

        void onDanmakuSent(String text);
    }

    private record PendingPreload(PlaySpec spec, long startPositionMs) {
    }

    // 2026-10-09: the audio/text offsets used to be part of this snapshot, read off Player
    // via getAudioOffsetMs()/getTextOffsetMs() (fork-only methods). They no longer need to
    // be captured: they now live on PlayerManager itself and a player rebuild cannot
    // change them, so dropping them from the record loses nothing. Everything still
    // captured here is stock Media3.
    private record PlaybackSnapshot(long positionMs, boolean playWhenReady, PlaybackParameters playbackParameters, int repeatMode, float volume) {

        private static PlaybackSnapshot capture(Player player) {
            float volume = player.isCommandAvailable(Player.COMMAND_GET_VOLUME) ? player.getVolume() : Float.NaN;
            return new PlaybackSnapshot(player.getCurrentPosition(), player.getPlayWhenReady(), player.getPlaybackParameters(), player.getRepeatMode(), volume);
        }

        private void restore(Player player) {
            if (player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) player.setPlaybackParameters(playbackParameters);
            if (player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) player.setRepeatMode(repeatMode);
            if (!Float.isNaN(volume) && player.isCommandAvailable(Player.COMMAND_SET_VOLUME)) player.setVolume(volume);
            if (player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) player.setPlayWhenReady(playWhenReady);
        }
    }

    private final Player.Listener listener = new Player.Listener() {

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) App.removeCallbacks(runnable);
            if (state == Player.STATE_READY) startPreloadIfReady();
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize size) {
            videoSize = size;
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            if (tracks.isEmpty() || initTrack) return;
            setTrack(Track.find(getKey()));
            callback.onTracksChanged();
            initTrack = true;
        }

        // 2026-10-09: removed onMediaChaptersChanged / onMediaEditionsChanged. They were
        // @Override of Player.Listener callbacks added by FongMi's Media3 fork; official
        // Player.Listener (35 methods in media3-common 1.8.0) declares neither. They can
        // never fire now anyway, since getCurrentMediaChapters()/getCurrentMediaEditions()
        // return empty lists -- which is what makes haveChapter()/haveEdition() false and
        // keeps the media-options row hidden.
        @Override
        public void onPlayerError(@NonNull PlaybackException e) {
            if (spec == null) return;
            PlayerEngine.ErrorAction action = engine.handleError(e);
            if (action != PlayerEngine.ErrorAction.RECOVERED) App.removeCallbacks(runnable);
            switch (action) {
                case DECODE -> handleDecodeError(e);
                case RECOVERED -> notifyDanmakuSourceChanged();
                case FATAL -> callback.onError(engine.getErrorMessage(e));
            }
        }
    };
}
