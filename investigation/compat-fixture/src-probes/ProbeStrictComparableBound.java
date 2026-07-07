import io.micrometer.core.instrument.Tag;

import java.util.List;

/**
 * PROBE: generic method with the strict bound T extends Comparable<T> applied to Tag.
 * Compiles against 1.16.2.
 * EXPECTED against prototype: FAILS (Tag extends Comparable<KeyValue>, not
 * Comparable<Tag>). Note that the RELAXED idiomatic bound
 * T extends Comparable<? super T> (used by Collections.sort etc.) still works.
 */
public class ProbeStrictComparableBound {

    static <T extends Comparable<T>> T max(List<T> values) {
        T max = values.get(0);
        for (T value : values) {
            if (value.compareTo(max) > 0) {
                max = value;
            }
        }
        return max;
    }

    static Tag maxTag(List<Tag> tags) {
        return max(tags);
    }

}
