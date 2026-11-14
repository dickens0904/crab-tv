package com.fongmi.android.tv.player.exo;

import androidx.media3.common.Format;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.ExoPlayer;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.effect.PlayerEffect;
import com.fongmi.android.tv.player.effect.audio.AudioEffectBands;
import com.fongmi.android.tv.player.effect.audio.AudioEffectConfig;
import com.fongmi.android.tv.player.effect.audio.DownmixAudioProcessor;
import com.fongmi.android.tv.player.effect.audio.ExoAudioEffectController;
import com.fongmi.android.tv.player.effect.video.ExoVideoEffectController;
import com.fongmi.android.tv.player.effect.video.VideoEffectProfile;
import com.fongmi.android.tv.setting.AudioSetting;
import com.fongmi.android.tv.setting.VideoSetting;

public final class ExoPlayerEffect implements PlayerEffect {

    private final ExoAudioEffectController audioEffectController;
    private final ExoVideoEffectController videoEffectController;

    // 多声道 -> 立体声真降混。独立于上面的音效处理链，作为后级 AudioProcessor 串在其后：
    // 先做 DSP（EQ/平衡/声道模式），再把多声道折成 2ch 交给 AudioTrack。
    // 这样两条降混路径（ExoPlayer 的 AudioProcessor、IJK 的 ffmpeg 滤镜）互不干扰。
    private final DownmixAudioProcessor downmixProcessor;

    private boolean previewVideoEffect;
    private boolean previewAudioEffect;
    private boolean audioEffectFailed;
    private ExoPlayer player;

    public ExoPlayerEffect() {
        this.audioEffectController = new ExoAudioEffectController();
        this.videoEffectController = new ExoVideoEffectController();
        this.downmixProcessor = new DownmixAudioProcessor(AudioSetting::getDownmixMode);
    }

    /**
     * 音效处理 + 降混，按顺序串成 AudioProcessor 链。
     *
     * @return 供 DefaultAudioSink 使用的处理器数组；降混关闭时只剩音效处理。
     */
    public AudioProcessor[] getAudioProcessors() {
        AudioProcessor effect = audioEffectController.getProcessor();
        AudioProcessor downmix = AudioSetting.isDownmixEnabled() ? downmixProcessor : null;
        if (downmix == null) return new AudioProcessor[]{effect};
        return new AudioProcessor[]{effect, downmix};
    }

    public AudioProcessor getAudioProcessor() {
        return audioEffectController.getProcessor();
    }

    public void setPlayer(ExoPlayer player) {
        this.player = player;
    }

    public void release() {
        audioEffectController.release();
    }

    // 2026-10-09: replaced three fork-only queries on ExoPlayer with official equivalents.
//   ExoPlayer.getVideoEffectsSupport()  + VIDEO_EFFECTS_* constants  -> derived below
//   ExoPlayer.getAudioProcessingSupport() + AUDIO_PROCESSING_* constants -> derived below
//   ExoPlayer.isSkipSilenceSupported()  -> getSkipSilenceEnabled()
// None of the first two exist in official media3-exoplayer 1.8.0: they were added by
// FongMi's Media3 fork, whose patched renderers could report exactly why an effect was
// refused. Official ExoPlayer exposes no such query, so the two "support" tests below
// substitute the closest real signal -- is a video / audio renderer actually attached --
// and the specific DRM / decode / tunneling error branches collapse to the generic
// "unsupported" message. The underlying machinery still works: setVideoEffects and
// setAudioProcessors are stock Media3, so effects are applied for real when enabled.
private boolean hasVideoRenderer() {
    return player != null && player.getVideoFormat() != null && player.getVideoSize().width != Format.NO_VALUE;
}

private boolean hasAudioRenderer() {
    return player != null && player.getAudioFormat() != null;
}

@Override
    public boolean supportsVideoEffect() {
        return hasVideoRenderer();
    }

    @Override
    public int getVideoEffectError() {
        return hasVideoRenderer() ? 0 : R.string.error_video_effect_unsupported;
    }

    @Override
    public void applyVideoEffect() {
        if (supportsVideoEffect() && (previewVideoEffect || VideoSetting.isEnabled())) videoEffectController.apply(player, getVideoProfile());
        else videoEffectController.clear(player);
    }

    @Override
    public void previewVideoEffect(boolean original) {
        if (previewVideoEffect == original) return;
        previewVideoEffect = original;
        applyVideoEffect();
    }

    @Override
    public boolean supportsAudioEffect() {
        return hasAudioRenderer() && !audioEffectFailed;
    }

    @Override
    public AudioEffectBands getAudioEffectBands() {
        return AudioEffectBands.STANDARD;
    }

    @Override
    public int getAudioEffectError() {
        if (!hasAudioRenderer()) return R.string.error_audio_effect_unsupported;
        return audioEffectFailed ? R.string.error_audio_effect_apply : 0;
    }

    @Override
    public void applyAudioEffect() {
        if (hasAudioRenderer()) applyAudioConfig(getAudioChannelCount());
        else clearAudioEffect();
    }

    public void clearAudioEffect() {
        audioEffectController.release();
        audioEffectFailed = false;
    }

    @Override
    public void previewAudioEffect(boolean original) {
        if (previewAudioEffect == original) return;
        previewAudioEffect = original;
        applyAudioEffect();
    }

    // 2026-10-09: isSkipSilenceSupported() was fork-only. Official ExoPlayer reports the
    // current state via getSkipSilenceEnabled(); there is no separate capability query,
        // so the toggle follows the player and setSkipSilenceEnabled stays a pass-through.
        @Override
        public boolean supportsSkipSilence() {
            return player.getSkipSilenceEnabled();
        }

    @Override
    public void setSkipSilenceEnabled(boolean enabled) {
        player.setSkipSilenceEnabled(enabled);
    }

    private VideoEffectProfile getVideoProfile() {
        return previewVideoEffect ? VideoEffectProfile.off() : VideoSetting.getAppliedProfile();
    }

    private AudioEffectConfig getAudioConfig(int channelCount) {
        return previewAudioEffect ? AudioEffectConfig.disabled() : AudioEffectConfig.from(getAudioEffectBands(), channelCount);
    }

    private int getAudioChannelCount() {
        Format format = player.getAudioFormat();
        return format == null ? Format.NO_VALUE : format.channelCount;
    }

    private void applyAudioConfig(int channelCount) {
        if (channelCount == Format.NO_VALUE) return;
        AudioEffectConfig config = getAudioConfig(channelCount);
        audioEffectFailed = !audioEffectController.apply(player, config);
    }
}
