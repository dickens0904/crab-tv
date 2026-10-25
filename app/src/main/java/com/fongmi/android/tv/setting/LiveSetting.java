package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

public class LiveSetting {

    public static final String SELF_ENDPOINT_URL = "http://192.168.31.29:8099/merged.m3u";

    public static boolean isSelfEndpoint() {
        return Prefers.getBoolean("self_endpoint");
    }

    public static void putSelfEndpoint(boolean selfEndpoint) {
        Prefers.put("self_endpoint", selfEndpoint);
    }

    public static String getSelfUrl() {
        String url = Prefers.getString("self_endpoint_url", "");
        return url.isEmpty() ? SELF_ENDPOINT_URL : url;
    }

    public static void putSelfUrl(String url) {
        Prefers.put("self_endpoint_url", url);
    }

    public static boolean isBoot() {
        return Prefers.getBoolean("boot_live");
    }

    public static void putBoot(boolean boot) {
        Prefers.put("boot_live", boot);
    }

    public static boolean isAcross() {
        return Prefers.getBoolean("across", true);
    }

    public static void putAcross(boolean across) {
        Prefers.put("across", across);
    }

    public static boolean isChange() {
        return Prefers.getBoolean("change", true);
    }

    public static void putChange(boolean change) {
        Prefers.put("change", change);
    }

    public static boolean isInvert() {
        return Prefers.getBoolean("invert");
    }

    public static void putInvert(boolean invert) {
        Prefers.put("invert", invert);
    }

    public static int getScale() {
        return Math.clamp(Prefers.getInt("scale_live", PlayerSetting.getScale()), PlayerSetting.MIN_SCALE, PlayerSetting.MAX_SCALE);
    }

    public static void putScale(int scale) {
        Prefers.put("scale_live", Math.clamp(scale, PlayerSetting.MIN_SCALE, PlayerSetting.MAX_SCALE));
    }
}
