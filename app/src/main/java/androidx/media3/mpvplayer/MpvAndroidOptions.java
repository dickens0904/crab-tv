package androidx.media3.mpvplayer;

/**
 * [LOCAL COMPILE STUB] -- see {@link MpvPlayer} for the full rationale.
 *
 * <p>Android-side rendering options for mpv (shader cache, Vulkan, gpu-next).
 */
public class MpvAndroidOptions {

    public static final class Builder {

        public Builder setShaderCacheDirectory(java.io.File dir) { return this; }
        public Builder setAudioPassthroughEnabled(boolean enabled) { return this; }
        public Builder setDolbyVisionOutputPolicy(int policy) { return this; }
        public Builder setVulkanEnabled(boolean enabled) { return this; }
        public Builder setGpuNextEnabled(boolean enabled) { return this; }

        public MpvAndroidOptions build() { return new MpvAndroidOptions(); }
    }
}