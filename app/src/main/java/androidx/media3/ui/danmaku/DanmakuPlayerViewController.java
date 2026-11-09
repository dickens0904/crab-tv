package androidx.media3.ui.danmaku;

import okhttp3.OkHttpClient;

/**
 * [LOCAL COMPILE STUB]
 *
 * <p>Companion stub to {@link DanmakuConfig}: placeholder for the same closed-source
 * {@code media3-ui-danmaku} artifact. Purpose is purely to let the project compile;
 * no danmaku is rendered.
 *
 * <p>Complete upstream call surface of this class (verified by grepping the repo):
 * <pre>
 *   new DanmakuPlayerViewController()
 *   .bind(View)
 *   .setOkHttpClient(OkHttpClient)
 *   .setConfig(DanmakuConfig)
 *   .setDataSource(Uri)
 *   .setEnabled(boolean)
 *   .sendNow(String)
 *   .close()
 * </pre>
 *
 * <p>All signatures are preserved and the call ordering used by PlaybackActivity
 * (setDataSource then setConfig then setEnabled) is kept, so no re-entrancy surprises.
 *
 * <p>2026-09 / written for self-compiling on ct2000 (armv7l, Android 9).
 */
public class DanmakuPlayerViewController {

    private boolean enabled = false;
    private DanmakuConfig config = DanmakuConfig.DEFAULT;

    public void bind(android.view.View view) {
        // Real library attaches its render layer to the PlayerView here.
    }

    public void setOkHttpClient(OkHttpClient client) {
        // Real library uses this client to fetch danmaku XML.
    }

    public void setConfig(DanmakuConfig config) {
        this.config = config == null ? DanmakuConfig.DEFAULT : config;
    }

    public DanmakuConfig getConfig() {
        return config;
    }

    public void setDataSource(android.net.Uri uri) {
        // Real library parses the danmaku source here.
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void sendNow(String text) {
        // Real library renders one danmaku immediately.
    }

    public void close() {
        // Real library releases its thread and caches.
    }
}