package androidx.media3.ui.danmaku;

/**
 * [LOCAL COMPILE STUB]
 *
 * <p>WHY: {@code androidx.media3.ui.danmaku.DanmakuConfig} lives in
 * {@code androidx.media3:media3-ui-danmaku}, a module FongMi/TV publishes nowhere: not on
 * Google Maven, not as an AAR in the repository, not as source at any commit. It is part
 * of the closed-source half of their Media3 fork (together with the mpv modules and their
 * extended {@code PlayerView}). The repository only contains the files that *use* it --
 * {@code DanmakuSetting}, {@code DanmakuSettingPanel}, {@code PlayerManager},
 * {@code PlaybackService}, {@code PlaybackActivity}. So a source build cannot get the real
 * class and declares it here instead.
 *
 * <p>WHAT THIS IS: a faithful data holder. Every field name, type and default below is
 * pinned by the call sites, not guessed:
 * <pre>
 *   DanmakuSetting.getConfig()   -> Builder with 27 setters, then build()
 *   DanmakuSetting.reset*()      -> reads fields directly off DanmakuConfig.DEFAULT
 *   DanmakuSettingPanel          -> STYLE_NONE / SHADOW / STROKE / PROJECTION,
 *                                   COLOR_MODE_DEFAULT / COLORFUL / GRADIENT
 * </pre>
 * The fields are public because {@code resetAppearance()}, {@code resetTiming()} and
 * {@code resetDensity()} assign them straight into {@code Prefers} -- so the names and
 * the defaults are load-bearing: they are what "restore defaults" writes.
 *
 * <p>WHAT THIS IS NOT: nothing renders danmaku with it. The renderer lived in the same
 * closed-source module and is not reconstructible from the call sites. Everything around
 * it -- the settings screen, the preferences, {@code PlayerManager}'s state -- works and
 * round-trips; the comments simply are not painted on the video.
 *
 * <p>ASCII-only comments on purpose (javac defaults to GBK on Chinese Windows and
 * mangles UTF-8 CJK bytes into illegal-character errors).
 */
public class DanmakuConfig {

    // --- style modes ---------------------------------------------------------
    public static final int STYLE_NONE = 0;
    public static final int STYLE_SHADOW = 1;
    public static final int STYLE_STROKE = 2;
    public static final int STYLE_PROJECTION = 3;

    // --- colour modes --------------------------------------------------------
    public static final int COLOR_MODE_DEFAULT = 0;
    public static final int COLOR_MODE_COLORFUL = 1;
    public static final int COLOR_MODE_GRADIENT = 2;

    /** Default instance, read by DanmakuSetting.resetAppearance/Timing/Density. */
    public static final DanmakuConfig DEFAULT = new Builder().build();

    // --- appearance ----------------------------------------------------------
    public float textScale;
    public float transparency;
    public boolean textBold;
    public int styleMode;
    public float shadowTransparency;
    public float strokeWidthMultiplier;
    public float projectionOffsetXMultiplier;
    public float projectionOffsetYMultiplier;
    public float projectionTransparency;
    public int colorMode;

    // --- timing --------------------------------------------------------------
    public long durationMs;
    public long fixedDurationMs;
    public long timeOffsetMs;

    // --- density / layout ----------------------------------------------------
    public int maxOnScreen;
    public float scrollAreaRatio;
    public float scrollGapRatio;
    public float lineSpacing;
    public int maxScrollLines;
    public int maxTopLines;
    public int maxBottomLines;

    // --- visibility ----------------------------------------------------------
    public boolean showScroll;
    public boolean showTop;
    public boolean showBottom;
    public boolean showReverse;
    public boolean showPositioned;
    public boolean showSubtitle;
    public boolean showSpecial;

    public DanmakuConfig() {
    }

    private DanmakuConfig(Builder builder) {
        this.textScale = builder.textScale;
        this.transparency = builder.transparency;
        this.textBold = builder.textBold;
        this.styleMode = builder.styleMode;
        this.shadowTransparency = builder.shadowTransparency;
        this.strokeWidthMultiplier = builder.strokeWidthMultiplier;
        this.projectionOffsetXMultiplier = builder.projectionOffsetXMultiplier;
        this.projectionOffsetYMultiplier = builder.projectionOffsetYMultiplier;
        this.projectionTransparency = builder.projectionTransparency;
        this.colorMode = builder.colorMode;
        this.durationMs = builder.durationMs;
        this.fixedDurationMs = builder.fixedDurationMs;
        this.timeOffsetMs = builder.timeOffsetMs;
        this.maxOnScreen = builder.maxOnScreen;
        this.scrollAreaRatio = builder.scrollAreaRatio;
        this.scrollGapRatio = builder.scrollGapRatio;
        this.lineSpacing = builder.lineSpacing;
        this.maxScrollLines = builder.maxScrollLines;
        this.maxTopLines = builder.maxTopLines;
        this.maxBottomLines = builder.maxBottomLines;
        this.showScroll = builder.showScroll;
        this.showTop = builder.showTop;
        this.showBottom = builder.showBottom;
        this.showReverse = builder.showReverse;
        this.showPositioned = builder.showPositioned;
        this.showSubtitle = builder.showSubtitle;
        this.showSpecial = builder.showSpecial;
    }

    public static final class Builder {

        private float textScale = 1.0f;
        private float transparency = 1.0f;
        private boolean textBold;
        private int styleMode = STYLE_STROKE;
        private float shadowTransparency = 1.0f;
        private float strokeWidthMultiplier = 1.0f;
        private float projectionOffsetXMultiplier;
        private float projectionOffsetYMultiplier;
        private float projectionTransparency = 1.0f;
        private int colorMode = COLOR_MODE_DEFAULT;
        private long durationMs;
        private long fixedDurationMs;
        private long timeOffsetMs;
        private int maxOnScreen;
        private float scrollAreaRatio = 1.0f;
        private float scrollGapRatio = 1.0f;
        private float lineSpacing = 1.0f;
        private int maxScrollLines;
        private int maxTopLines;
        private int maxBottomLines;
        private boolean showScroll = true;
        private boolean showTop = true;
        private boolean showBottom = true;
        private boolean showReverse = true;
        private boolean showPositioned = true;
        private boolean showSubtitle = true;
        private boolean showSpecial = true;

        public Builder setTextScale(float textScale) {
            this.textScale = textScale;
            return this;
        }

        public Builder setTransparency(float transparency) {
            this.transparency = transparency;
            return this;
        }

        public Builder setTextBold(boolean textBold) {
            this.textBold = textBold;
            return this;
        }

        public Builder setStyleMode(int styleMode) {
            this.styleMode = styleMode;
            return this;
        }

        public Builder setShadowTransparency(float shadowTransparency) {
            this.shadowTransparency = shadowTransparency;
            return this;
        }

        public Builder setStrokeWidthMultiplier(float strokeWidthMultiplier) {
            this.strokeWidthMultiplier = strokeWidthMultiplier;
            return this;
        }

        public Builder setProjectionOffsetXMultiplier(float projectionOffsetXMultiplier) {
            this.projectionOffsetXMultiplier = projectionOffsetXMultiplier;
            return this;
        }

        public Builder setProjectionOffsetYMultiplier(float projectionOffsetYMultiplier) {
            this.projectionOffsetYMultiplier = projectionOffsetYMultiplier;
            return this;
        }

        public Builder setProjectionTransparency(float projectionTransparency) {
            this.projectionTransparency = projectionTransparency;
            return this;
        }

        public Builder setColorMode(int colorMode) {
            this.colorMode = colorMode;
            return this;
        }

        public Builder setDurationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder setFixedDurationMs(long fixedDurationMs) {
            this.fixedDurationMs = fixedDurationMs;
            return this;
        }

        public Builder setTimeOffsetMs(long timeOffsetMs) {
            this.timeOffsetMs = timeOffsetMs;
            return this;
        }

        public Builder setMaxOnScreen(int maxOnScreen) {
            this.maxOnScreen = maxOnScreen;
            return this;
        }

        public Builder setScrollAreaRatio(float scrollAreaRatio) {
            this.scrollAreaRatio = scrollAreaRatio;
            return this;
        }

        public Builder setScrollGapRatio(float scrollGapRatio) {
            this.scrollGapRatio = scrollGapRatio;
            return this;
        }

        public Builder setLineSpacing(float lineSpacing) {
            this.lineSpacing = lineSpacing;
            return this;
        }

        public Builder setMaxScrollLines(int maxScrollLines) {
            this.maxScrollLines = maxScrollLines;
            return this;
        }

        public Builder setMaxTopLines(int maxTopLines) {
            this.maxTopLines = maxTopLines;
            return this;
        }

        public Builder setMaxBottomLines(int maxBottomLines) {
            this.maxBottomLines = maxBottomLines;
            return this;
        }

        public Builder setShowScroll(boolean showScroll) {
            this.showScroll = showScroll;
            return this;
        }

        public Builder setShowTop(boolean showTop) {
            this.showTop = showTop;
            return this;
        }

        public Builder setShowBottom(boolean showBottom) {
            this.showBottom = showBottom;
            return this;
        }

        public Builder setShowReverse(boolean showReverse) {
            this.showReverse = showReverse;
            return this;
        }

        public Builder setShowPositioned(boolean showPositioned) {
            this.showPositioned = showPositioned;
            return this;
        }

        public Builder setShowSubtitle(boolean showSubtitle) {
            this.showSubtitle = showSubtitle;
            return this;
        }

        public Builder setShowSpecial(boolean showSpecial) {
            this.showSpecial = showSpecial;
            return this;
        }

        public DanmakuConfig build() {
            return new DanmakuConfig(this);
        }
    }
}