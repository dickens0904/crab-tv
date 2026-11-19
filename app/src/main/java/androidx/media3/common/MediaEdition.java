package androidx.media3.common;

/**
 * [LOCAL COMPILE STUB] -- part of upstream FongMi's Media3 fork.
 *
 * <p>An alternate version of the same movie (theatrical cut, director's cut, extended
 * edition) attached to a media item. Absent from every published Media3 release.
 *
 * <p>Mirrors {@link MediaChapter} exactly; the only difference is the timing field,
 * which the call sites use as a <em>duration</em>:
 * <pre>
 *   EditionAdapter.getText:  item.label, item.durationUs, item.selected
 * </pre>
 * so {@code durationUs} must stay in microseconds and default to {@link C#TIME_UNSET}.
 *
 * <p>Semantic note: never populated, so the edition row stays hidden.
 *
 * <p>ASCII-only comments on purpose (javac defaults to GBK on Chinese Windows).
 */
public class MediaEdition {

    /** Length of this edition, in microseconds; {@link C#TIME_UNSET} if unknown. */
    public long durationUs = C.TIME_UNSET;

    /** Display text shown in the edition list. */
    public String label = "";

    /** Whether this is the edition currently being played. */
    public boolean selected;

    public MediaEdition() {
    }

    public MediaEdition(String label, long durationUs) {
        this.label = label;
        this.durationUs = durationUs;
    }

    public long getDurationUs() {
        return durationUs;
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
        return label + "#" + durationUs;
    }
}