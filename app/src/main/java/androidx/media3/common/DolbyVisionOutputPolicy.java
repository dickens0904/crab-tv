package androidx.media3.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * [LOCAL COMPILE STUB]
 *
 * <p>WHY: {@code DolbyVisionOutputPolicy} does not exist in ANY published Media3
 * release. Verified by downloading and inspecting the AARs of media3-common /
 * media3-exoplayer / media3-datasource at 1.8.0, 1.9.0, 1.10.0 and 1.11.1 -- the
 * class is absent from all of them. It is part of upstream FongMi's deep Media3 fork.
 *
 * <p>What is replicated here is the surface the app uses, which is a small int
 * enum cycle:
 * <pre>
 *   DecodeSetting.getDolbyVisionOutputPolicy()  default = AUTO
 *   SettingDecode: mode = (mode + 1) % (ASSUME_UNSUPPORTED + 1); put(mode)
 * </pre>
 * so the three values AUTO / TO_SDR_TONEMAP / ASSUME_UNSUPPORTED cycle correctly and
 * the settings row keeps rendering.
 *
 * <p>Note the adjacent dependency: {@code DefaultMediaSourceFactory} in the official
 * 1.8.0 AAR has no {@code setDolbyVisionOutputPolicy} method either, so ExoUtil's
 * call is patched out separately. See ExoUtil.java.
 *
 * <p>ASCII-only comments on purpose (javac defaults to GBK on Chinese Windows and
 * mangles UTF-8 CJK bytes into illegal-character errors).
 */
public final class DolbyVisionOutputPolicy {

    public static final int AUTO = 0;
    public static final int TO_SDR_TONEMAP = 1;
    public static final int ASSUME_UNSUPPORTED = 2;

    /** Annotation the app uses as {@code @DolbyVisionOutputPolicy.Mode}. */
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD,
             ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})
    public @interface Mode {
    }

    private DolbyVisionOutputPolicy() {
    }
}