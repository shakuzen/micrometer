import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * A registry decorator overriding timer(String, Iterable<Tag>), as a user might write
 * against 1.16.x. The override iterates the elements as Tag (a typical
 * filtering/logging decorator), which is the interesting case for heap pollution when
 * new code passes KeyValues through the widened signature.
 *
 * Compiles against 1.16.2 only; recompiling against the prototype fails with a name
 * clash (same erasure, neither overrides the other).
 */
public class MyRegistry extends SimpleMeterRegistry {

    public volatile boolean overrideCalled = false;

    public volatile int keyCharsSeen = 0;

    @Override
    public Timer timer(String name, Iterable<Tag> tags) {
        overrideCalled = true;
        for (Tag tag : tags) {
            keyCharsSeen += tag.getKey().length();
        }
        return super.timer(name, tags);
    }

}
