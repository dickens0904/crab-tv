package com.fongmi.android.tv.player.engine;

import static com.fongmi.android.tv.player.engine.PlayerEngine.Type.EXO;
import static com.fongmi.android.tv.player.engine.PlayerEngine.Type.IJK;
import static com.fongmi.android.tv.player.engine.PlayerEngine.Type.MPV;

import androidx.media3.common.Player;

import com.fongmi.android.tv.player.exo.ExoPlayerEngine;
import com.fongmi.android.tv.player.ijk.IjkPlayerEngine;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.mpv.MpvPlayerEngine;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.UrlUtil;

public final class PlayerEngineFactory {

    public static PlayerEngine create(int decode, Player.Listener listener) {
        return create(decode, resolve(), listener);
    }

    public static PlayerEngine create(int decode, PlaySpec spec, Player.Listener listener) {
        return create(decode, resolve(spec), listener);
    }

    public static PlayerEngine create(int decode, PlayerEngine.Type type, Player.Listener listener) {
        // Red line: ExoPlayer is both the default and the floor. Every branch below is
        // wrapped so a constructor that throws on this device (missing ABI slice, dlopen
        // failure, ...) degrades to ExoPlayer instead of taking the process down.
        try {
            return switch (type) {
                case EXO -> new ExoPlayerEngine(decode, listener);
                case IJK -> new IjkPlayerEngine(decode, listener);
                case MPV -> new MpvPlayerEngine(decode, listener);
            };
        } catch (Throwable e) {
            return onEngineFailed(type, decode, listener, e);
        }
    }

    private static PlayerEngine onEngineFailed(PlayerEngine.Type type, int decode, Player.Listener listener, Throwable e) {
        // Even the floor can throw, but there is nothing below it to fall to, so that
        // one is allowed to propagate -- it is a real ExoPlayer breakage, not an
        // engine-selection problem.
        if (type == EXO) throw asRuntime(e);
        return new ExoPlayerEngine(decode, listener);
    }

    private static RuntimeException asRuntime(Throwable e) {
        return e instanceof RuntimeException runtime ? runtime : new RuntimeException(e);
    }

    public static boolean matches(PlayerEngine engine, PlaySpec spec) {
        return engine != null && engine.getType() == resolve(spec);
    }

    /**
     * Which engine is actually playing. Reports the live engine's real type rather than
     * the preference, so the UI shows EXO when a fallback happened -- the red line is
     * "never claim a kernel that is not the one running".
     */
    public static PlayerEngine.Type getEngineType(PlayerEngine engine) {
        return engine == null ? EXO : engine.getType();
    }

    private static PlayerEngine.Type resolve(PlaySpec spec) {
        // DRM and smb:// only ever worked on ExoPlayer's extractors; keep forcing it.
        if (requiresExo(spec)) return EXO;
        return resolve();
    }

    private static PlayerEngine.Type resolve() {
        // Red line: the selection is a preference, not a promise. Each candidate is
        // probed and the first one that is actually loadable on this device wins;
        // anything unusable falls through to ExoPlayer.
        int want = PlayerSetting.getEngine();
        if (want == PlayerSetting.ENGINE_IJK && IjkPlayerEngine.isAvailable()) return IJK;
        if (want == PlayerSetting.ENGINE_MPV && MpvPlayerEngine.isAvailable()) return MPV;
        return EXO;
    }

    private static boolean requiresExo(PlaySpec spec) {
        return spec != null && (spec.getDrm() != null || "smb".equals(UrlUtil.scheme(spec.getUrl())));
    }

    public static boolean isMpvReady() {
        return PlayerSetting.isMpv() && MpvPlayerEngine.isAvailable();
    }

    public static boolean isIjkReady() {
        return PlayerSetting.isIjk() && IjkPlayerEngine.isAvailable();
    }
}