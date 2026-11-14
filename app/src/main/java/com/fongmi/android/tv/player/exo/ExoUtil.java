package com.fongmi.android.tv.player.exo;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.trackselection.DecodeTrackSelector;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.util.EventLogger;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.track.LangUtil;
import com.fongmi.android.tv.setting.DecodeSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.SpeedSetting;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class ExoUtil {

    public static ExoPlayer buildPlayer(Player.Listener listener, DefaultPreloadManager.Builder preloadManagerBuilder) {
        ExoPlayer.Builder playerBuilder = new ExoPlayer.Builder(App.get()).setSkipSilenceEnabled(SpeedSetting.isSkipSilence());
        ExoPlayer player = preloadManagerBuilder.buildExoPlayer(playerBuilder);
        if (BuildConfig.DEBUG) player.addAnalyticsListener(new EventLogger());
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        player.setPlayWhenReady(true);
        player.addListener(listener);
        return player;
    }

    public static String getMimeType(int errorCode) {
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED || errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED || errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) return MimeTypes.APPLICATION_M3U8;
        // 2026-10-09: MimeTypes.APPLICATION_OCTET_STREAM is fork-only. The generic
        // application/* base type that official media3-common 1.8.0 does expose is the
        // closest match for "manifest we could not parse".
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED || errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) return MimeTypes.BASE_TYPE_APPLICATION;
        return null;
    }

    static LoadControl buildLoadControl(int maxPreloadBufferBytes) {
        int buffer = PlayerSetting.getBuffer();
        // 2026-10-09: setPlayerTargetBufferBytes(PlayerId, long) is fork-only, used to size
        // the buffer per player instance. Official 1.8.0 DefaultLoadControl.Builder has the
        // single-player setTargetBufferBytes(int), so the preload cap is applied there. The
        // other three durations keep the PlayerSetting.getBuffer() multiplier untouched.
        return new DefaultLoadControl.Builder().setBufferDurationsMs(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * buffer, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * buffer, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS).setTargetBufferBytes(maxPreloadBufferBytes).build();
    }

    public static Map<String, String> extractHeaders(MediaItem item) {
        Bundle extras = item.requestMetadata.extras;
        if (extras == null) return new HashMap<>();
        return extras.keySet().stream().filter(key -> extras.getString(key) != null).collect(Collectors.toMap(key -> key, extras::getString));
    }

    static DecodeTrackSelector buildTrackSelector(int decode) {
        DecodeTrackSelector trackSelector = new DecodeTrackSelector(App.get());
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
        if (DecodeSetting.isPreferAAC()) builder.setPreferredAudioMimeType(MimeTypes.AUDIO_AAC);
        builder.setPreferredTextLanguages(LangUtil.getPreferredTextLanguages());
        builder.setTunnelingEnabled(DecodeSetting.isTunnelingEnabled());
        builder.setForceHighestSupportedBitrate(true);
        trackSelector.setParameters(builder.build());
        setDecodePreferences(trackSelector, decode);
        return trackSelector;
    }

    static void setDecodePreferences(DecodeTrackSelector trackSelector, int decode) {
        int audioDecode = isAudioSoftwareDecode(decode) ? PlayerEngine.SOFT : PlayerEngine.HARD;
        int videoDecode = isVideoSoftwareDecode(decode) ? PlayerEngine.SOFT : PlayerEngine.HARD;
        trackSelector.setRendererDecodePreferences(audioDecode, videoDecode);
    }

    private static boolean isAudioSoftwareDecode(int decode) {
        return decode == PlayerEngine.SOFT && DecodeSetting.isAudioPrefer();
    }

    private static boolean isVideoSoftwareDecode(int decode) {
        return decode == PlayerEngine.SOFT && DecodeSetting.isVideoPrefer();
    }

    static RenderersFactory buildRenderersFactory() {
        return buildRenderersFactory(new AudioProcessor[0]);
    }

    static RenderersFactory buildRenderersFactory(AudioProcessor[] audioProcessors) {
        DefaultRenderersFactory factory = new DefaultRenderersFactory(App.get()) {
            @Override
            protected AudioSink buildAudioSink(@NonNull Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams) {
                return ExoUtil.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams, audioProcessors);
            }
        };
        // 2026-10-09: setDolbyVisionOutputPolicy(...) exists only in FongMi's Media3
        // fork (verified absent from media3-exoplayer 1.8.0's DefaultRenderersFactory).
        // The setting stays readable and cycleable in the UI, but the platform decides
        // how a Dolby Vision track is mapped to the display. Every other knob below is
        // stock Media3 and is passed through unchanged.
        return factory.setEnableDecoderFallback(true).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
    }

    private static AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams, AudioProcessor[] audioProcessors) {
        DefaultAudioSink.Builder builder = new DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput).setEnableAudioTrackPlaybackParams(enableAudioOutputPlaybackParams);
        // 2026-10-09: upstream called setAudioOutputProvider(new AudioTrackAudioOutputProvider
        // .Builder(null).build()) to force passthrough off when the setting is disabled.
        // That type and that setter are fork-only (absent from media3-exoplayer 1.8.0),
        // so the passthrough switch is dropped; passthrough stays at Media3's default,
        // which decides on its own from the device's reported audio capabilities.
        // 多声道直喂 AudioTrack 前必须先降混，否则 2.0 输出的盒子会静音或建轨失败。
        if (audioProcessors != null && audioProcessors.length > 0) builder.setAudioProcessors(audioProcessors);
        return builder.build();
    }
}
