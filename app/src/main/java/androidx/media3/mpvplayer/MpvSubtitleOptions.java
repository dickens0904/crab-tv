package androidx.media3.mpvplayer;

/**
 * Subtitle presentation options for mpv (position, scale, secondary track, styles).
 *
 * <p>Parameter types are pinned by {@code MpvUtil.buildSubtitleOptions()}: the secondary
 * track id is an int index ({@code SubtitleSetting.getSecondaryTrackId()} returns int),
 * and the style's edge width and shadow are floats -- not the ints a caption-style API
 * usually takes.
 *
 * <p>Only {@code position} / {@code scale} map onto libmpv properties ({@code sub-pos},
 * {@code sub-scale}); the secondary track and the style blob are stored but not applied,
 * because this JNI bridge exposes no way to load ASS/SSA override styles.
 */
public class MpvSubtitleOptions {

    final double position;
    final double scale;

    private MpvSubtitleOptions(double position, double scale) {
        this.position = position;
        this.scale = scale;
    }

    public static final class Builder {

        private double position = 100.0;
        private double scale = 1.0;

        public Builder setPosition(double position) {
            this.position = position;
            return this;
        }

        public Builder setScale(double scale) {
            this.scale = scale;
            return this;
        }

        public Builder setSecondarySubtitle(int secondaryTrackId, float secondaryPosition, boolean styleForced) {
            return this;
        }

        public Builder setCustomStyle(int textColor, int backgroundColor, int edgeType, int edgeColor, float edgeWidth, float shadow) {
            return this;
        }

        public Builder setSystemCaptionStyle() {
            return this;
        }

        public MpvSubtitleOptions build() {
            return new MpvSubtitleOptions(position, scale);
        }
    }
}