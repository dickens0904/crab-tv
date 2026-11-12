package com.fongmi.android.tv.player;

import android.util.Log;
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
import com.fongmi.android.tv.player.ijk.IjkPlayerEngine;
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
    // 2026-10-25: 同一路流只自动回退一次引擎（防止循环）
    private boolean audioFallbackTried;
    // 2026-11-08: 会话级引擎覆盖。自动无音轨回退只影响当前这路流，
    // 绝不写 PlayerSetting（一次偶发 mp2 频道不该永久改掉用户的引擎偏好）。
    // 新流入口 start()/parse() 清空；回退重播 startCurrent() 保留，避免死循环。
    // 2026-11-12: 引擎口径统一后不再使用「会话级引擎覆盖」(sessionEngine)。
    // 所有引擎切换一律写回 PlayerSetting，偏好即实际引擎，换源也自然延续。
    private int retry;
    private int decode;
    // Default 0, matching what the old Player-backed getters returned when the command
    // was unavailable -- the setting panels display these directly as a number.
    private long audioOffsetMs;
    private long textOffsetMs;
    // 2026-11-12 修正：每个源/线路给满 TIMEOUT_PLAY(15s) 的"有效尝试时间"。
    // 起点 startMs 在 setMediaItem 设置；FATAL 时**不再瘫痪干等**（引擎报错后已停止，
    // 不重启就永远播不出来），而是在 15s 宽限内节流重启当前源继续拉流，
    // 直到绝对 15s 截止才允许换源。冷启动 CDN（如 jlm3u8 两级 m3u8）常需数秒才吐数据。
    private long startMs;
    private long lastFatalRetryMs;
    private int fatalRetryCount;
    private boolean exoDowngradeTried;
    private static final long FATAL_RETRY_INTERVAL_MS = 1200L;
    private static final int FATAL_RETRY_MAX = 3;
    private final Runnable fatalRetry = this::retryFatalSource;

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
        // 2026-11-12: 引擎口径统一 —— 用户手动选择也走同一个入口，
        // 保证「偏好」与「实际运行引擎」永远是同一个值。
        applyEngine(targetEngine);
    }

    /**
     * 引擎切换的唯一入口（2026-11-12 用户要求：引擎口径统一，任何界面都按同一标准）。
     *
     * <p>旧实现里自动切换（无音轨回退 IJK、IJK 打不开流降级 EXO）只改 sessionEngine，
     * 不写偏好，导致：设置页显示 IJK、播放页显示 EXO；且换源时 start() 清掉
     * sessionEngine 后又退回偏好引擎。现在所有切换一律写回 PlayerSetting，
     * 于是 ① 设置页/播放页/引擎对话框读数一致；② 换源后 resolve() 读到的
     * 就是当前引擎，自动延续；③ 用户手动选择同样语义。
     */
    private void applyEngine(int targetEngine) {
        PlayerSetting.putEngine(targetEngine);
        // 引擎不重建时也要把「实际」校准回偏好：例如 DRM/smb 被 requiresExo 强制成
        // EXO，用户选 IJK 也无法真正生效，此时偏好应显示 EXO 而不是留一个假值。
        if (isEmpty() || PlayerEngineFactory.matches(engine, spec)) {
            if (!isEmpty()) syncEnginePreference();
            return;
        }
        PlaybackSnapshot snapshot = PlaybackSnapshot.capture(player);
        startCurrent(snapshot.positionMs());
        snapshot.restore(player);
    }

    /**
     * 流里有画面没声音（多半 AC-3）→ 切 IJK 引擎重播当前流。
     *
     * <p>2026-10-25: media3 遇到解不了的音轨不会报错，只会"有画面无声音"，
     * 所以原来 DECODE 那条自动回退路径永远不触发，只能在这里主动识别。
     *
     * <p>2026-11-12: 改为走 {@link #applyEngine(int)} 写回偏好。原本是 session-only
     * （怕单条流改变全局），但这会造成设置页与播放页引擎显示不一致、换源后又跳回去。
     * 用户明确要求「引擎口径统一，任何操作界面都按这个标准」，故统一持久化。
     */
    private void handleMissingAudio() {
        if (audioFallbackTried) return;
        if (getEngine() == PlayerSetting.ENGINE_IJK) return;
        if (!IjkPlayerEngine.isAvailable()) return;
        audioFallbackTried = true;
        Log.d("TV-Audio", "no audio track (likely MPEG/AC-3 audio) -> switch to IJK engine");
        Notify.show("无音轨：已切换到 IJK 解码");
        applyEngine(PlayerSetting.ENGINE_IJK);
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
        App.removeCallbacks(fatalRetry);
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
        App.removeCallbacks(fatalRetry);
        retry = 0;
        fatalRetryCount = 0;
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
        android.util.Log.w("PM-FATAL", "onPlayTimeout -> fallback (15s grace used up)");
        callback.onError(ResUtil.getString(R.string.error_play_timeout));
        stop();
    }

    /**
     * 15s 宽限期内的"当前源重启"：直接让引擎重新拉同一个 spec，
     * 不重置 startMs、不重排 15s 截止计时器（否则宽限期会被无限续命）。
     * 冷启动 CDN / 两级 m3u8 首次常需数秒才吐数据，重启一次往往就能起播；
     * 真到 15s 仍不可用则由 runnable(onPlayTimeout) 统一换源。
     */
    private void retryFatalSource() {
        if (spec == null || spec.getUrl() == null) return;
        if (SystemClock.elapsedRealtime() - startMs >= Constant.TIMEOUT_PLAY) return;
        android.util.Log.w("PM-FATAL", "retry current source #" + fatalRetryCount
                + " url=" + spec.getUrl());
        try {
            initTrack = false;
            engine.start(spec, C.TIME_UNSET);
            notifyDanmakuSourceChanged();
            callback.onPrepare();
        } catch (Throwable t) {
            android.util.Log.w("PM-FATAL", "retry failed: " + t);
        }
    }

    private void ensureEngine(PlaySpec spec) {
        if (PlayerEngineFactory.matches(engine, spec)) return;
        PlayerEngine old = engine;
        player.removeListener(listener);
        engine = PlayerEngineFactory.create(decode, spec, listener);
        setPlayer(engine.getPlayer());
        old.release();
        syncEnginePreference();
    }

    /**
     * 2026-11-12 引擎口径统一：引擎真正建好后，把「实际在跑的引擎」写回 PlayerSetting。
     * 覆盖 applyEngine 之外的旁路：DRM/smb 被 requiresExo 强制成 EXO、构造失败降级 EXO。
     * 这样设置页、播放页按钮、引擎对话框读到的永远是同一个值。
     */
    private void syncEnginePreference() {
        int actual = switch (engine.getType()) {
            case MPV -> PlayerSetting.ENGINE_MPV;
            case IJK -> PlayerSetting.ENGINE_IJK;
            default -> PlayerSetting.ENGINE_EXO;
        };
        if (PlayerSetting.getEngine() != actual) PlayerSetting.putEngine(actual);
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
        // A fresh stream (new channel / new line / new episode): allow one audio
        // fallback again. 引擎不在这里重置 —— 2026-11-12 起引擎口径以 PlayerSetting
        // 为唯一真相源，换源/换线路时 resolve() 直接沿用它（用户要求：延续原有引擎）。
        audioFallbackTried = false;
        // 每个全新的源/线路都允许重新尝试一次 IJK→EXO 降级；
        // 注意不要放在 setMediaItem 里，否则降级引发的重启会被当成新源而无限循环。
        exoDowngradeTried = false;
        setMediaItem(timeout, startPositionMs);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata) {
        parse(key, result, useParse, metadata, C.TIME_UNSET);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata, long startPositionMs) {
        stopParse();
        // Fresh VOD stream: same reset as start(), so a parsed mp2 source can still
        // trigger one audio fallback after the previous channel did. 引擎沿用 PlayerSetting。
        audioFallbackTried = false;
        exoDowngradeTried = false;
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
        App.removeCallbacks(fatalRetry);
        fatalRetryCount = 0;
        lastFatalRetryMs = 0;
        ensureEngine(spec.checkUa());
        pendingPreload = null;
        initTrack = false;
        engine.start(spec, startPositionMs);
        notifyDanmakuSourceChanged();
        startMs = SystemClock.elapsedRealtime();
        android.util.Log.w("PM-START", "start url=" + spec.getUrl() + " fmt=" + spec.getFormat());
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
            android.util.Log.w("PM-STATE", "state=" + state + " url=" + (spec == null ? "null" : spec.getUrl()));
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                App.removeCallbacks(runnable);
                App.removeCallbacks(fatalRetry);
            }
            if (state == Player.STATE_READY) startPreloadIfReady();
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize size) {
            videoSize = size;
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            // 2026-10-25: 有视频轨却没有可用音轨 —— 多半是 AC-3/E-AC-3（media3 不解，
            // 且不会报错，只是静默无声）。这时自动切到 IJK 引擎重播当前流（IJK 能解 AC-3）。
            if (!tracks.isEmpty() && tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                    && !tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) {
                handleMissingAudio();
            }
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
            if (action == PlayerEngine.ErrorAction.DECODE) {
                handleDecodeError(e);
                return;
            }
            if (action == PlayerEngine.ErrorAction.RECOVERED) {
                notifyDanmakuSourceChanged();
                return;
            }
            // FATAL：不立即换源，也**不再瘫痪干等**。2026-11-12 修正：
            // 引擎报错后已停止，若不重启当前源，黑屏干等到 15s 只会让用户"任何内容都播不出来"。
            // 现在保留 setMediaItem 排程的绝对 15s 截止计时器(runnable)，期间节流重启
            // 当前源继续拉流（冷启动 CDN 常需数秒才吐数据）；真到 15s 仍起不来才换源。
            long elapsed = SystemClock.elapsedRealtime() - startMs;
            android.util.Log.w("PM-FATAL", "code=" + e.errorCode + " msg=" + e.getMessage()
                    + " url=" + spec.getUrl()
                    + " elapsed=" + elapsed + " retry=" + fatalRetryCount);
            if (elapsed >= Constant.TIMEOUT_PLAY) {
                android.util.Log.w("PM-FATAL", "grace expired -> fallback");
                callback.onError(engine.getErrorMessage(e));
                return;
            }
            // 2026-11-12: IJK 的 ffmpeg 打不开很多 HTTPS HLS master playlist，几乎瞬间
            // 报 what=-10000。重试同一个引擎毫无意义 —— 降级到 EXO（media3，官方默认
            // 且支持 HLS）重播当前源。通过 applyEngine 写回偏好：这样换源/换线路后
            // resolve() 读到的就是 EXO，引擎自动延续；
            // 同时设置页、播放页、引擎对话框显示的引擎完全一致。
            if (!exoDowngradeTried && engine.getType() == PlayerEngine.Type.IJK) {
                exoDowngradeTried = true;
                android.util.Log.w("PM-FATAL", "IJK cannot open stream -> downgrade to EXO");
                Notify.show("IJK 无法播放该源，已自动切换到 EXO 内核");
                applyEngine(PlayerSetting.ENGINE_EXO);
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (fatalRetryCount < FATAL_RETRY_MAX && now - lastFatalRetryMs >= FATAL_RETRY_INTERVAL_MS) {
                lastFatalRetryMs = now;
                ++fatalRetryCount;
                App.post(fatalRetry, FATAL_RETRY_INTERVAL_MS);
            }
        }
    };
}
