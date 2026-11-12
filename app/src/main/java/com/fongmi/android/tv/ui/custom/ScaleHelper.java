package com.fongmi.android.tv.ui.custom;

import android.view.View;

import androidx.media3.ui.PlayerView;

import java.util.WeakHashMap;

/**
 * 画面比例档位（select_scale：原始 / 16:9 / 4:3 / 填充 / 裁剪）。
 *
 * <p>2026-10-25: 原来各页面直接 {@code mBinding.player.setResizeMode(scale)}，把档位索引
 * 当成 media3 的 RESIZE_MODE 常量用 → 索引与语义完全错位：点"16:9"实际是裁剪(ZOOM)、
 * 点"4:3"实际是拉伸(FILL)、点"填充"变成按宽度缩放、"裁剪"变成按高度缩放。
 * media3 只提供 FIT/ZOOM/FILL/FIXED_WIDTH/FIXED_HEIGHT，没有 16:9/4:3 这种强制显示框。
 *
 * <p>现在按档位语义实现：
 * <ul>
 *   <li>原始 → FIT：保持源自身比例，不裁不拉</li>
 *   <li>16:9 / 4:3 → FIT + 强制显示框：用 padding 在播放器里挖出一个居中的
 *       16:9 / 4:3 盒子，源内容在盒内按原比例居中（不拉伸画面），其余留黑边</li>
 *   <li>填充 → FILL：拉伸铺满整个屏幕</li>
 *   <li>裁剪 → ZOOM：等比放大裁掉边缘铺满</li>
 * </ul>
 *
 * <p>实现只用公开 View API：media3 的 {@code PlayerView.setAspectRatio} 与
 * {@code AspectRatioFrameLayout.RESIZE_MODE_*} 常量都不是 public，引用不到。
 */
public class ScaleHelper {

    public static final int SCALE_ORIGINAL = 0;
    public static final int SCALE_16_9 = 1;
    public static final int SCALE_4_3 = 2;
    public static final int SCALE_FILL = 3;
    public static final int SCALE_CROP = 4;

    // media3 的 resize mode 取值（上游稳定：FIT=0 按比例适应 / ZOOM=1 等比放大裁边 / FILL=2 拉伸铺满）
    private static final int RESIZE_FIT = 0;
    private static final int RESIZE_ZOOM = 1;
    private static final int RESIZE_FILL = 2;

    private static final WeakHashMap<PlayerView, Float> RATIOS = new WeakHashMap<>();
    private static final WeakHashMap<PlayerView, View.OnLayoutChangeListener> LISTENERS = new WeakHashMap<>();

    /** 档位 → media3 resize mode。 */
    public static int resizeMode(int scale) {
        switch (scale) {
            case SCALE_FILL:
                return RESIZE_FILL;
            case SCALE_CROP:
                return RESIZE_ZOOM;
            default:
                return RESIZE_FIT;
        }
    }

    /** 强制显示框的宽高比（宽/高）；0 = 不限（跟随源自身比例）。 */
    public static float ratio(int scale) {
        switch (scale) {
            case SCALE_16_9:
                return 16f / 9f;
            case SCALE_4_3:
                return 4f / 3f;
            default:
                return 0f;
        }
    }

    /** 应用档位到播放器。 */
    public static void apply(PlayerView player, int scale) {
        if (player == null) return;
        player.setResizeMode(resizeMode(scale));
        RATIOS.put(player, ratio(scale));
        if (!LISTENERS.containsKey(player)) {
            View.OnLayoutChangeListener listener = (v, l, t, r, b, ol, ot, or, ob) -> applyPadding((PlayerView) v);
            LISTENERS.put(player, listener);
            player.addOnLayoutChangeListener(listener);
        }
        applyPadding(player);
    }

    /** 在播放器内挖出居中的定比例盒子（用 padding 实现，两侧/上下留黑边）。 */
    private static void applyPadding(PlayerView player) {
        Float ratio = RATIOS.get(player);
        int width = player.getWidth();
        int height = player.getHeight();
        if (ratio == null || ratio <= 0 || width <= 0 || height <= 0) {
            player.setPadding(0, 0, 0, 0);
            return;
        }
        float boxWidth = width;
        float boxHeight = width / ratio;
        if (boxHeight > height) {
            boxHeight = height;
            boxWidth = height * ratio;
        }
        int padX = Math.round((width - boxWidth) / 2f);
        int padY = Math.round((height - boxHeight) / 2f);
        player.setPadding(padX, padY, padX, padY);
    }
}
