package is.xyz.mpv;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Surface;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Java binding for mpv-android's prebuilt JNI bridge ({@code libplayer.so}).
 *
 * <p><b>Why this package name is {@code is.xyz.mpv}:</b> the native library binds by JNI
 * short-name mangling, i.e. it exports exactly {@code Java_is_xyz_mpv_MPVLib_create},
 * {@code Java_is_xyz_mpv_MPVLib_command}, ... . Reusing mpv-android's own class name is what
 * lets us reuse its already-compiled, battle-tested JNI layer instead of maintaining a second
 * hand-written copy of the same mpv client-API bindings.
 *
 * <p><b>What this buys us:</b> {@code libmpv.so} alone is just the mpv core; something has to
 * bridge it to the JVM. This class + {@code libplayer.so} is that bridge, so no NDK build step
 * and no {@code build.gradle} change are required to get a working mpv player.
 *
 * <p><b>Threading:</b> mpv callbacks are delivered on mpv's own event thread, so observers are
 * held in a {@link CopyOnWriteArrayList} and must do their own Android UI work via a Handler.
 *
 * <p>ASCII-only comments on purpose (javac defaults to GBK on Chinese Windows and mangles
 * UTF-8 CJK bytes into illegal-character errors).
 */
public final class MPVLib {

    // MpvFormat: must match MPV_FORMAT_* from mpv/client.h.
    public static final int MPV_FORMAT_NONE = 0;
    public static final int MPV_FORMAT_STRING = 1;
    public static final int MPV_FORMAT_OSD_STRING = 2;
    public static final int MPV_FORMAT_FLAG = 3;
    public static final int MPV_FORMAT_INT64 = 4;
    public static final int MPV_FORMAT_DOUBLE = 5;
    public static final int MPV_FORMAT_NODE = 6;
    public static final int MPV_FORMAT_NODE_ARRAY = 7;
    public static final int MPV_FORMAT_NODE_MAP = 8;
    public static final int MPV_FORMAT_BYTE_ARRAY = 9;

    // MpvEvent: must match MPV_EVENT_* from mpv/client.h.
    public static final int MPV_EVENT_NONE = 0;
    public static final int MPV_EVENT_SHUTDOWN = 1;
    public static final int MPV_EVENT_LOG_MESSAGE = 2;
    public static final int MPV_EVENT_GET_PROPERTY_REPLY = 3;
    public static final int MPV_EVENT_SET_PROPERTY_REPLY = 4;
    public static final int MPV_EVENT_COMMAND_REPLY = 5;
    public static final int MPV_EVENT_START_FILE = 6;
    public static final int MPV_EVENT_END_FILE = 7;
    public static final int MPV_EVENT_FILE_LOADED = 8;
    public static final int MPV_EVENT_IDLE = 11;
    public static final int MPV_EVENT_TICK = 14;
    public static final int MPV_EVENT_CLIENT_MESSAGE = 16;
    public static final int MPV_EVENT_VIDEO_RECONFIG = 17;
    public static final int MPV_EVENT_AUDIO_RECONFIG = 18;
    public static final int MPV_EVENT_SEEK = 20;
    public static final int MPV_EVENT_PLAYBACK_RESTART = 21;
    public static final int MPV_EVENT_PROPERTY_CHANGE = 22;
    public static final int MPV_EVENT_QUEUE_OVERFLOW = 24;
    public static final int MPV_EVENT_HOOK = 25;

    // MpvLogLevel: must match MPV_LOG_LEVEL_* from mpv/client.h.
    public static final int MPV_LOG_LEVEL_NONE = 0;
    public static final int MPV_LOG_LEVEL_FATAL = 10;
    public static final int MPV_LOG_LEVEL_ERROR = 20;
    public static final int MPV_LOG_LEVEL_WARN = 30;
    public static final int MPV_LOG_LEVEL_INFO = 40;
    public static final int MPV_LOG_LEVEL_V = 50;
    public static final int MPV_LOG_LEVEL_DEBUG = 60;
    public static final int MPV_LOG_LEVEL_TRACE = 70;

    /** True once both native libraries loaded successfully. */
    private static volatile boolean available;

    private static final MPVLib INSTANCE = new MPVLib();

    static {
        boolean ok;
        try {
            System.loadLibrary("mpv");
            System.loadLibrary("player");
            ok = true;
        } catch (UnsatisfiedLinkError e) {
            ok = false;
        }
        available = ok;
    }

    /** Whether libmpv.so + libplayer.so are present and loadable in this process. */
    public static boolean isAvailable() {
        return available;
    }

    /** Forces class loading so {@link #isAvailable()} reflects a real load attempt. */
    public static void ensureLoaded() {
        // touching the class runs the static initializer
    }

    public static MPVLib getInstance() {
        return INSTANCE;
    }

    private MPVLib() {
    }

    // ----- native (bound to Java_is_xyz_mpv_MPVLib_*) ------------------------

    public native void create(Context appctx);

    public native void init();

    public native void destroy();

    public native void attachSurface(Surface surface);

    public native void detachSurface();

    public native void command(String[] cmd);

    public native int setOptionString(String name, String value);

    public native Bitmap grabThumbnail(int dimension);

    public native Integer getPropertyInt(String property);

    public native void setPropertyInt(String property, int value);

    public native Double getPropertyDouble(String property);

    public native void setPropertyDouble(String property, double value);

    public native Boolean getPropertyBoolean(String property);

    public native void setPropertyBoolean(String property, boolean value);

    public native String getPropertyString(String property);

    public native void setPropertyString(String property, String value);

    public native void observeProperty(String property, int format);

    // ----- callbacks invoked from the native event thread --------------------

    public interface EventObserver {
        void eventProperty(String property);

        void eventProperty(String property, long value);

        void eventProperty(String property, boolean value);

        void eventProperty(String property, String value);

        void eventProperty(String property, double value);

        void event(int eventId);
    }

    public interface LogObserver {
        void logMessage(String prefix, int level, String text);
    }

    private static final CopyOnWriteArrayList<EventObserver> OBSERVERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<LogObserver> LOG_OBSERVERS = new CopyOnWriteArrayList<>();

    public static void addObserver(EventObserver observer) {
        if (observer != null) OBSERVERS.addIfAbsent(observer);
    }

    public static void removeObserver(EventObserver observer) {
        OBSERVERS.remove(observer);
    }

    public static void addLogObserver(LogObserver observer) {
        if (observer != null) LOG_OBSERVERS.addIfAbsent(observer);
    }

    public static void removeLogObserver(LogObserver observer) {
        LOG_OBSERVERS.remove(observer);
    }

    // Signatures below are looked up by name+descriptor from native code
    // (see jni_utils.cpp init_methods_cache); do not rename or re-type them.

    public static void eventProperty(String property) {
        for (EventObserver o : OBSERVERS) o.eventProperty(property);
    }

    public static void eventProperty(String property, boolean value) {
        for (EventObserver o : OBSERVERS) o.eventProperty(property, value);
    }

    public static void eventProperty(String property, long value) {
        for (EventObserver o : OBSERVERS) o.eventProperty(property, value);
    }

    public static void eventProperty(String property, double value) {
        for (EventObserver o : OBSERVERS) o.eventProperty(property, value);
    }

    public static void eventProperty(String property, String value) {
        for (EventObserver o : OBSERVERS) o.eventProperty(property, value);
    }

    public static void event(int eventId) {
        for (EventObserver o : OBSERVERS) o.event(eventId);
    }

    public static void logMessage(String prefix, int level, String text) {
        for (LogObserver o : LOG_OBSERVERS) o.logMessage(prefix, level, text);
    }

    // ----- convenience helpers ----------------------------------------------

    public void commandArray(String... cmd) {
        command(cmd);
    }

    public void loadFile(String url, String flags) {
        commandArray("loadfile", url, flags == null ? "replace" : flags);
    }

    public void setProperty(String name, String value) {
        setPropertyString(name, value);
    }
}