package com.fongmi.android.tv.player.effect.audio;

/**
 * 多声道下混矩阵（ITU-R BS.775 风格），ExoPlayer 与 IJK 两条内核路径共享同一份定义。
 *
 * <p>来源：蟹老板TV {@code DownmixAudioProcessor.kt} 的 {@code MATRIX} 常量，逐值原样移植，
 * 保证两个内核出来的听感一致（红线：两套并存，逻辑一致）。
 *
 * <p>权重约定（与蟹老板TV 完全一致）：
 * <ul>
 *   <li>中置 FC / 环绕 SL、SR：0.7071（-3dB）分别混入左右</li>
 *   <li>LFE：0.5（-6dB）混入左右</li>
 *   <li>前后主声道 FL / FR：1.0 直通</li>
 * </ul>
 *
 * <p>声道顺序按 ISO / SMPTE 标准布局：
 * <pre>
 *   3  FL FR FC
 *   4  FL FR FC BC
 *   5  FL FR FC BL BR
 *   6  FL FR FC LFE BL BR
 *   7  FL FR FC LFE BC SL SR
 *   8  FL FR FC LFE BL BR SL SR
 * </pre>
 */
public final class DownmixMatrix {

    private static final float S = 0.7071f; // -3dB：中置与环绕
    private static final float L = 0.5f;    // -6dB：LFE

    /** 声道数 → {左声道权重, 右声道权重}，索引即输入声道。 */
    private static final float[][] MATRIX = {
            // 3.0  FL FR FC
            {1f, S, 0f},
            {0f, 1f, S},
            // 4.0  FL FR FC BC
            {1f, S, 0f, L},
            {0f, 1f, S, L},
            // 5.0  FL FR FC BL BR
            {1f, S, 0f, S, 0f},
            {0f, 1f, S, 0f, S},
            // 5.1  FL FR FC LFE BL BR
            {1f, S, 0f, L, S, 0f},
            {0f, 1f, S, L, 0f, S},
            // 6.1  FL FR FC LFE BC SL SR
            {1f, S, 0f, L, L, S, 0f},
            {0f, 1f, S, L, L, 0f, S},
            // 7.1  FL FR FC LFE BL BR SL SR
            {1f, S, 0f, L, S, 0f, S, 0f},
            {0f, 1f, S, L, 0f, S, 0f, S},
    };

    private DownmixMatrix() {
    }

    /** 该声道数是否落在矩阵覆盖范围内（3..8，对应 3.0 ~ 7.1）。 */
    public static boolean isSupported(int channelCount) {
        return channelCount >= 3 && channelCount <= 8;
    }

    /** 是否需要降混：多声道（>2）且有对应矩阵。 */
    public static boolean shouldDownmix(int channelCount) {
        return isSupported(channelCount);
    }

    /** 该输入声道数对应的左声道权重（长度 == channelCount）。 */
    public static float[] left(int channelCount) {
        return MATRIX[pairIndex(channelCount)];
    }

    /** 该输入声道数对应的右声道权重（长度 == channelCount）。 */
    public static float[] right(int channelCount) {
        return MATRIX[pairIndex(channelCount) + 1];
    }

    // MATRIX 每 2 行是一对 (左, 右)，按声道数 3,4,5,6,7,8 依次排列，
    // 所以第 N 声道的对首行下标 = (N - 3) * 2；越界回退到 5.1 (第 6 声道)。
    private static int pairIndex(int channelCount) {
        return (isSupported(channelCount) ? channelCount : 6) * 2 - 6;
    }
}