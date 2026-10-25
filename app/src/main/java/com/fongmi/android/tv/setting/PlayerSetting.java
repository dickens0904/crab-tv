package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

public class PlayerSetting {

    // 2026-10-10 tri-engine: three values, EXO stays 0 so every existing install
    // keeps the ExoPlayer engine it already had. IJK=1 / MPV=2 are appended; the
    // stored value is clamped on read, so a 1 written by the old two-value build
    // ("MPV") would now read as IJK -- that is the documented migration and the
    // only behavioural change for upgraders, both engines resolve independently.
    public static final int ENGINE_EXO = 0;
    public static final int ENGINE_IJK = 1;
    public static final int ENGINE_MPV = 2;
    public static final int MIN_ENGINE = ENGINE_EXO;
    public static final int MAX_ENGINE = ENGINE_MPV;
    public static final int ENGINE_COUNT = MAX_ENGINE - MIN_ENGINE + 1;
    public static final int RENDER_SURFACE = 0;
    public static final int RENDER_TEXTURE = 1;
    public static final int MIN_SCALE = 0;
    public static final int MAX_SCALE = 4;
    private static final int MIN_SIZE = 0;
    private static final int MAX_SIZE = 3;
    private static final int MIN_BUFFER = 1;
    private static final int MAX_BUFFER = 10;
    private static final int MIN_BACKGROUND = 0;
    private static final int MAX_BACKGROUND = 2;

    public static int getEngine() {
        return Math.clamp(Prefers.getInt("player_engine", ENGINE_EXO), MIN_ENGINE, MAX_ENGINE);
    }

    public static void putEngine(int engine) {
        Prefers.put("player_engine", Math.clamp(engine, MIN_ENGINE, MAX_ENGINE));
        if (isExo() && DecodeSetting.isTunnel()) putRender(RENDER_SURFACE);
    }

    public static boolean isExo() {
        return getEngine() == ENGINE_EXO;
    }

    public static boolean isIjk() {
        return getEngine() == ENGINE_IJK;
    }

    public static boolean isMpv() {
        return getEngine() == ENGINE_MPV;
    }

    /**
     * Only the mpv engine owns the mpv-only rows (vulkan / gpu-next / mpv.conf) on the
     * player settings screen, and only ExoPlayer owns the tunnel decode row. IJK sits
     * between the two: it gets neither, but it is still a non-Exo engine, so those
     * call sites ask this instead of {@code !isExo()}.
     */
    public static boolean isMpvOnly() {
        return isMpv();
    }

    public static boolean isDebug() {
        return Prefers.getBoolean("player_debug");
    }

    public static void putDebug(boolean debug) {
        Prefers.put("player_debug", debug);
    }

    public static boolean isMpvGpuNext() {
        return Prefers.getBoolean("mpv_gpu_next");
    }

    public static void putMpvGpuNext(boolean gpuNext) {
        Prefers.put("mpv_gpu_next", gpuNext);
    }

    public static boolean isMpvVulkan() {
        return Prefers.getBoolean("mpv_vulkan");
    }

    public static void putMpvVulkan(boolean vulkan) {
        Prefers.put("mpv_vulkan", vulkan);
    }

    public static int getRender() {
        return Math.clamp(Prefers.getInt("render", RENDER_SURFACE), RENDER_SURFACE, RENDER_TEXTURE);
    }

    public static void putRender(int render) {
        Prefers.put("render", Math.clamp(render, RENDER_SURFACE, RENDER_TEXTURE));
        if (isExo() && DecodeSetting.isTunnel() && getRender() == RENDER_TEXTURE) DecodeSetting.putTunnel(false);
    }

    public static int getSize() {
        return Math.clamp(Prefers.getInt("size", 2), MIN_SIZE, MAX_SIZE);
    }

    public static void putSize(int size) {
        Prefers.put("size", Math.clamp(size, MIN_SIZE, MAX_SIZE));
    }

    public static int getScale() {
        return Math.clamp(Prefers.getInt("scale"), MIN_SCALE, MAX_SCALE);
    }

    public static void putScale(int scale) {
        Prefers.put("scale", Math.clamp(scale, MIN_SCALE, MAX_SCALE));
    }

    public static int getBackground() {
        return Math.clamp(Prefers.getInt("background", 2), MIN_BACKGROUND, MAX_BACKGROUND);
    }

    public static void putBackground(int background) {
        Prefers.put("background", Math.clamp(background, MIN_BACKGROUND, MAX_BACKGROUND));
    }

    public static boolean isBackgroundOff() {
        return getBackground() == 0;
    }

    public static boolean isBackgroundOn() {
        return getBackground() == 1 || getBackground() == 2;
    }

    public static boolean isBackgroundPiP() {
        return getBackground() == 2;
    }

    public static int getBuffer() {
        return Math.clamp(Prefers.getInt("buffer"), MIN_BUFFER, MAX_BUFFER);
    }

    public static void putBuffer(int buffer) {
        Prefers.put("buffer", Math.clamp(buffer, MIN_BUFFER, MAX_BUFFER));
    }
}
