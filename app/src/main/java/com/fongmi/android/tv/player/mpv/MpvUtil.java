package com.fongmi.android.tv.player.mpv;

import android.content.pm.PackageManager;

import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.mpvplayer.MpvAndroidOptions;
import androidx.media3.mpvplayer.MpvPlayer;
import androidx.media3.mpvplayer.MpvPlayerConfig;
import androidx.media3.mpvplayer.MpvSubtitleOptions;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.track.LangUtil;
import com.fongmi.android.tv.setting.DecodeSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.setting.SubtitleSetting;
import com.github.catvod.utils.Path;

import java.io.File;

public final class MpvUtil {

    private static final String ASSET_CA_FILE = "cacert.pem";
    private static final int VULKAN_1_2 = 0x00402000;
    private static final double DEFAULT_SUB_POS = 100.0;
    private static final double DEFAULT_SUB_SCALE = 1.0;
    private static final double MIN_SUB_POS = 0.0;
    private static final double MAX_SUB_POS = 150.0;

    public static boolean isAvailable() {
        try {
            return MpvPlayer.isAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean isVulkanSupported() {
        return App.get().getPackageManager().hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_2);
    }

    /**
     * mpv can be put on a TextureView instead of a SurfaceView (see {@code mpv_pixel.conf},
     * key {@code surface=texture}). That only works if media3's PlayerView stops painting its
     * black shutter over the picture: the shutter normally sits above the SurfaceView, which
     * is hidden behind the hole the SurfaceView punches in the window, but a TextureView is
     * an ordinary child and would be covered by it. Called for the mpv engine only, so the
     * ExoPlayer / IJK surfaces are untouched.
     */
    public static void applySurfaceMode(androidx.media3.ui.PlayerView view, Player player) {
        if (view == null || !(player instanceof MpvPlayer mpv)) return;
        if (!mpv.isTextureSurface()) return;
        view.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT);
    }

    public static MpvPlayer buildPlayer(int decode, Player.Listener listener) {
        MpvPlayer player = new MpvPlayer.Builder(App.get()).setDecode(decode).setConfig(buildConfig()).build();
        setPreferredTextLanguages(player);
        player.addListener(listener);
        return player;
    }

    public static void setSubtitleStyle(MpvPlayer player) {
        player.setSubtitleOptions(buildSubtitleOptions());
    }

    private static MpvPlayerConfig buildConfig() {
        File configDir = Path.mpv();
        File cacheDir = Path.mpvCache();
        MpvPlayerConfig.Builder builder = new MpvPlayerConfig.Builder().addConfigDirectory(configDir).addAndroidFontConfig(configDir, cacheDir).addAndroidDefaults(buildAndroidOptions(cacheDir)).addTlsCaFileFromAsset(App.get(), ASSET_CA_FILE, Path.files(ASSET_CA_FILE)).addAndroidSubtitleOptions(App.get(), buildSubtitleOptions());
        addPreloadOptions(builder);
        return builder.build();
    }

    private static MpvAndroidOptions buildAndroidOptions(File shaderCacheDirectory) {
        MpvAndroidOptions.Builder builder = new MpvAndroidOptions.Builder().setShaderCacheDirectory(shaderCacheDirectory).setAudioPassthroughEnabled(DecodeSetting.isAudioPassThrough()).setDolbyVisionOutputPolicy(DecodeSetting.getDolbyVisionOutputPolicy());
        builder.setVulkanEnabled(isVulkanSupported() && PlayerSetting.isMpvVulkan());
        builder.setGpuNextEnabled(PlayerSetting.isMpvGpuNext());
        return builder.build();
    }

    private static void setPreferredTextLanguages(MpvPlayer player) {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setPreferredTextLanguages(LangUtil.getPreferredTextLanguages()).build());
    }

    private static void addPreloadOptions(MpvPlayerConfig.Builder builder) {
        if (!PreloadSetting.isEnabled()) return;
        builder.addDiskCacheOptions(Path.mpvCache(), PreloadSetting.getTimeSeconds());
    }

    private static MpvSubtitleOptions buildSubtitleOptions() {
        MpvSubtitleOptions.Builder builder = new MpvSubtitleOptions.Builder().setPosition(getSubtitlePosition()).setScale(getSubtitleScale()).setSecondarySubtitle(SubtitleSetting.getSecondaryTrackId(), SubtitleSetting.getSecondaryPosition(), SubtitleSetting.isStyleForced());
        if (SubtitleSetting.isCustomStyle()) builder.setCustomStyle(SubtitleSetting.getTextColor(), SubtitleSetting.getBackgroundColor(), SubtitleSetting.getEdgeType(), SubtitleSetting.getEdgeColor(), SubtitleSetting.getEdgeWidth(), SubtitleSetting.getShadow());
        else if (SubtitleSetting.isSystemStyle()) builder.setSystemCaptionStyle();
        return builder.build();
    }

    private static double getSubtitlePosition() {
        float position = SubtitleSetting.getPosition();
        if (!SubtitleSetting.isPositionSet()) return DEFAULT_SUB_POS;
        // 2026-10-09: Util here is androidx.media3.common.util.Util, whose constrainValue has
        // int / long / float overloads but no double one (verified against the 1.8.0
        // javap). DEFAULT_SUB_POS and friends are doubles, so the clamp is done with
        // Math.clamp, which the rest of this codebase already uses.
        return Math.clamp(DEFAULT_SUB_POS - position, MIN_SUB_POS, MAX_SUB_POS);
    }

    private static double getSubtitleScale() {
        if (!SubtitleSetting.isScaleApplied()) return DEFAULT_SUB_SCALE;
        return SubtitleSetting.getScale(App.get());
    }
}
