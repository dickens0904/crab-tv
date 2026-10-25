package com.fongmi.android.tv.player.engine;

import java.util.Arrays;

import androidx.media3.common.Player;

/**
 * The command set advertised by the non-ExoPlayer engines (MPV and IJK).
 *
 * <p><b>Why this is shared rather than written per engine:</b> media3's UI decides what
 * to hand a player by asking {@link Player#isCommandAvailable(int)}. {@code PlayerView}
 * gates the whole surface hand-off on {@code COMMAND_SET_VIDEO_SURFACE}, so an engine
 * that forgets it silently never receives a {@code Surface} -- and mpv, whose VO can
 * only be pointed at a surface before {@code mpv_initialize}, then plays audio with a
 * black picture. One list, used by {@link #isAvailable(int)} and {@link #all()}, keeps
 * those two from drifting apart.
 *
 * <p><b>Why the ids are sorted before building {@code Commands}:</b> the {@code Commands}
 * constructor rejects a non-ascending array using {@code Util.checkArgument(boolean)},
 * which throws a bare {@link IllegalArgumentException} with no message. Hand-writing the
 * list in "logical" order throws there, inside {@code PlaybackService.onCreate}.
 */
public final class EngineCommands {

    /** Sorted at class-init so {@link #all()} can hand {@code Commands} a valid array. */
    private static final int[] SORTED = sortedCopy();

    private static int[] sortedCopy() {
        int[] source = {
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME,
                Player.COMMAND_SET_VIDEO_SURFACE,
                Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                Player.COMMAND_GET_TRACKS,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_RELEASE,
        };
        Arrays.sort(source);
        return source;
    }

    private static volatile Player.Commands commands;

    private EngineCommands() {
    }

    /** Commands are deliberately NOT advertised when they would be a lie: device volume
     *  adjustment and audio attributes are stored-only no-ops on these engines, so the
     *  UI keeps showing them as unavailable rather than silently ignoring a tap. */
    public static boolean isAvailable(int command) {
        return Arrays.binarySearch(SORTED, command) >= 0;
    }

    public static Player.Commands all() {
        Player.Commands local = commands;
        if (local == null) {
            synchronized (EngineCommands.class) {
                local = commands;
                if (local == null) {
                    local = new Player.Commands.Builder().addAll(SORTED).build();
                    commands = local;
                }
            }
        }
        return local;
    }
}