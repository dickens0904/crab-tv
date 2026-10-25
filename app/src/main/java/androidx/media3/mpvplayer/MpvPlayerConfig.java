package androidx.media3.mpvplayer;

/**
 * Options bag handed to {@code MpvPlayer.Builder.setConfig(...)}.
 *
 * <p>Only the pieces that map onto a real libmpv option are carried through; the rest of
 * the fork's config surface (TLS CA asset, shader cache, disk cache, subtitle options)
 * has no pre-initialize mpv equivalent that this bridge can set, so it is accepted and
 * ignored rather than faked.
 */
public class MpvPlayerConfig {

    /** mpv {@code --config-dir}; applied before {@code mpv_initialize()}. */
    final String configDirectory;

    private MpvPlayerConfig(String configDirectory) {
        this.configDirectory = configDirectory;
    }

    public static final class Builder {

        private String configDirectory;
        private MpvSubtitleOptions subtitleOptions;

        public Builder addConfigDirectory(java.io.File dir) {
            if (dir != null) configDirectory = dir.getAbsolutePath();
            return this;
        }

        public Builder addAndroidFontConfig(java.io.File fontDir, java.io.File cacheDir) {
            // mpv-android does this natively inside its own VO/AO init; nothing to set here.
            return this;
        }

        public Builder addAndroidDefaults(MpvAndroidOptions options) {
            return this;
        }

        public Builder addTlsCaFileFromAsset(android.content.Context ctx, String assetName, java.io.File dest) {
            return this;
        }

        public Builder addAndroidSubtitleOptions(android.content.Context ctx, MpvSubtitleOptions options) {
            this.subtitleOptions = options;
            return this;
        }

        public Builder addDiskCacheOptions(java.io.File cacheDir, int preloadSeconds) {
            // mpv's own "cache" / "demuxer-max-bytes" options are set by the player itself.
            return this;
        }

        public MpvPlayerConfig build() {
            MpvPlayerConfig config = new MpvPlayerConfig(configDirectory);
            config.subtitleOptions = subtitleOptions;
            return config;
        }
    }

    MpvSubtitleOptions subtitleOptions;
}