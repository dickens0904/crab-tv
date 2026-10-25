package androidx.media3.mpvplayer;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.Size;
import com.fongmi.android.tv.player.engine.EngineCommands;
import androidx.media3.mpvplayer.audio.MpvAudioFilter;
import androidx.media3.mpvplayer.video.MpvVideoEqualizer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import is.xyz.mpv.MPVLib;

/**
 * mpv 内核（真正驱动 libmpv）。
 *
 * <p>上游 FongMi/TV 依赖一份私有 Media3 fork 里的 {@code androidx.media3:media3-mpvplayer}，
 * 那份 AVM 没有公开发布；mpv-android 的 {@code MPVLib} + {@code libplayer.so} + {@code libmpv.so}
 * 三件套已经落在 {@code jniLibs/} 里，于是这里用它的 client API 把 mpv 接到 media3 的
 * {@link Player} 契约上：类结构、{@link Builder}、mpv-only 的能力方法都保持原样，
 * 播放框架那一侧零改动。
 *
 * <p><b>进程内只有一个 mpv core。</b> {@code MPVLib} 的 native 侧持有全局
 * {@code g_mpv}，{@code create}/{@code init}/{@code destroy} 都是进程级的，而且重复
 * {@code init()}／{@code destroy()} 会走 native 的 {@code exit()} 直接杀进程。所以：
 * <ul>
 *   <li>{@link #coreCreated} / {@link #coreInitialized} 两个静态标记保证 create/init/destroy
 *       各只发生一次；</li>
 *   <li>{@link #active} 记录当前持有者。PlayerManager 换内核时是「先 new 新的，再 release
 *       旧的」，若此时直接 {@code create()} 会覆盖全局句柄、泄漏旧 core，随后旧实例的
 *       {@code release()} 会把<em>新</em> core 销毁掉。所以旧实例一旦被顶替就只是摘掉观察者，
 *       不再拥有销毁权。</li>
 * </ul>
 *
 * <p><b>为什么延迟初始化：</b> libplayer 的 {@code attachSurface} 走
 * {@code mpv_set_option(wid)}，而 {@code mpv_set_option} 在 {@code mpv_initialize()} 之后
 * 一律返回 {@code MPV_ERROR_OPTION_ERROR}。也就是说 surface 必须在 initialize 之前绑上。
 * 播放流程里 surface 由 {@code PlayerView.setPlayer} → {@link #setVideoSurfaceView} 先送到，
 * 再由 {@code prepare()} 触发 initialize，顺序天然成立。万一晚到了，只能退化成无画面
 * （音频照出），不会崩。
 *
 * <p>ASCII-only comments on purpose (javac defaults to GBK on Chinese Windows and
 * mangles UTF-8 CJK bytes into illegal-character errors).
 */
public class MpvPlayer implements Player, MPVLib.EventObserver {

    private static final String TAG = "MpvPlayer";

    /** How long a prepare waits for a Surface before falling back to audio-only.
     */
    private static final long SURFACE_TIMEOUT_MS = 4000;

    private boolean pendingPrepare;

    private SurfaceHolder.Callback surfaceCallback;

    private SurfaceHolder surfaceCallbackHolder;

    private static final int HARD = androidx.media3.exoplayer.trackselection.DecodeTrackSelector.DECODE_HARDWARE;

    // ----- pixel-level output evidence ---------------------------------------

    /**
     * mpv's own {@code screenshot} command writes the frame it just rendered straight to
     * disk. That is the only output proof that does not depend on how (or whether) Android
     * composites the Surface, so it is the ground truth when the SurfaceView path looks
     * black on screencap.
     */
    private static final String SHOT_NAME = "mpv_shot";

    private static final int[] SHOT_DELAYS_MS = {2500, 8000, 20000};

    private static final String OVERRIDE_FILE = "mpv_pixel.conf";

    /** Marks the TextureView this class adds, so a new player instance reuses it. */
    private static final String TEXTURE_TAG = "mpv-texture-surface";

    /** mpv log messages forwarded into logcat; without this there is no VO trace at all. */
    private static final MPVLib.LogObserver LOG_OBSERVER = new MPVLib.LogObserver() {
        @Override
        public void logMessage(String prefix, int level, String text) {
            String line = prefix + text;
            if (level <= MPVLib.MPV_LOG_LEVEL_ERROR) Log.e(TAG, line);
            else if (level <= MPVLib.MPV_LOG_LEVEL_WARN) Log.w(TAG, line);
            else Log.d(TAG, line);
        }
    };

    // ----- mpv capability surface (all fork-only) -----------------------------

    public static final int AUDIO_EFFECTS_SUPPORTED = 0;
    public static final int AUDIO_EFFECTS_UNSUPPORTED_PASSTHROUGH = 1;
    public static final int AUDIO_EFFECTS_UNSUPPORTED = 2;
    public static final int VIDEO_EFFECTS_SUPPORTED = 0;
    public static final int VIDEO_EFFECTS_UNSUPPORTED_DIRECT_DOLBY_VISION_OUTPUT = 1;
    public static final int VIDEO_EFFECTS_UNSUPPORTED = 2;

    /**
     * The single switch that makes {@code PlayerEngineFactory} pick mpv: libmpv.so and
     * libplayer.so are present in {@code jniLibs/} for both ABIs, and touching
     * {@link MPVLib} runs the static initializer that actually dlopen()s them.
     */
    public static boolean isAvailable() {
        try {
            MPVLib.ensureLoaded();
            return MPVLib.isAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    public int getAudioEffectsSupport() {
        // Honest: the mpv "af" chain this hook would drive is not wired up here, so the
        // audio effects UI keeps reporting "unsupported" on mpv exactly as before.
        return AUDIO_EFFECTS_UNSUPPORTED;
    }

    public int getVideoEffectsSupport() {
        // Same reason: mpv "vf" gamma/eq are not applied, so video adjustment stays off.
        return VIDEO_EFFECTS_UNSUPPORTED;
    }

    public boolean setAudioFilter(MpvAudioFilter filter) {
        return false;
    }

    public boolean setVideoEqualizer(MpvVideoEqualizer equalizer) {
        return false;
    }

    public boolean isVideoSharpnessSupported() {
        return false;
    }

    public int getAudioChannelCount() {
        return audioChannelCount;
    }

    public void setDecode(int decode) {
        this.decode = decode;
        if (!initialized) return;
        // mpv picks hwdec per track at open time; hwdec is read at loadfile, so flipping
        // it takes effect on the next prepare. Applying it live is harmless too.
        command("set", "hwdec", decode == HARD ? "mediacodec-copy" : "no");
    }

    public void setSubtitleOptions(MpvSubtitleOptions options) {
        this.subtitleOptions = options;
        if (!initialized || options == null) return;
        // Only the two options this stub actually carries; the rest of the fork's
        // subtitle surface has no libmpv equivalent we can set from here.
        applySubtitleOptions();
    }

    public void setAudioOutputListener(@Nullable AudioOutputListener listener) {
        this.audioOutputListener = listener;
    }

    public boolean addSubtitle(androidx.media3.common.MediaItem.SubtitleConfiguration configuration) {
        if (!initialized || configuration == null || configuration.uri == null) return false;
        String uri = configuration.uri.toString();
        if (uri.isEmpty()) return false;
        int before = readSubCount();
        MPVLib.getInstance().command(new String[]{"sub-add", uri, "select"});
        // Real check, not a hopeful true: mpv's "sub-count" has to have gone up.
        return readSubCount() > before;
    }

    private static int readSubCount() {
        try {
            Integer count = MPVLib.getInstance().getPropertyInt("sub-count");
            return count == null ? 0 : count;
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Callback shape used by MpvPlayerEngine to receive processed audio output. */
    public interface AudioOutputListener {
        void onAudioOutput();
    }

    public static final class Builder {

        private final Context context;
        private MpvPlayerConfig config;
        private int decode;

        public Builder(android.content.Context context) {
            this.context = context == null ? null : context.getApplicationContext();
        }

        public Builder setDecode(int decode) {
            this.decode = decode;
            return this;
        }

        public Builder setConfig(MpvPlayerConfig config) {
            this.config = config;
            return this;
        }

        public MpvPlayer build() {
            MpvPlayer player = new MpvPlayer(context, decode, config);
            return player;
        }
    }

    // ----- process-global core bookkeeping -----------------------------------

    private static MpvPlayer active;
    private static boolean coreCreated;
    private static boolean coreInitialized;

    // ----- local state -------------------------------------------------------

    private static final int Format_NO_VALUE = androidx.media3.common.Format.NO_VALUE;

    private final List<Player.Listener> listeners = new ArrayList<>();

    private final List<MediaItem> mediaItems = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Context appContext;
    private final MpvPlayerConfig config;

    private int decode;

    private MpvSubtitleOptions subtitleOptions;

    private AudioOutputListener audioOutputListener;

    private int playbackState = STATE_IDLE;

    private boolean playWhenReady;

    private boolean playing;

    private boolean released;

    private boolean initialized;

    private boolean fileLoaded;

    private float volume = 1f;

    private PlaybackParameters playbackParameters = PlaybackParameters.DEFAULT;

    private int repeatMode = REPEAT_MODE_OFF;

    private Surface surface;

    /** TextureView hosted in place of the SurfaceView when the texture path is selected. */
    private TextureView textureView;

    private TextureView.SurfaceTextureListener textureListener;

    /** Parsed {@link #OVERRIDE_FILE}; also carries the app-level "surface=" switch. */
    private java.util.Map<String, String> overrides;

    /** Cached position, refreshed from mpv's observed "time-pos" property. */
    private volatile long positionMs;

    private volatile long durationMs = C.TIME_UNSET;

    private volatile int videoWidth;

    private volatile int videoHeight;

    private volatile int audioChannelCount = Format.NO_VALUE;

    private TrackSelectionParameters trackSelectionParameters = new TrackSelectionParameters.Builder().build();

    private MpvPlayer(Context appContext, int decode, MpvPlayerConfig config) {
        this.appContext = appContext;
        this.decode = decode;
        this.config = config;
        if (config != null && config.subtitleOptions != null) this.subtitleOptions = config.subtitleOptions;
    }

    /**
     * Creates (or adopts) the process-global mpv core. Must only be reached from the
     * main thread -- every media3 Player callback and PlayerManager call site is.
     */
    private synchronized void ensureInitialized() {
        if (initialized || released) return;
        if (!isAvailable()) throw new IllegalStateException("libmpv is not available on this device");
        MPVLib mpv = MPVLib.getInstance();
        if (coreCreated && coreInitialized) {
            // Hand-over: the previous owner is about to be released but has not been
            // released yet. Reuse its core instead of creating a second one.
            if (active != null) active.detach();
        } else {
            mpv.create(appContext);
            coreCreated = true;
            applyOptions(mpv);
            mpv.init();
            coreInitialized = true;
        }
        initialized = true;
        observe(mpv);
        MPVLib.addLogObserver(LOG_OBSERVER);
        // Honour the requested play state instead of forcing a pause: when the Surface only
        // arrives after prepare() (the TextureView path always does), play() has already run
        // against an uninitialised core and left mpv paused at position 0 forever.
        mpv.setPropertyBoolean("pause", !playWhenReady);
        mpv.setPropertyDouble("volume", volume);
        MPVLib.addObserver(this);
        active = this;
        applySubtitleOptions();
    }

    /**
     * Everything here has to happen before {@code mpv_initialize()}; afterwards
     * {@code mpv_set_option} is rejected outright. Non-fatal errors are ignored on
     * purpose: an unknown option must not cost us the video output.
     */
    private void applyOptions(MPVLib mpv) {
        // libmpv here is built with vo=mediacodec_embed (mpv's "Android (Embedded
        // MediaCodec Surface)" driver) and ao=audiotrack -- both confirmed present in
        // libmpv.so. vo=android, which older mpv-android used, does NOT exist here and is
        // rejected with "[vo:error] Video output android not found!".
        mpv.setOptionString("vo", "gpu-next");
        mpv.setOptionString("ao", "audiotrack");
        mpv.setOptionString("pause", "yes");
        mpv.setOptionString("idle", "yes");
        mpv.setOptionString("keep-open", "no");
        mpv.setOptionString("cache", "yes");
        mpv.setOptionString("cache-secs", "60");
        mpv.setOptionString("demuxer-max-bytes", "512MiB");
        mpv.setOptionString("demuxer-max-back-bytes", "64MiB");
        mpv.setOptionString("network-timeout", "60");
        mpv.setOptionString("msg-level", "all=warn");
        // The app draws its own controls; mpv's key bindings would swallow the remote.
        mpv.setOptionString("input-default-bindings", "no");
        mpv.setOptionString("input-vo-keyboard", "no");
        // vo=mediacodec_embed still renders through mpv's gpu pipeline, so it needs a
        // GL/Vulkan context bound to the surface. context_android.c and libmpv_gl.c are
        // both present in this libmpv, so gpu-context=android is available; without it
        // the video chain dies with "[vf:fatal] Cannot convert decoder/filter output to
        // any format supported by the output".
        mpv.setOptionString("gpu-context", "android");
        mpv.setOptionString("gpu-api", useVulkan() ? "vulkan" : "opengl");
        // mediacodec-copy is what mpv-android pairs with this VO: zero-copy hwdec
        // hands the VO a format vo_mediacodec_embed cannot convert, which fails the
        // video chain just as hard.
        mpv.setOptionString("hwdec", decode == HARD ? "mediacodec-copy" : "no");
        if (config != null && config.configDirectory != null) {
            mpv.setOptionString("config-dir", config.configDirectory);
        }
        // Pixel-level evidence: mpv writes the frame it rendered itself, no Android
        // compositing involved. Written into a directory the app can actually write to.
        String shotDir = shotDir();
        mpv.setOptionString("screenshot-template", shotDir + "/" + SHOT_NAME + "####");
        mpv.setOptionString("screenshot-format", "png");
        mpv.setOptionString("screenshot-png-compression", "0");
        mpv.setOptionString("screenshot-jpeg-quality", "95");
        applyOverrides(mpv);
        if (surface != null) {
            mpv.attachSurface(surface);
        } else {
            // No Surface yet: drop the video track rather than let vo=mediacodec_embed
            // assert on wid == 0 and abort the process. Audio still plays.
            Log.d(TAG, "initialising without a Surface: video disabled, audio only");
            mpv.setOptionString(
                    "vid", "no");
        }
        Log.i(TAG, "core up: surface=" + (surface != null) + " texture=" + useTextureView()
                + " overrides=" + overrides + " shotDir=" + shotDir);
    }

    /**
     * A {@code key = value} file dropped next to the app's external files dir. Anything in it
     * is applied with {@code mpv_set_option} after the built-in defaults, so vo / gpu-context /
     * hwdec / gpu-api can be varied from a shell without rebuilding. "surface" is read by this
     * class itself instead of being handed to mpv.
     */
    private void applyOverrides(MPVLib mpv) {
        overrides = readOverrides();
        for (java.util.Map.Entry<String, String> entry : overrides.entrySet()) {
            String key = entry.getKey();
            // "surface" and "evidence" are read by this class; "prop:<name>" targets a
            // VO-private option that does not exist until the VO is created and so has to be
            // set after mpv_initialize.
            if (key.equals("surface") || key.equals("evidence") || key.startsWith("prop:")) continue;
            try {
                int rc = mpv.setOptionString(key, entry.getValue());
                Log.i(TAG, "override " + key + "=" + entry.getValue() + " rc=" + rc);
            } catch (Throwable e) {
                Log.w(TAG, "override " + key + " failed: " + e);
            }
        }
    }

    /** Re-applies the {@code prop:} overrides once the video output exists. */
    private void applyPropertyOverrides(MPVLib mpv) {
        if (overrides == null) return;
        for (java.util.Map.Entry<String, String> entry : overrides.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("prop:")) continue;
            String name = key.substring(5);
            try {
                mpv.setPropertyString(name, entry.getValue());
                Log.i(TAG, "property override " + name + "=" + entry.getValue());
            } catch (Throwable e) {
                Log.w(TAG, "property override " + name + " failed: " + e);
            }
        }
    }

    private java.util.Map<String, String> readOverrides() {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        File file = overrideFile();
        if (file == null || !file.isFile()) return map;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                map.put(key, value);
            }
        } catch (Throwable e) {
            Log.w(TAG, "override read failed: " + e);
        }
        return map;
    }

    private File overrideFile() {
        File dir = appContext == null ? null : appContext.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, OVERRIDE_FILE);
    }

    /** TextureView instead of SurfaceView: opt-in so the SurfaceView path stays the default. */
    public boolean isTextureSurface() {
        return useTextureView();
    }

    /**
     * mpv draws with OpenGL into whatever native window it is handed. On a SurfaceView that
     * window is a separate SurfaceFlinger layer: this box composites it outside the display
     * framebuffer, so {@code screencap} (and anything else reading the framebuffer) sees
     * nothing but the background there -- the picture is unverifiable even when mpv is
     * rendering it correctly. A TextureView puts the very same frames inside the app window,
     * which makes the output both visible on screen and readable by the standard capture
     * tools. The SurfaceView path stays available through {@code surface=surfaceview}.
     */
    private boolean useTextureView() {
        if (overrides == null) overrides = readOverrides();
        String mode = overrides.get("surface");
        return mode == null || mode.isEmpty() || "texture".equalsIgnoreCase(mode);
    }

    /** First directory this process can actually write a PNG into. */
    private String shotDir() {
        File[] candidates = {new File("/data/local/tmp"), appContext == null ? null : appContext.getExternalFilesDir(null), appContext == null ? null : appContext.getCacheDir()};
        for (File dir : candidates) {
            if (dir == null || !dir.isDirectory()) continue;
            File probe = new File(dir, ".mpv_write_probe");
            try {
                if (probe.isFile() || probe.createNewFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    probe.delete();
                    return dir.getAbsolutePath();
                }
            } catch (Throwable ignored) {
            }
        }
        return "/data/local/tmp";
    }

    /** Vulkan is opt-in (player settings) and only where the device advertises it. */
    private static boolean useVulkan() {
        try {
            return com.fongmi.android.tv.player.mpv.MpvUtil.isVulkanSupported()
                    && com.fongmi.android.tv.setting.PlayerSetting.isMpvVulkan();
        } catch (Throwable e) {
            return false;
        }
    }

    private void observe(MPVLib mpv) {
        mpv.observeProperty("time-pos", MPVLib.MPV_FORMAT_DOUBLE);
        mpv.observeProperty("duration", MPVLib.MPV_FORMAT_DOUBLE);
        mpv.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG);
        mpv.observeProperty("core-idle", MPVLib.MPV_FORMAT_FLAG);
        mpv.observeProperty("eof-reached", MPVLib.MPV_FORMAT_FLAG);
        mpv.observeProperty("volume", MPVLib.MPV_FORMAT_DOUBLE);
        mpv.observeProperty("speed", MPVLib.MPV_FORMAT_DOUBLE);
        mpv.observeProperty("video-params/w", MPVLib.MPV_FORMAT_INT64);
        mpv.observeProperty("video-params/h", MPVLib.MPV_FORMAT_INT64);
        mpv.observeProperty("audio-params/channel-count", MPVLib.MPV_FORMAT_INT64);
    }

    private void applySubtitleOptions() {
        if (!initialized) return;
        MPVLib mpv = MPVLib.getInstance();
        if (subtitleOptions != null) {
            mpv.setPropertyDouble("sub-scale", subtitleOptions.scale);
            mpv.setPropertyDouble("sub-pos", subtitleOptions.position);
        }
    }

    private static void command(String... args) {
        try {
            MPVLib.getInstance().command(args);
        } catch (Throwable e) {
            Log.d(TAG, "command failed: " + e);
        }
    }

    private void detach() {
        MPVLib.removeObserver(this);
        initialized = false;
    }

    private void notifyListeners(java.util.function.Consumer<Player.Listener> action) {
        if (released) return;
        for (Player.Listener listener : new ArrayList<>(listeners)) {
            try {
                action.accept(listener);
            } catch (Throwable e) {
                Log.d(TAG, "listener failed: " + e);
            }
        }
    }

    // ----- mpv events (mpv's own event thread) ------------------------------

    @Override
    public void eventProperty(String property) {
    }

    @Override
    public void eventProperty(String property, long value) {
        switch (property) {
            case "video-params/w", "video-params/h" -> {
                int w = "video-params/w".equals(property) ? (int) value : videoWidth;
                int h = "video-params/h".equals(property) ? (int) value : videoHeight;
                if (w == videoWidth && h == videoHeight) return;
                videoWidth = w;
                videoHeight = h;
                // media3 drives PlayerView's shutter and aspect ratio off this callback, and
                // it is the only place a player can tell the view "there really is a picture".
                post(() -> {
                    if (!released && videoWidth > 0 && videoHeight > 0) {
                        notifyListeners(listener -> listener.onVideoSizeChanged(getVideoSize()));
                    }
                });
            }
            case "audio-params/channel-count" -> audioChannelCount = (int) value;
            default -> {
            }
        }
    }

    @Override
    public void eventProperty(String property, boolean value) {
        switch (property) {
            case "pause" -> post(() -> {
                boolean nowPlaying = !value && playbackState == STATE_READY;
                if (nowPlaying == playing) return;
                playing = nowPlaying;
                notifyListeners(listener -> listener.onIsPlayingChanged(nowPlaying));
            });
            case "core-idle" -> {
                // Nothing to act on by itself: readiness is driven by MPV_EVENT_FILE_LOADED
                // and termination by eof-reached / END_FILE. Kept observed because it is the
                // cheapest way to see that the core is alive at all when debugging.
            }
            case "eof-reached" -> {
                if (value) post(() -> setPlaybackState(STATE_ENDED));
            }
            default -> {
            }
        }
    }

    @Override
    public void eventProperty(String property, double value) {
        switch (property) {
            case "time-pos" -> positionMs = Math.max(0, (long) (value * 1000));
            case "duration" -> durationMs = value > 0 ? (long) (value * 1000) : C.TIME_UNSET;
            case "volume" -> {
                float v = (float) value;
                if (Math.abs(v - volume) > 0.001f) {
                    volume = v;
                    post(() -> notifyListeners(listener -> listener.onVolumeChanged(v)));
                }
            }
            case "speed" -> {
                float speed = (float) value;
                if (speed != playbackParameters.speed) {
                    playbackParameters = new PlaybackParameters(speed);
                    post(() -> notifyListeners(listener -> listener.onPlaybackParametersChanged(playbackParameters)));
                }
            }
            default -> {
            }
        }
    }

    @Override
    public void eventProperty(String property, String value) {
    }

    @Override
    public void event(int eventId) {
        switch (eventId) {
            case MPVLib.MPV_EVENT_START_FILE -> post(() -> {
                fileLoaded = false;
                durationMs = C.TIME_UNSET;
                positionMs = 0;
                if (playbackState != STATE_IDLE) setPlaybackState(STATE_BUFFERING);
            });
            case MPVLib.MPV_EVENT_FILE_LOADED -> post(() -> {
                fileLoaded = true;
                setPlaybackState(STATE_READY);
                // The video output only exists from here on, so VO-private options can be set.
                applyPropertyOverrides(MPVLib.getInstance());
            });
            case MPVLib.MPV_EVENT_END_FILE -> post(() -> {
                if (!fileLoaded) {
                    notifyError("MPV failed to open the source");
                    return;
                }
                setPlaybackState(STATE_ENDED);
            });
            case MPVLib.MPV_EVENT_SEEK -> post(() -> {
            });
            default -> {
            }
        }
    }

    private void post(Runnable runnable) {
        if (released) return;
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run();
        else handler.post(runnable);
    }

    private void setPlaybackState(int state) {
        if (released || playbackState == state) return;
        playbackState = state;
        boolean nowPlaying = state == STATE_READY && playWhenReady;
        if (nowPlaying != playing) {
            playing = nowPlaying;
            notifyListeners(listener -> listener.onIsPlayingChanged(nowPlaying));
        }
        notifyListeners(listener -> listener.onPlaybackStateChanged(state));
    }

    private void notifyError(String message) {
        PlaybackException error = new PlaybackException(message,
                new IllegalStateException(message), PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        notifyListeners(listener -> listener.onPlayerError(error));
    }

    // ----- mpv-only surface used by the app ----------------------------------

    public void addListener(Player.Listener listener) {

        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Player.Listener listener) {
        listeners.remove(listener);
    }

    public void setMediaItem(MediaItem item, long startPositionMs) {
        mediaItems.clear();
        if (item != null) mediaItems.add(item);
        positionMs = startPositionMs;
    }

    public void prepare() {
        MediaItem item = getCurrentMediaItem();
        if (item == null || item.localConfiguration == null || item.localConfiguration.uri == null) return;
        String url = item.localConfiguration.uri.toString();
        if (url.isEmpty()) return;
        // vo=mediacodec_embed aborts the process (SIGABRT, not a Java exception) when it
        // is asked to open a video track with no wid, and wid can only be set before
        // mpv_initialize. So a prepare that arrives before the Surface waits for it
        // instead of racing it.
        if (!initialized && surface == null) {
            pendingPrepare = true;
            handler.removeCallbacks(surfaceTimeout);
            handler.postDelayed(surfaceTimeout, SURFACE_TIMEOUT_MS);
            Log.d(TAG, "prepare deferred: waiting for a Surface");
            return;
        }
        load(url);
    }

    private void runPendingPrepare() {
        pendingPrepare = false;
        handler.removeCallbacks(surfaceTimeout);
        MediaItem item = getCurrentMediaItem();
        if (item == null || item.localConfiguration == null || item.localConfiguration.uri == null) return;
        load(item.localConfiguration.uri.toString());
    }

    /** Audio-only safety net: never let a missing Surface stall playback forever. */
    private final Runnable surfaceTimeout = this::runPendingPrepare;

    private void load(String url) {
        try {
            ensureInitialized();
        } catch (Throwable e) {
            // Red line: a failed mpv core must surface as a playback error the player
            // framework already handles, never as a crash on the main thread.
            notifyError("MPV init failed: " + e);
            return;
        }
        fileLoaded = false;
        command("loadfile", url, "replace");
        applySubtitleOptions();
        scheduleShots();
    }

    // ----- pixel evidence ----------------------------------------------------

    private int shotIndex;

    private final Runnable shotTask = new Runnable() {
        @Override
        public void run() {
            if (released || !initialized) return;
            captureShot(shotIndex++);
            if (shotIndex < SHOT_DELAYS_MS.length) {
                handler.postDelayed(this, SHOT_DELAYS_MS[shotIndex] - SHOT_DELAYS_MS[shotIndex - 1]);
            }
        }
    };

    private void scheduleShots() {
        handler.removeCallbacks(shotTask);
        handler.removeCallbacks(positionTask);
        if (!evidenceEnabled()) {
            Log.i(TAG, "evidence capture off (set evidence=1 in " + OVERRIDE_FILE + " to enable)");
            return;
        }
        shotIndex = 0;
        handler.postDelayed(shotTask, SHOT_DELAYS_MS[0]);
        handler.postDelayed(positionTask, 1000);
    }

    /**
     * mpv's own {@code screenshot} command writes several megabytes per capture, so the
     * capture and the position trace stay off unless a root shell asks for them.
     */
    private boolean evidenceEnabled() {
        if (overrides == null) overrides = readOverrides();
        return "1".equals(overrides.get("evidence"));
    }

    /**
     * Asks mpv for the frame it is rendering right now. mpv 0.41's {@code screenshot} command
     * only takes a flags argument -- the file name always comes from {@code --screenshot-template}
     * -- so the new files are found by diffing the directory instead of naming them up front.
     * The time position is logged next to each capture: identical PNGs at different positions
     * mean a stalled source rather than a stalled output.
     */
    private void captureShot(int index) {
        String dir = shotDir();
        // mpv refuses to overwrite, and this libmpv does not substitute the #### placeholder,
        // so every earlier capture is cleared first and the new name is found by diffing.
        for (String name : shotFiles(dir)) {
            //noinspection ResultOfMethodCallIgnored
            new File(dir, name).delete();
        }
        command("screenshot", "video");
        handler.postDelayed(() -> {
            List<String> after = shotFiles(dir);
            Log.i(TAG, "shot[" + index + "] t-pos=" + property("time-pos")
                    + " new=" + after + " cache=" + property("paused-for-cache")
                    + " vo=" + property("current-vo")
                    + " osd=" + property("osd-width") + "x" + property("osd-height"));
            for (String name : after) {
                File file = new File(dir, name);
                Log.i(TAG, "shot[" + index + "] " + file.getAbsolutePath()
                        + " bytes=" + file.length());
            }
        }, 1500);
    }

    private static List<String> shotFiles(String dir) {
        List<String> names = new ArrayList<>();
        File[] files = new File(dir).listFiles();
        if (files == null) return names;
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(SHOT_NAME) && name.endsWith(".png")) names.add(name);
        }
        return names;
    }

    private static String property(String name) {
        try {
            String value = MPVLib.getInstance().getPropertyString(name);
            return value == null ? "?" : value;
        } catch (Throwable e) {
            return "?";
        }
    }

    private final Runnable positionTask = new Runnable() {
        @Override
        public void run() {
            if (released || !playing) return;
            Log.i(TAG, "tick t-pos=" + property("time-pos")
                    + " video-params=" + property("video-params")
                    + " cache=" + property("paused-for-cache")
                    + " dropped=" + property("vo-drop-frame-count"));
            handler.postDelayed(this, 2000);
        }
    };

    public void play() {
        setPlayWhenReady(true);
    }

    public void stop() {
        if (initialized) {
            command("stop");
            command("set", "idle", "yes");
        }
        fileLoaded = false;
        setPlaybackState(STATE_IDLE);
    }

    public void release() {
        if (released) return;
        released = true;
        MPVLib.removeObserver(this);
        MPVLib.removeLogObserver(LOG_OBSERVER);
        handler.removeCallbacks(shotTask);
        handler.removeCallbacks(positionTask);
        listeners.clear();
        mediaItems.clear();
        if (active != this) {
            // Another MpvPlayer already took the core over; destroying it here would kill
            // the live one. Just drop our references.
            initialized = false;
            return;
        }
        active = null;
        initialized = false;
        if (coreCreated) {
            try {
                MPVLib.getInstance().destroy();
            } catch (Throwable e) {
                Log.d(TAG, "destroy failed: " + e);
            }
            coreCreated = false;
            coreInitialized = false;
        }
    }

    // ----- Player contract ---------------------------------------------------

    @Override
    public Looper getApplicationLooper() {
        return Looper.getMainLooper();
    }

    @Override
    public void setMediaItems(List<MediaItem> items) {
        mediaItems.clear();
        if (items != null) mediaItems.addAll(items);
    }

    @Override
    public void setMediaItems(List<MediaItem> items, boolean resetPosition) {
        setMediaItems(items);
        if (resetPosition) positionMs = 0;
    }

    @Override
    public void setMediaItems(List<MediaItem> items, int startIndex, long startPositionMs) {
        setMediaItems(items);
        positionMs = startPositionMs;
    }

    @Override
    public void setMediaItem(MediaItem item) {
        setMediaItem(item, 0);
    }

    @Override
    public void setMediaItem(MediaItem item, boolean startPosition) {
        setMediaItem(item, startPosition ? 0 : positionMs);
    }

    @Override
    public void addMediaItem(MediaItem item) {
        if (item != null) mediaItems.add(item);
    }

    @Override
    public void addMediaItem(int index, MediaItem item) {
        if (item == null) return;
        mediaItems.add(Math.min(index, mediaItems.size()), item);
    }

    @Override
    public void addMediaItems(List<MediaItem> items) {
        if (items != null) mediaItems.addAll(items);
    }

    @Override
    public void addMediaItems(int index, List<MediaItem> items) {
        if (items == null || items.isEmpty()) return;
        mediaItems.addAll(Math.min(index, mediaItems.size()), items);
    }

    @Override
    public void moveMediaItem(int currentIndex, int newIndex) {
        if (currentIndex >= 0 && currentIndex < mediaItems.size()) {
            MediaItem item = mediaItems.remove(currentIndex);
            mediaItems.add(Math.clamp(newIndex, 0, mediaItems.size()), item);
        }
    }

    @Override
    public void moveMediaItems(int fromIndex, int toIndex, int newIndex) {
        // single-item playlist in practice; a no-op keeps the contract honest
    }

    @Override
    public void replaceMediaItem(int index, MediaItem item) {
        if (index >= 0 && index < mediaItems.size()) mediaItems.set(index, item);
    }

    @Override
    public void replaceMediaItems(int fromIndex, int toIndex, List<MediaItem> items) {
        // no-op
    }

    @Override
    public void removeMediaItem(int index) {
        if (index >= 0 && index < mediaItems.size()) mediaItems.remove(index);
    }

    @Override
    public void removeMediaItems(int fromIndex, int toIndex) {
        // no-op
    }

    @Override
    public void clearMediaItems() {
        mediaItems.clear();
    }

    @Override
    public boolean isCommandAvailable(int command) {
        return EngineCommands.isAvailable(command);
    }

    @Override
    public boolean canAdvertiseSession() {
        // Matches ExoPlayer. media3's MediaSession.BuilderBase asserts on this in its
        // constructor, so returning false took PlaybackService.onCreate down with a bare
        // IllegalArgumentException (no message) before playback even started.
        return true;
    }

    @Override
    public Commands getAvailableCommands() {
        return EngineCommands.all();
    }

    @Override
    public int getPlaybackState() {
        return playbackState;
    }

    @Override
    public int getPlaybackSuppressionReason() {
        return PLAYBACK_SUPPRESSION_REASON_NONE;
    }

    @Override
    public boolean isPlaying() {
        return playing;
    }

    @Override
    public PlaybackException getPlayerError() {
        return null;
    }

    @Override
    public void pause() {
        setPlayWhenReady(false);
    }

    @Override
    public void setPlayWhenReady(boolean playWhenReady) {
        this.playWhenReady = playWhenReady;
        if (!initialized) return;
        MPVLib.getInstance().setPropertyBoolean("pause", !playWhenReady);
        boolean nowPlaying = playWhenReady && playbackState == STATE_READY;
        if (nowPlaying == playing) return;
        playing = nowPlaying;
        notifyListeners(listener -> listener.onIsPlayingChanged(nowPlaying));
    }

    @Override
    public boolean getPlayWhenReady() {
        return playWhenReady;
    }

    @Override
    public void setRepeatMode(int repeatMode) {
        this.repeatMode = repeatMode;
    }

    @Override
    public int getRepeatMode() {
        return repeatMode;
    }

    @Override
    public void setShuffleModeEnabled(boolean shuffleModeEnabled) {
    }

    @Override
    public boolean getShuffleModeEnabled() {
        return false;
    }

    @Override
    public boolean isLoading() {
        return playbackState == STATE_BUFFERING;
    }

    @Override
    public void seekToDefaultPosition() {
        seekTo(0);
    }

    @Override
    public void seekToDefaultPosition(int mediaItemIndex) {
        seekTo(0);
    }

    @Override
    public void seekTo(long positionMs) {
        this.positionMs = Math.max(0, positionMs);
        if (!initialized) return;
        command("seek", String.format(java.util.Locale.US, "%.3f", this.positionMs / 1000.0), "absolute");
    }

    @Override
    public void seekTo(int mediaItemIndex, long positionMs) {
        seekTo(positionMs);
    }

    @Override
    public long getSeekBackIncrement() {
        return C.DEFAULT_SEEK_BACK_INCREMENT_MS;
    }

    @Override
    public void seekBack() {
        seekTo(Math.max(0, getCurrentPosition() - getSeekBackIncrement()));
    }

    @Override
    public long getSeekForwardIncrement() {
        return C.DEFAULT_SEEK_FORWARD_INCREMENT_MS;
    }

    @Override
    public void seekForward() {
        seekTo(getCurrentPosition() + getSeekForwardIncrement());
    }

    @Override
    public boolean hasPreviousMediaItem() {
        return false;
    }

    @Override
    public void seekToPreviousMediaItem() {
    }

    @Override
    public long getMaxSeekToPreviousPosition() {
        return 0;
    }

    @Override
    public void seekToPrevious() {
    }

    @Override
    public boolean hasNextMediaItem() {
        return false;
    }

    @Override
    public void seekToNextMediaItem() {
    }

    @Override
    public void seekToNext() {
    }

    @Override
    public void setPlaybackParameters(PlaybackParameters playbackParameters) {
        if (playbackParameters == null) return;
        this.playbackParameters = playbackParameters;
        if (!initialized) return;
        MPVLib.getInstance().setPropertyDouble("speed", playbackParameters.speed);
    }

    @Override
    public void setPlaybackSpeed(float speed) {
        setPlaybackParameters(new PlaybackParameters(speed));
    }

    @Override
    public PlaybackParameters getPlaybackParameters() {
        return playbackParameters;
    }

    @Override
    public Tracks getCurrentTracks() {
        // Honest: enumerating mpv's "track-list" needs MPV_FORMAT_NODE, which this JNI
        // bridge does not expose, so the track panel stays hidden on mpv (as before).
        return Tracks.EMPTY;
    }

    @Override
    public TrackSelectionParameters getTrackSelectionParameters() {
        return trackSelectionParameters;
    }

    @Override
    public void setTrackSelectionParameters(TrackSelectionParameters parameters) {
        if (parameters != null) this.trackSelectionParameters = parameters;
    }

    @Override
    public MediaMetadata getMediaMetadata() {
        MediaItem item = getCurrentMediaItem();
        return item == null ? MediaMetadata.EMPTY : item.mediaMetadata;
    }

    @Override
    public MediaMetadata getPlaylistMetadata() {
        return MediaMetadata.EMPTY;
    }

    @Override
    public void setPlaylistMetadata(MediaMetadata mediaMetadata) {
    }

    @Override
    public Object getCurrentManifest() {
        return null;
    }

    @Override
    public Timeline getCurrentTimeline() {
        return Timeline.EMPTY;
    }

    @Override
    public int getCurrentPeriodIndex() {
        return 0;
    }

    @Override
    public int getCurrentWindowIndex() {
        return 0;
    }

    @Override
    public int getCurrentMediaItemIndex() {
        return 0;
    }

    @Override
    public int getNextWindowIndex() {
        return C.INDEX_UNSET;
    }

    @Override
    public int getNextMediaItemIndex() {
        return C.INDEX_UNSET;
    }

    @Override
    public int getPreviousWindowIndex() {
        return C.INDEX_UNSET;
    }

    @Override
    public int getPreviousMediaItemIndex() {
        return C.INDEX_UNSET;
    }

    @Override
    public MediaItem getCurrentMediaItem() {
        return mediaItems.isEmpty() ? null : mediaItems.get(0);
    }

    @Override
    public int getMediaItemCount() {
        return mediaItems.size();
    }

    @Override
    public MediaItem getMediaItemAt(int index) {
        return index >= 0 && index < mediaItems.size() ? mediaItems.get(index) : null;
    }

    @Override
    public long getDuration() {
        return durationMs;
    }

    @Override
    public long getCurrentPosition() {
        return positionMs;
    }

    @Override
    public long getBufferedPosition() {
        return positionMs;
    }

    @Override
    public int getBufferedPercentage() {
        if (durationMs <= 0) return 0;
        return (int) Math.clamp(positionMs * 100 / durationMs, 0, 100);
    }

    @Override
    public long getTotalBufferedDuration() {
        return 0;
    }

    @Override
    public boolean isCurrentWindowDynamic() {
        return false;
    }

    @Override
    public boolean isCurrentMediaItemDynamic() {
        return false;
    }

    @Override
    public boolean isCurrentWindowLive() {
        return durationMs <= 0 && playbackState == STATE_READY;
    }

    @Override
    public boolean isCurrentMediaItemLive() {
        return isCurrentWindowLive();
    }

    @Override
    public long getCurrentLiveOffset() {
        return C.TIME_UNSET;
    }

    @Override
    public boolean isCurrentWindowSeekable() {
        return playbackState == STATE_READY;
    }

    @Override
    public boolean isCurrentMediaItemSeekable() {
        return isCurrentWindowSeekable();
    }

    @Override
    public boolean isPlayingAd() {
        return false;
    }

    @Override
    public int getCurrentAdGroupIndex() {
        return C.INDEX_UNSET;
    }

    @Override
    public int getCurrentAdIndexInAdGroup() {
        return C.INDEX_UNSET;
    }

    @Override
    public long getContentDuration() {
        return durationMs;
    }

    @Override
    public long getContentPosition() {
        return positionMs;
    }

    @Override
    public long getContentBufferedPosition() {
        return positionMs;
    }

    @Override
    public AudioAttributes getAudioAttributes() {
        return AudioAttributes.DEFAULT;
    }

    @Override
    public void setVolume(float volume) {
        this.volume = volume;
        if (!initialized) return;
        MPVLib.getInstance().setPropertyDouble("volume", volume);
    }

    @Override
    public float getVolume() {
        return volume;
    }

    @Override
    public void clearVideoSurface() {
        setVideoSurface(null);
    }

    @Override
    public void clearVideoSurface(@Nullable Surface surface) {
        if (surface == this.surface) setVideoSurface(null);
    }

    @Override
    public void setVideoSurface(@Nullable Surface surface) {
        if (this.surface == surface) return;
        this.surface = surface;
        if (initialized) {
            // wid can no longer be set once mpv is initialized; re-attaching is a
            // best-effort no-op. See the class note for why this ordering is safe.
            try {
                if (surface != null) MPVLib.getInstance().attachSurface(surface);
                else MPVLib.getInstance().detachSurface();
            } catch (Throwable e) {
                Log.d(TAG, "attach surface failed: " + e);
            }
        } else if (surface != null && pendingPrepare) {
            runPendingPrepare();
        }
    }

    @Override
    public void setVideoSurfaceHolder(@Nullable SurfaceHolder surfaceHolder) {
        unregisterSurfaceCallback();
        if (surfaceHolder == null) {
            setVideoSurface(null);
            return;
        }
        Surface ready = surfaceHolder.getSurface();
        if (ready != null && ready.isValid()) {
            setVideoSurface(ready);
            return;
        }
        // The view has not produced its Surface yet. Watch the holder ourselves:
        // vo=mediacodec_embed cannot start without wid, and wid can only be set before
        // mpv_initialize, so whoever hears "surfaceCreated" first must be us.
        surfaceCallback = new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                setVideoSurface(holder.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                setVideoSurface(holder.getSurface());
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                setVideoSurface(null);
            }
        };
        surfaceHolder.addCallback(surfaceCallback);
        Log.d(TAG, "surface holder registered, waiting for surfaceCreated");
    }

    @Override
    public void clearVideoSurfaceHolder(@Nullable SurfaceHolder surfaceHolder) {
        unregisterSurfaceCallback();
        clearVideoSurface();
    }

    private void unregisterSurfaceCallback() {
        if (surfaceCallback == null) return;
        SurfaceHolder holder = surfaceCallbackHolder;
        if (holder != null) holder.removeCallback(surfaceCallback);
        surfaceCallback = null;
        surfaceCallbackHolder = null;
    }

    @Override
    public void setVideoSurfaceView(@Nullable SurfaceView surfaceView) {

        // media3's PlayerView hands the view (not the Surface) to a SurfaceView-backed
        // player, so this is the path that actually gets us the Surface -- and, when the
        // view has not been laid out yet, the path that registers for it.
        if (useTextureView()) {
            TextureView view = textureView;
            if (view == null || view.getParent() == null) view = installTextureView(surfaceView);
            if (view != null) {
                bindTextureView(view);
                return;
            }
            Log.w(TAG, "no room for a TextureView, falling back to the SurfaceView");
        }
        surfaceCallbackHolder = surfaceView == null ? null : surfaceView.getHolder();
        setVideoSurfaceHolder(surfaceCallbackHolder);
    }

    /**
     * Swaps the PlayerView's SurfaceView for a TextureView sitting right above it: same
     * parent (media3 puts the surface inside its AspectRatioFrameLayout, so the picture keeps
     * the geometry the view was measured with), same LayoutParams, one index further up so
     * the controller still draws on top. The SurfaceView is only hidden, not detached, because
     * PlayerView keeps handing it back on every setPlayer() -- including to a brand new
     * MpvPlayer on the next playback -- and a detached view has no parent to host the swap.
     */
    private TextureView installTextureView(SurfaceView surfaceView) {
        ViewGroup parent = surfaceView != null && surfaceView.getParent() instanceof ViewGroup group ? group : null;
        if (parent == null) return null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (TEXTURE_TAG.equals(child.getTag())) return (TextureView) child;
        }
        ViewGroup.LayoutParams source = surfaceView.getLayoutParams();
        ViewGroup.LayoutParams params = source == null
                ? new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                : new ViewGroup.LayoutParams(source);
        TextureView view = new TextureView(surfaceView.getContext());
        view.setOpaque(true);
        view.setTag(TEXTURE_TAG);
        int index = parent.indexOfChild(surfaceView);
        parent.addView(view, index + 1, params);
        surfaceView.setVisibility(View.GONE);
        textureView = view;
        Log.i(TAG, "TextureView installed above the SurfaceView (index " + index + ")");
        return view;
    }

    private void bindTextureView(TextureView view) {
        if (view.getSurfaceTexture() == null) {
            if (textureListener == null) {
                textureListener = new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                        setVideoSurface(new Surface(texture));
                    }

                    @Override
                    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                        setVideoSurface(null);
                        return true;
                    }

                    @Override
                    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                    }
                };
            }
            view.setSurfaceTextureListener(textureListener);
            Log.i(TAG, "waiting for onSurfaceTextureAvailable");
            return;
        }
        setVideoSurface(new Surface(view.getSurfaceTexture()));
    }

    @Override
    public void clearVideoSurfaceView(@Nullable SurfaceView surfaceView) {
        if (textureView != null && textureListener != null) textureView.setSurfaceTextureListener(null);
        textureListener = null;
        unregisterSurfaceCallback();
        clearVideoSurface();
    }

    @Override
    public void setVideoTextureView(@Nullable TextureView textureView) {

        if (textureView == null || textureView.getSurfaceTexture() == null) return;
        setVideoSurface(new Surface(textureView.getSurfaceTexture()));
    }

    @Override
    public void clearVideoTextureView(@Nullable TextureView textureView) {
        clearVideoSurface();
    }

    @Override
    public VideoSize getVideoSize() {
        if (videoWidth <= 0 || videoHeight <= 0) return VideoSize.UNKNOWN;
        return new VideoSize(videoWidth, videoHeight);
    }

    @Override
    public Size getSurfaceSize() {
        return Size.UNKNOWN;
    }

    @Override
    public CueGroup getCurrentCues() {
        return CueGroup.EMPTY_TIME_ZERO;
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return DeviceInfo.UNKNOWN;
    }

    @Override
    public int getDeviceVolume() {
        return 0;
    }

    @Override
    public boolean isDeviceMuted() {
        return false;
    }

    @Override
    public void setDeviceVolume(int volume) {
    }

    @Override
    public void setDeviceVolume(int volume, int flags) {
    }

    @Override
    public void increaseDeviceVolume() {
    }

    @Override
    public void increaseDeviceVolume(int flags) {
    }

    @Override
    public void decreaseDeviceVolume() {
    }

    @Override
    public void decreaseDeviceVolume(int flags) {
    }

    @Override
    public void setDeviceMuted(boolean muted) {
    }

    @Override
    public void setDeviceMuted(boolean muted, int flags) {
    }

    @Override
    public void setAudioAttributes(AudioAttributes audioAttributes, boolean handleAudioFocus) {
    }

    /** mpv's audio output report, forwarded to the effect layer by the engine. */
    public void notifyAudioOutput() {
        AudioOutputListener listener = audioOutputListener;
        if (listener != null) listener.onAudioOutput();
    }
}