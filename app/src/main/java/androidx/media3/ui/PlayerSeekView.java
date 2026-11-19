package androidx.media3.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Player;

/**
 * [LOCAL COMPILE STUB] -- this is NOT a stub in the "does nothing" sense.
 *
 * <p>WHY: {@code PlayerSeekView} belongs to upstream FongMi's Media3 fork and is
 * inflated by name from five layouts in this repo ({@code view_control_vod.xml} and
 * {@code view_control_cast.xml} in leanback, {@code view_control_vod.xml} and
 * {@code view_control_live.xml} in mobile). No published Media3 release ships it.
 *
 * <p>Because the layouts inflate it, an inert placeholder would either crash the
 * inflater or leave a dead gap where the progress bar belongs. So this class is
 * reimplemented on top of the official, fully functional {@link DefaultTimeBar},
 * reusing the standard progress-bar constants ({@link DefaultTimeBar#DEFAULT_BAR_HEIGHT_DP},
 * {@code DEFAULT_PLAYED_COLOR}, ...) so it looks the same as the rest of the UI.
 *
 * <p>Everything the app actually calls is provided:
 * <pre>
 *   PlaybackActivity.setSeekNextFocusDown -> findViewById(R.id.exo_progress)
 *   PlaybackActivity.onControllerConnected  -> setPlayer(controller)
 *   PlaybackActivity.addSeekListener        -> getTimeBar().addListener(...)
 *   PlaybackActivity.updateKeyIncrement     -> getTimeBar().setKeyTimeIncrement(...)
 *   PlaybackActivity.onRelease              -> setPlayer(null)
 * </pre>
 * The child is given the id {@code androidx.media3.ui.R.id.exo_progress} (an id that
 * does exist in the official {@code media3-ui} R class), which is what the
 * {@code findViewById} call above looks up.
 *
 * <p>Scrub handling mirrors the official {@code PlayerControlView}: pause while the
 * user drags, push positions to the bar while dragging, seek and restore playback on
 * release. That means dragging the bar actually works.
 *
 * <p>ASCII-only comments on purpose (javac defaults to GBK on Chinese Windows and
 * mangles UTF-8 CJK bytes into illegal-character errors).
 */
public class PlayerSeekView extends FrameLayout {

    private static final int PROGRESS_INTERVAL_PLAYING_MS = 500;

    private static final int PROGRESS_INTERVAL_IDLE_MS = 1000;

    private static final int BAR_HEIGHT_DP = DefaultTimeBar.DEFAULT_BAR_HEIGHT_DP;

    private final DefaultTimeBar timeBar;

    private final TimeBar.OnScrubListener scrubListener = new TimeBar.OnScrubListener() {

        @Override
        public void onScrubStart(@NonNull TimeBar timeBar, long position) {
            if (player == null) return;
            wasPlaying = player.isPlaying();
            if (wasPlaying) player.pause();
            syncBar();
        }

        @Override
        public void onScrubMove(@NonNull TimeBar timeBar, long position) {
            if (player == null) return;
            timeBar.setPosition(position);
        }

        @Override
        public void onScrubStop(@NonNull TimeBar timeBar, long position, boolean canceled) {
            if (player == null) return;
            if (!canceled && player.getCurrentPosition() != position) player.seekTo(position);
            if (wasPlaying) player.play();
            wasPlaying = false;
            syncBar();
        }
    };

    private final Player.Listener playerListener = new Player.Listener() {

        @Override
        public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
            syncBar();
        }
    };

    @Nullable
    private Player player;

    private boolean wasPlaying;

    public PlayerSeekView(@NonNull Context context) {
        this(context, null);
    }

    public PlayerSeekView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PlayerSeekView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        timeBar = new DefaultTimeBar(context, attrs, defStyleAttr);
        timeBar.setId(R.id.exo_progress);
        timeBar.setLayoutParams(params);
        timeBar.setAdGroupTimesMs(new long[0], new boolean[0], 0);
        timeBar.setPlayedColor(DefaultTimeBar.DEFAULT_PLAYED_COLOR);
        timeBar.setBufferedColor(DefaultTimeBar.DEFAULT_BUFFERED_COLOR);
        timeBar.setUnplayedColor(DefaultTimeBar.DEFAULT_UNPLAYED_COLOR);
        timeBar.setScrubberColor(DefaultTimeBar.DEFAULT_SCRUBBER_COLOR);
        timeBar.setAdMarkerColor(DefaultTimeBar.DEFAULT_AD_MARKER_COLOR);
        timeBar.setPlayedAdMarkerColor(DefaultTimeBar.DEFAULT_PLAYED_AD_MARKER_COLOR);
        timeBar.setDuration(C.TIME_UNSET);
        timeBar.setPosition(0);
        timeBar.setBufferedPosition(0);
        timeBar.addListener(scrubListener);
        addView(timeBar, params);
    }

    /** Binds a player to the bar; pass null to detach. Mirrors PlayerControlView. */
    public void setPlayer(@Nullable Player player) {
        detachListener();
        this.player = player;
        this.wasPlaying = false;
        attachListener();
        syncBar();
    }

    @Nullable
    public Player getPlayer() {
        return player;
    }

    /** The bar itself. The app adds its own OnScrubListener and sets the key increment. */
    @NonNull
    public TimeBar getTimeBar() {
        return timeBar;
    }

    /**
     * Pushes the whole player state into the bar. Single source of truth: both the event
     * listener and the periodic tick call this and nothing else.
     *
     * <p>It has to be one method, not two. A previous revision split this into an
     * "everything" path driven by events and a lighter "position only" path driven by the
     * tick, and the light path omitted setDuration(). A stream reports its length only
     * after the initial layout, so the TimeBar kept duration = TIME_UNSET forever;
     * DefaultTimeBar then computes a 0% progress fraction and draws a permanently empty
     * bar even though setPosition is handed the correct value every tick. Keeping the two
     * paths separate is what created the bug, so they are now merged.
     *
     * <p>All four setters are cheap: DefaultTimeBar early-returns when the value is
     * unchanged, so calling them at tick rate costs nothing when nothing moved.
     */
    private void syncBar() {
        if (player == null) {
            timeBar.setEnabled(false);
            timeBar.setDuration(C.TIME_UNSET);
            timeBar.setPosition(0);
            timeBar.setBufferedPosition(0);
            return;
        }
        timeBar.setDuration(player.getDuration());
        timeBar.setBufferedPosition(player.getBufferedPosition());
        timeBar.setPosition(player.getCurrentPosition());
        // Only interaction is gated, not the drawn position: while buffering,
        // getCurrentPosition() returns the last known position, which is what the user
        // expects to keep seeing, and following it through a seek is better than freezing.
        timeBar.setEnabled(player.getPlaybackState() == Player.STATE_READY
                && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM));
    }

    /** Declared before updateProgress so the ordering is obvious at a glance. */
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * Periodic position refresh.
     *
     * <p>This is not optional polish: Player.Listener.onEvents only fires when something
     * discrete happens (state change, track change, seek) and never continuously, so a bar
     * driven purely by events sits frozen for the whole duration of a stream. The official
     * PlayerControlView solves the same problem the same way -- a Runnable reposted on a
     * Handler (see its updateProgressAction and MAX_UPDATE_INTERVAL_MS), which is exactly
     * what this mirrors. 500 ms while playing, 1 s otherwise, and only while attached.
     */
    private final Runnable updateProgress = new Runnable() {
        @Override
        public void run() {
            syncBar();
            handler.postDelayed(this, player != null && player.isPlaying()
                    ? PROGRESS_INTERVAL_PLAYING_MS : PROGRESS_INTERVAL_IDLE_MS);
        }
    };

    /**
     * Whether playerListener is currently registered on {@link #player}.
     *
     * <p>This tracks the player, not the view. Guarding on view attachment instead is
     * subtly wrong: setPlayer() removes the old listener before adding the new one, and if
     * the view happens to already be attached the add then skips, leaving the bar with no
     * listener at all. That silently disables every event-driven refresh.
     */
    private boolean listening;

    private void startProgressUpdates() {
        handler.removeCallbacks(updateProgress);
        handler.post(updateProgress);
    }

    private void stopProgressUpdates() {
        handler.removeCallbacks(updateProgress);
    }

    /** Idempotent, and independent of attachment state. */
    private void attachListener() {
        if (listening || player == null) return;
        player.addListener(playerListener);
        listening = true;
    }

    private void detachListener() {
        if (!listening || player == null) return;
        player.removeListener(playerListener);
        listening = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // The player outlives this view (it belongs to PlaybackService), so the listener is
        // re-registered here after having been dropped in onDetachedFromWindow.
        attachListener();
        startProgressUpdates();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopProgressUpdates();
        detachListener();
        // The player reference is deliberately kept: dropping it would silently orphan the
        // bar if the view is ever re-attached (window recreation, PiP, a re-layout), since
        // onControllerConnected does not fire a second time.
        super.onDetachedFromWindow();
    }
}