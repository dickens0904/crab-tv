package com.fongmi.android.tv.playback;

import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.PlayerManager;

public final class PlaybackReset {

    public static void afterError(PlayerManager player) {
        afterError(player, null);
    }

    public static void afterError(PlayerManager player, Runnable beforeReset) {
        if (beforeReset != null) beforeReset.run();
        Track.delete(player.getKey());
        player.resetTrack();
        player.reset();
        player.stop();
        // Clear both the media items and spec so isEmpty() becomes true.
        // Otherwise the play button calls play() on a stale spec and never re-parses.
        player.clearMediaItems();
        player.clear();
    }
}
