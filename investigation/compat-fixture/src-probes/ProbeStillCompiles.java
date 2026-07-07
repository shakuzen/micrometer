import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BiFunction;

/**
 * PROBE: common Tag usage patterns. Compiles against 1.16.2.
 * EXPECTED against prototype: STILL COMPILES (possibly with deprecation warnings).
 */
public class ProbeStillCompiles {

    // a plain implementor
    static class PlainImpl implements Tag {

        @Override
        public String getKey() {
            return "k";
        }

        @Override
        public String getValue() {
            return "v";
        }

    }

    // an implementor overriding compareTo(Tag) - kept compilable by the retained
    // deprecated overload (with a deprecation warning)
    static class ComparingImpl implements Tag {

        @Override
        public String getKey() {
            return "k";
        }

        @Override
        public String getValue() {
            return "v";
        }

        @Override
        public int compareTo(Tag o) {
            return o.getKey().compareTo(getKey());
        }

    }

    void commonPatterns(MeterRegistry registry, List<Tag> tags) {
        // sorting through the relaxed Comparable bound
        Collections.sort(tags);
        tags.sort(null);
        Tag[] array = tags.toArray(new Tag[0]);
        Arrays.sort(array);
        new TreeSet<Tag>(tags);
        new ArrayList<Tag>(tags).stream().sorted().count();

        // natural order comparator inference
        Comparator<Tag> comparator = Comparator.naturalOrder();
        comparator.compare(Tag.of("a", "b"), Tag.of("c", "d"));

        // direct compareTo between tags (binds to the deprecated overload)
        Tag.of("a", "b").compareTo(Tag.of("c", "d"));

        // registry entry points with Iterable<Tag>-typed arguments
        registry.timer("t", tags);
        registry.counter("c", tags);
        registry.summary("s", tags);
        registry.more().longTaskTimer("l", tags);
        Timer.builder("t2").tags(tags).register(registry);

        // method reference against the widened signature
        BiFunction<String, Iterable<Tag>, Timer> timerFactory = registry::timer;
        timerFactory.apply("t3", tags);

        // Tags API
        Tags.of(tags).and(tags);
        Tags.concat(tags, tags);
    }

    public static void main(String[] args) {
        new ProbeStillCompiles().commonPatterns(new SimpleMeterRegistry(),
                new ArrayList<>(Arrays.asList(Tag.of("a", "b"))));
        System.out.println("ProbeStillCompiles ran successfully");
    }

}
