import io.micrometer.core.instrument.Tag;

/**
 * PROBE: code that uses Comparable<Tag> as a type. Compiles against 1.16.2.
 * EXPECTED against prototype: FAILS (Tag no longer implements Comparable<Tag>).
 */
public class ProbeComparableTagTyped {

    Comparable<Tag> asComparable(Tag tag) {
        return tag;
    }

}
