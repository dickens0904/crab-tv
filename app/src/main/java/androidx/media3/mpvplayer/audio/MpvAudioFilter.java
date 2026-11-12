package androidx.media3.mpvplayer.audio;

/**
 * [LOCAL COMPILE STUB] -- see {@code androidx.media3.mpvplayer.MpvPlayer} for the full
 * rationale. Audio effect graph for the mpv engine.
 *
 * <p>The Builder accepts the full set of filters the app configures (volume, equalizer,
 * limiter, compressor, loudness normalisation and a runtime channel mix matrix) and
 * records nothing, because every {@code build()} returns {@link #EMPTY}. That is the
 * honest outcome: with no libmpv there is no filter chain to install, and reporting
 * "applied" would make {@code PlayerEffect.getAudioEffectError()} claim success.
 *
 * <p>ASCII-only comments on purpose (javac defaults to GBK on Chinese Windows).
 */
public class MpvAudioFilter {

    /** Sentinel meaning "no audio effect". Returned by every build() in this stub. */
    public static final MpvAudioFilter EMPTY = new MpvAudioFilter();

    public boolean hasEffect() {
        return false;
    }

    public static final class Builder {

        public Builder addVolume(String id, double gain) { return this; }

        public Builder addEqualizer(String id, double centerFrequencyKhz, double gain) { return this; }

        public Builder addLimiter(String id, double limit) { return this; }

        public Builder addCompressor(String id, double threshold, double ratio, double makeupGain, double mix) { return this; }

        public Builder addLoudnessNormalization(String id, double targetLufs, double maxGainDb, double minGainDb) { return this; }

        /** Matrix form is {@code mix[outChannel][inChannel]}; see {@link AudioChannelMix}. */
        public Builder addRuntimeChannelMix(String id, float[][] mix) { return this; }

        public MpvAudioFilter build() { return EMPTY; }
    }
}