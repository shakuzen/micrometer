import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Binary/behavioral compatibility fixture. Source is compilable against BOTH the old
 * (1.16.2) and new (prototype) jars; the compiled classes are then run against both.
 *
 * Usage: java CompatMain <jars:old|new> <compiledAgainst:old|new>
 */
public class CompatMain {

    static int failures = 0;

    public static void main(String[] args) {
        String jars = args[0];
        String compiledAgainst = args[1];
        boolean newJars = jars.equals("new");
        boolean oldCompiled = compiledAgainst.equals("old");

        // --- 1. classes load and basic operations work
    Tag immutableTag = Tag.of("a", "b");
        KeyValue immutableKeyValue = KeyValue.of("a", "b");
        MyKeyValue myKeyValue = new MyKeyValue("a", "b");
        MyTag myA = new MyTag("a", "1");
        MyTag myB = new MyTag("b", "2");
        PlainTag plainA = new PlainTag("a", "1");
        PlainTag plainB = new PlainTag("b", "2");
        check("classes load and accessors work", immutableTag.getKey().equals("a") && myKeyValue.getValue().equals("b")
                && myA.getValue().equals("1") && plainA.getKey().equals("a"));

        // --- 2. direct tag.compareTo(otherTag) call: custom (descending) impl is always dispatched
        check("direct compareTo(Tag) dispatches to custom impl", myA.compareTo((Tag) myB) > 0);
        check("direct compareTo(Tag) on plain impl is by key", plainA.compareTo((Tag) plainB) < 0);
        check("direct compareTo(Tag) on built-in Tag is by key", immutableTag.compareTo(Tag.of("z", "b")) < 0);

        // --- 3. Arrays.sort(Tag[]) with a custom compareTo(Tag) implementor.
        // Dispatches through Comparable.compareTo(Object):
        // - classes compiled against 1.16.x have their own bridge -> custom order (descending)
        // - classes compiled against the prototype have no bridge; KeyValue's bridge routes to
        //   compareTo(KeyValue), whose default is key-ascending -> custom order is IGNORED
        Tag[] customArray = new Tag[] { myA, myB };
        Arrays.sort(customArray);
        boolean sortedDescending = customArray[0].getKey().equals("b");
        if (oldCompiled) {
            check("Arrays.sort honors old-compiled custom compareTo (descending)", sortedDescending);
        }
        else {
            check("Arrays.sort IGNORES newly-compiled custom compareTo (key-ascending) [documented wart]",
                    !sortedDescending);
        }

        // --- 4. Collections.sort(List<Tag>) behaves like Arrays.sort
        List<Tag> customList = new ArrayList<Tag>(Arrays.asList(myA, myB));
        Collections.sort(customList);
        check("Collections.sort matches Arrays.sort dispatch",
                customList.get(0).getKey().equals(customArray[0].getKey()));

        // --- 5. implementations without custom compareTo always sort by key
        Tag[] plainArray = new Tag[] { plainB, plainA };
        Arrays.sort(plainArray);
        check("Arrays.sort of plain implementor is by key", plainArray[0].getKey().equals("a"));

        Tag[] builtInArray = new Tag[] { Tag.of("z", "1"), Tag.of("a", "2") };
        Arrays.sort(builtInArray);
        check("Arrays.sort of built-in tags is by key", builtInArray[0].getKey().equals("a"));

        // --- 6. TreeSet natural ordering
        TreeSet<Tag> treeSet = new TreeSet<Tag>(Arrays.asList(plainB, plainA));
        check("TreeSet natural ordering is by key", treeSet.first().getKey().equals("a"));

        // --- 7. Tags.of with custom compareTo implementors
        // (old-compiled: isSortedSet and Arrays.sort both honor the custom order;
        // new-compiled: isSortedSet honors it but Arrays.sort does not)
        Tags customTags = Tags.of(new Tag[] { new MyTag("a", "1"), new MyTag("b", "2") });
        String firstKey = customTags.iterator().next().getKey();
        if (oldCompiled) {
            check("Tags.of honors old-compiled custom compareTo (descending)", firstKey.equals("b"));
        }
        else {
            check("Tags.of with newly-compiled custom compareTo sorts by key [documented wart]", firstKey.equals("a"));
        }

        // --- 8. Tags and Meter.Id as HashMap keys
        Map<Tags, String> tagsMap = new HashMap<Tags, String>();
        tagsMap.put(Tags.of("a", "b", "c", "d"), "x");
        check("Tags works as HashMap key", "x".equals(tagsMap.get(Tags.of("a", "b", "c", "d"))));

        Map<Meter.Id, String> idMap = new HashMap<Meter.Id, String>();
        idMap.put(new Meter.Id("m", Tags.of("a", "b"), null, null, Meter.Type.COUNTER), "y");
        check("Meter.Id works as HashMap key",
                "y".equals(idMap.get(new Meter.Id("m", Tags.of("a", "b"), null, null, Meter.Type.COUNTER))));

        // --- 9. cross-type equality between ImmutableTag and ImmutableKeyValue (intended change)
        boolean tagEqualsKeyValue = immutableTag.equals(immutableKeyValue);
        boolean keyValueEqualsTag = immutableKeyValue.equals(immutableTag);
        if (newJars) {
            check("ImmutableTag.equals(ImmutableKeyValue) is true on new jars", tagEqualsKeyValue);
            check("ImmutableKeyValue.equals(ImmutableTag) is true on new jars", keyValueEqualsTag);
        }
        else {
            check("ImmutableTag.equals(ImmutableKeyValue) is false on old jars", !tagEqualsKeyValue);
            check("ImmutableKeyValue.equals(ImmutableTag) is false on old jars", !keyValueEqualsTag);
        }
        check("hash codes of equal-pair Tag and KeyValue match (same formula, both versions)",
                immutableTag.hashCode() == immutableKeyValue.hashCode());
        System.out.println("INFO: custom KeyValue impl without equals override: immutableTag.equals(myKeyValue)="
                + immutableTag.equals(myKeyValue) + ", myKeyValue.equals(immutableTag)="
                + myKeyValue.equals(immutableTag));

        // --- 10. MeterRegistry round trip with Iterable<Tag> (same instance on re-registration)
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Timer timer1 = registry.timer("t", Arrays.asList(Tag.of("k", "v")));
        Timer timer2 = registry.timer("t", Arrays.asList(Tag.of("k", "v")));
        check("timer(String, Iterable) deduplicates across calls", timer1 == timer2);
        check("registry has exactly one meter", registry.getMeters().size() == 1);
        check("meter id tag accessible", "v".equals(timer1.getId().getTag("k")));

        System.out.println(failures == 0 ? "ALL CHECKS PASSED (jars=" + jars + ", compiled=" + compiledAgainst + ")"
                : failures + " CHECKS FAILED (jars=" + jars + ", compiled=" + compiledAgainst + ")");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(String label, boolean condition) {
        System.out.println((condition ? "PASS: " : "FAIL: ") + label);
        if (!condition) {
            failures++;
        }
    }

}
