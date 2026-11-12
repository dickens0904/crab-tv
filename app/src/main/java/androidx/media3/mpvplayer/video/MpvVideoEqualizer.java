package androidx.media3.mpvplayer.video;

/**
 * [LOCAL COMPILE STUB] -- see {@code androidx.media3.mpvplayer.MpvPlayer} for the
 * full rationale. Video colour adjustment for the mpv engine.
 */
public class MpvVideoEqualizer {

    /** Neutral adjustment, as used by the reset path. */
    public static final MpvVideoEqualizer DEFAULT = new MpvVideoEqualizer();

    public static MpvVideoEqualizer create(double brightness, double contrast, double saturation, double gamma, double hue, double sharpness) {
        return DEFAULT;
    }
}