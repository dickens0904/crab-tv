package androidx.media3.exoplayer.trackselection;

import android.content.Context;

/**
 * [LOCAL COMPILE STUB] -- part of upstream FongMi's Media3 fork.
 *
 * <p>Extends the official {@link DefaultTrackSelector} with the fork's per-renderer
 * decode preference. Used by {@code ExoUtil.buildTrackSelector(decode)} and
 * {@code ExoUtil.setDecodePreferences(trackSelector, decode)}, which are reached from
 * both {@code ExoPlayerSession} and the ExoPlayer engine.
 *
 * <p>Why it is a real subclass rather than a patch of {@code ExoUtil}: the official
 * {@code DefaultTrackSelector} already provides everything the app actually needs from
 * it ({@code buildUponParameters()}, {@code setParameters(...)}, {@code setTunnelingEnabled},
 * {@code setForceHighestSupportedBitrate}, {@code setPreferredAudioMimeType} -- all
 * verified present in media3-exoplayer 1.8.0). Only the single fork-only entry point
 * {@link #setRendererDecodePreferences} is missing, and a subclass is the cheapest way
 * to add it without touching official classes.
 *
 * <p>BEHAVIOUR CAVEAT, stated plainly: official Media3 exposes no switch that forces a
 * software decoder -- the fork added that ability inside its patched
 * {@code DefaultRenderersFactory}. So the preference is recorded and read back by the
 * settings UI, but not enforced. What actually happens is stock Android behaviour:
 * hardware decoder first, with automatic fallback to software, which is exactly what
 * {@code ExoUtil} already requests via {@code setEnableDecoderFallback(true)}. Choosing
 * "soft" in the UI therefore changes nothing at playback time -- it only re-tries via
 * {@code PlayerManager.retryDecode}, which is the app's existing recovery path anyway.
 *
 * <p>ASCII-only comments on purpose (javac defaults to GBK on Chinese Windows and
 * mangles UTF-8 CJK bytes into illegal-character errors).
 */
public class DecodeTrackSelector extends DefaultTrackSelector {

    /** Decoder preference constants. Values are internal; only equality is relied on. */
    public static final int DECODE_SOFTWARE = 0;

    public static final int DECODE_HARDWARE = 1;

    private int audioDecode = DECODE_HARDWARE;

    private int videoDecode = DECODE_HARDWARE;

    public DecodeTrackSelector(Context context) {
        super(context);
    }

    /** Records the desired audio/video decode preference. See the class caveat above. */
    public void setRendererDecodePreferences(int audioDecode, int videoDecode) {
        this.audioDecode = audioDecode;
        this.videoDecode = videoDecode;
    }

    public int getAudioDecode() {
        return audioDecode;
    }

    public int getVideoDecode() {
        return videoDecode;
    }
}