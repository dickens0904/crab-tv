package androidx.media3.common;

/**
 * [LOCAL COMPILE STUB] -- part of upstream FongMi's Media3 fork.
 *
 * <p>A chapter marker inside a media item: a human readable label plus the offset it
 * jumps to. Absent from every published Media3 release, so it has to be declared here
 * for {@code PlayerManager} / {@code ChapterAdapter} / {@code ChapterDialog} to compile.
 *
 * <p>Field names are fixed by the call sites, which read them directly:
 * <pre>
 *   ChapterAdapter.getText:  item.label, item.timeUs, item.selected
 *   ChapterAdapter.getSelected: item.selected
 * </pre>
 * They are public fields rather than getters because that is how the fork declares
 * them -- note {@code timeUs} carries <em>micro</em> values, while {@code C.TIME_UNSET}
 * is compared against it directly. Keep the names and units exactly as they are.
 *
 * <p>Semantic note: nothing ever populates this list, so {@code haveChapter()} stays
 * false and the chapter row stays hidden. That is a graceful degradation, not a break.
 *
 * <p>ASCII-only comments on purpose (javac defaults to GBK on Chinese Windows and
 * mangles UTF-8 CJK bytes into illegal-character errors).
 */
public class MediaChapter {

    /** Offset the chapter jumps to, in microseconds; {@link C#TIME_UNSET} if unknown. */
    public long timeUs = C.TIME_UNSET;

    /** Display text shown in the chapter list. */
    public String label = "";

    /** Whether this is the chapter matching the current playback position. */
    public boolean selected;

    public MediaChapter() {
    }

    public MediaChapter(String label, long timeUs) {
        this.label = label;
        this.timeUs = timeUs;
    }

    public long getTimeUs() {
        return timeUs;
    }

    public String getLabel() {
        return label;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    @Override
    public String toString() {
        return label + "@" + timeUs;
    }
}