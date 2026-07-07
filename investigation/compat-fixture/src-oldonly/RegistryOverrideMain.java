import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;

import java.util.Arrays;

/**
 * Runs the old-compiled MyRegistry override against old or new jars.
 *
 * Usage: java RegistryOverrideMain <jars:old|new>
 */
public class RegistryOverrideMain {

    static int failures = 0;

    public static void main(String[] args) {
        String jars = args[0];
        boolean newJars = jars.equals("new");

        // --- 1. the old-compiled override is still dispatched through the widened method
        MyRegistry myRegistry = new MyRegistry();
        MeterRegistry asBase = myRegistry;
        Timer timer1 = asBase.timer("t", Arrays.asList(Tag.of("key", "v")));
        check("old-compiled override is dispatched for timer(String, Iterable)", myRegistry.overrideCalled);
        check("override saw the Tag elements", myRegistry.keyCharsSeen == 3);
        Timer timer2 = asBase.timer("t", Arrays.asList(Tag.of("key", "v")));
        check("meter deduplicated through override", timer1 == timer2);

        // --- 2. observation-driven registration through the old-compiled iterating override.
        // Old jars: the handler converts KeyValues to List<Tag> first -> no problem.
        // New jars: the handler passes KeyValues through timer(String, Iterable); the
        // old-compiled override's loop checkcasts each element to Tag -> expect
        // ClassCastException (heap pollution through the widened virtual call).
        MyRegistry observedRegistry = new MyRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig()
            .observationHandler(new DefaultMeterObservationHandler(observedRegistry));
        boolean cce = false;
        try {
            Observation observation = Observation.createNotStarted("test.obs", observationRegistry)
                .lowCardinalityKeyValue("abc", "123")
                .start();
            observation.stop();
        }
        catch (ClassCastException e) {
            cce = true;
            System.out.println("INFO: ClassCastException: " + e.getMessage());
        }
        if (newJars) {
            check("DISPATCH HAZARD: observation through old-compiled iterating override throws CCE on new jars", cce);
        }
        else {
            check("observation through old-compiled iterating override works on old jars",
                    !cce && observedRegistry.overrideCalled);
        }

        System.out.println(failures == 0 ? "ALL CHECKS PASSED (jars=" + jars + ")"
                : failures + " CHECKS FAILED (jars=" + jars + ")");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(String label, boolean condition) {
        System.out.println((condition ? "PASS: " : "FAIL: ") + label);
        if (!condition) {
            failures++;
        }
    }

}
