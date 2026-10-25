package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

/**
 * Playback cache sizes, one independent gear per engine.
 *
 * <p>Gears are 0/64/128/256/512 MB and 0 means "this engine does not write a cache".
 * EXO and IJK resolve independently so a box that only ever plays Exo content is not
 * affected by the IJK gear, and vice versa.
 *
 * <p>Both default to 128 MB. The value is stored as the MB number itself rather than as
 * an index into the gear array, so a gear added later cannot silently repoint an
 * existing install's setting at a different size.
 */
public class CacheSetting {

    public static final int[] LEVELS = {0, 64, 128, 256, 512};
    public static final int DEFAULT_MB = 128;

    private static final String KEY_EXO = "cache_exo_mb";
    private static final String KEY_IJK = "cache_ijk_mb";

    private static boolean enabled() {
        return LEVELS.length > 1;
    }

    /**
     * Snaps a stored value onto the nearest declared gear, so a hand-edited or
     * gear-from-a-future-build value can never reach the cache layer off-gear.
     */
    private static int snap(int mb) {
        if (!enabled()) return 0;
        int best = LEVELS[0];
        int bestDelta = Integer.MAX_VALUE;
        for (int level : LEVELS) {
            int delta = Math.abs(level - mb);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = level;
            }
        }
        return best;
    }

    private static int indexOf(int mb) {
        int index = 0;
        for (int i = 0; i < LEVELS.length; i++) {
            if (LEVELS[i] == mb) return i;
            if (LEVELS[i] < mb) index = i;
        }
        return index;
    }

    public static int getExoSizeMb() {
        return snap(Prefers.getInt(KEY_EXO, DEFAULT_MB));
    }

    public static void putExoSizeMb(int mb) {
        Prefers.put(KEY_EXO, snap(mb));
    }

    public static int getExoIndex() {
        return indexOf(getExoSizeMb());
    }

    public static void putExoIndex(int index) {
        int clamped = Math.clamp(index, 0, LEVELS.length - 1);
        putExoSizeMb(LEVELS[clamped]);
    }

    /** Next EXO gear, wrapping at the end so repeated taps cycle. */
    public static int nextExoSizeMb() {
        return LEVELS[(getExoIndex() + 1) % LEVELS.length];
    }

    public static int getIjkSizeMb() {
        return snap(Prefers.getInt(KEY_IJK, DEFAULT_MB));
    }

    public static void putIjkSizeMb(int mb) {
        Prefers.put(KEY_IJK, snap(mb));
    }

    public static int getIjkIndex() {
        return indexOf(getIjkSizeMb());
    }

    public static void putIjkIndex(int index) {
        int clamped = Math.clamp(index, 0, LEVELS.length - 1);
        putIjkSizeMb(LEVELS[clamped]);
    }

    /** Next IJK gear, wrapping at the end so repeated taps cycle. */
    public static int nextIjkSizeMb() {
        return LEVELS[(getIjkIndex() + 1) % LEVELS.length];
    }

    public static long getExoSizeBytes() {
        return getExoSizeMb() * 1024L * 1024L;
    }
}