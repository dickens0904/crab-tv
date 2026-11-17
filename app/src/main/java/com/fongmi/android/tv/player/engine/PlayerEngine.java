package com.fongmi.android.tv.player.engine;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.trackselection.DecodeTrackSelector;

import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.effect.PlayerEffect;
import com.fongmi.android.tv.player.media.PlaySpec;

public interface PlayerEngine {

    // 2026-10-09: upstream referenced androidx.media3.common.C.DECODE_SOFTWARE /
    // DECODE_HARDWARE, constants that exist only in FongMi's Media3 fork (verified
    // absent from media3-common 1.8.0). The values are defined once in
    // DecodeTrackSelector, which is where the fork's decode preference actually
    // lands. Only equality is ever tested against them, so any distinct pair works.
    int SOFT = DecodeTrackSelector.DECODE_SOFTWARE;
    int HARD = DecodeTrackSelector.DECODE_HARDWARE;

    Type getType();

    Player getPlayer();

    int getAudioChannelCount();

    void release();

    void setDecode(int decode);

    default PlayerEffect getEffect() {
        return PlayerEffect.NONE;
    }

    void start(PlaySpec spec, long startPositionMs);

    default void preload(PlaySpec spec, long startPositionMs) {
    }

    default void clearPreload() {
    }

    void stop();

    default void setSubtitleStyle() {
    }

    default boolean addSubtitle(Sub sub) {
        return false;
    }

    String getErrorMessage(PlaybackException e);

    ErrorAction handleError(PlaybackException e);

    enum ErrorAction {
        RECOVERED,
        DECODE,
        FATAL
    }

    // 2026-10-10 tri-engine: IJK joins EXO/MPV. Enum ordinals are NOT persisted --
    // PlayerSetting maps them to ENGINE_EXO/ENGINE_IJK/ENGINE_MPV explicitly, so
    // reordering this list is safe.
    enum Type {
        EXO,
        IJK,
        MPV
    }
}
