package com.fongmi.android.tv.player.ijk;

import com.fongmi.android.tv.player.effect.audio.DownmixMatrix;
import com.fongmi.android.tv.setting.AudioSetting;

import java.util.Locale;

/**
 * IJK 侧的多声道降混：ffmpeg {@code pan} 滤镜表达式。
 *
 * <p><b>为什么权重不是写死的常量：</b> 这里的每一个系数都从
 * {@link DownmixMatrix#left(int)} / {@link DownmixMatrix#right(int)} 取，也就是说
 * IJK 与 ExoPlayer（{@code DownmixAudioProcessor}）用的是同一份 ITU-R BS.775 风格
 * 矩阵，两条内核听感一致。改动 {@code DownmixMatrix} 一处，两边同时变。
 *
 * <p><b>为什么通道数要先探测：</b> ffmpeg 的 {@code pan} 表达式里 {@code cN}
 * 只能引用输入<b>实际存在</b>的通道；引用越界通道会求值为 NaN，整条音频变成噪声。
 * 所以不能无条件挂一个 5.1 的滤镜——立体声源会被打坏。调用方（{@link IjkPlayerEngine}）
 * 先 prepare 一次拿到真实声道数，再决定是否用 {@link #downmix(int)} 重建。
 *
 * <p>声道顺序沿用 {@code DownmixMatrix} 的 ISO/SMPTE 布局（FL FR FC LFE BL BR
 * SL SR），ffmpeg 的 {@code pan} 同样按 AVChannelOrder 顺序编号，两者一致。
 */
public final class IjkDownmix {

    private IjkDownmix() {
    }

    /**
     * 是否需要对这条流挂 pan 滤镜。与 {@code DownmixAudioProcessor} 的模式语义保持
     * 一致：OFF 全关；AUTO 只在多声道时降混；FORCE 多声道一律降混。
     */
    public static boolean shouldApply(int channelCount) {
        return AudioSetting.isDownmixEnabled() && DownmixMatrix.shouldDownmix(channelCount);
    }

    /**
     * 由共享矩阵生成的 ffmpeg {@code pan} 滤镜串，例如 5.1 输入得到：
     * <pre>pan=stereo|c0=c0*1+c2*0.7071+c3*0.5+c4*0.7071|c1=c1*1+c2*0.7071+c3*0.5+c5*0.7071</pre>
     *
     * @param channelCount 输入声道数，必须落在 {@link DownmixMatrix#isSupported} 内
     * @return 滤镜串；不需要降混时返回 {@code null}
     */
    public static String downmix(int channelCount) {
        if (!DownmixMatrix.shouldDownmix(channelCount)) return null;
        return "pan=stereo|" + expression("c0", DownmixMatrix.left(channelCount))
                + "|" + expression("c1", DownmixMatrix.right(channelCount));
    }

    /**
     * ijk 的 {@code af} 选项值。挂上去之前必须仍在 prepare 之前（选项只对
     * FFmpegContext 建链时生效）。
     */
    public static String buildAudioFilter(int channelCount) {
        return downmix(channelCount);
    }

    private static String expression(String output, float[] weights) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < weights.length; i++) {
            float weight = weights[i];
            if (weight == 0f) continue;
            if (sb.length() > 0) sb.append('+');
            sb.append('c').append(i).append('*').append(format(weight));
        }
        // 理论上不会走到：DownmixMatrix 对 3..8 声道每行至少有一个非零权重。留一个
        // 恒等直通兜底，避免出现空表达式让 ffmpeg 直接拒绝整个滤镜链。
        if (sb.length() == 0) return output + "=" + output.substring(1);
        return output + "=" + sb;
    }

    private static String format(float weight) {
        // 0.7071f 用 %s 会打出 "0.7071"，0.5f 打成 "0.5"，都是 ffmpeg 能解析的写法。
        return String.format(Locale.US, "%s", weight);
    }

    /**
     * 把 ijk 回报的 channel layout 掩码折算成声道数。掩码为 0（容器没写）时用
     * {@code channel_layout} 描述串兜底，例如 "5.1" / "stereo" / "7.1"。
     */
    public static int toChannelCount(long layoutMask, String layoutText) {
        int count = countChannels(layoutMask);
        if (count > 0) return count;
        return parseChannelCount(layoutText);
    }

    private static int countChannels(long mask) {
        if (mask <= 0) return 0;
        return Long.bitCount(mask);
    }

    /** "5.1" -> 6, "7.1" -> 8, "stereo" -> 2, "mono" -> 1, 其余 -> 0（未知）。 */
    private static int parseChannelCount(String text) {
        if (text == null) return 0;
        String lower = text.toLowerCase(Locale.US).trim();
        if (lower.isEmpty()) return 0;
        switch (lower) {
            case "mono":
            case "1.0":
                return 1;
            case "stereo":
            case "2.0":
                return 2;
            default:
                break;
        }
        int dot = lower.indexOf('.');
        if (dot > 0 && dot < lower.length() - 1) {
            try {
                int front = Integer.parseInt(lower.substring(0, dot));
                int lfe = Integer.parseInt(lower.substring(dot + 1));
                int total = front + lfe;
                return total > 0 ? total : 0;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}