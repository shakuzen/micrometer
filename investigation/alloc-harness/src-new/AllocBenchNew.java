import com.sun.management.ThreadMXBean;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Scenarios that only compile against the NEW (prototype) jars.
 *
 * Usage: java AllocBenchNew <scenario> [warmupOps] [ops] [batches]
 * Scenarios: tags_of_kv | tags_convert5 | id_gettags
 */
public class AllocBenchNew {

    static final KeyValues KEY_VALUES = KeyValues.of("exception", "none", "method", "GET", "outcome", "SUCCESS",
            "status", "200", "uri", "/api/users/{id}");

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        int warmupOps = args.length > 1 ? Integer.parseInt(args[1]) : 300_000;
        int ops = args.length > 2 ? Integer.parseInt(args[2]) : 300_000;
        int batches = args.length > 3 ? Integer.parseInt(args[3]) : 7;

        if (scenario.equals("id_gettags")) {
            measureGetTagsFirstVsHot();
            return;
        }

        AllocBench.Op op = createOp(scenario);
        AllocBench.runAndReport(scenario, op, warmupOps, ops, batches);
    }

    static AllocBench.Op createOp(String scenario) {
        switch (scenario) {
            case "tags_of_kv":
                // NEW: direct pass-through of KeyValues into Tags.of (widened signature)
                return new AllocBench.Op() {
                    @Override
                    public Object run() {
                        return Tags.of(KEY_VALUES);
                    }
                };
            case "tags_convert5":
                // OLD equivalent of tags_of_kv: manual per-element conversion of the same 5 key-values
                return new AllocBench.Op() {
                    @Override
                    public Object run() {
                        List<Tag> tags = new ArrayList<>();
                        for (io.micrometer.common.KeyValue keyValue : KEY_VALUES) {
                            tags.add(Tag.of(keyValue.getKey(), keyValue.getValue()));
                        }
                        return Tags.of(tags);
                    }
                };
            default:
                throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
    }

    /**
     * Measures cost of the first (lazy conversion) vs subsequent (cached)
     * Meter.Id#getTagsAsIterable() call on an Id built from KeyValues without going
     * through Tags. Timed in batches because a single call is below the timer
     * granularity on some platforms.
     */
    static void measureGetTagsFirstVsHot() {
        ThreadMXBean threadMXBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        int trials = 50_000;

        // warmup
        Object sink = null;
        for (int i = 0; i < 50_000; i++) {
            SimpleMeterRegistry r = new SimpleMeterRegistry();
            Timer t = r.timer("http.server.requests", KEY_VALUES);
            sink = t.getId().getTagsAsIterable();
            sink = t.getId().getTagsAsIterable();
        }

        // first-call cost: fresh id per trial; aggregate allocation over all trials
        Meter.Id[] ids = new Meter.Id[trials];
        for (int i = 0; i < trials; i++) {
            SimpleMeterRegistry r = new SimpleMeterRegistry();
            ids[i] = r.timer("http.server.requests", KEY_VALUES).getId();
        }
        long a0 = threadMXBean.getThreadAllocatedBytes(tid);
        long t0 = System.nanoTime();
        for (int i = 0; i < trials; i++) {
            sink = ids[i].getTagsAsIterable();
        }
        long t1 = System.nanoTime();
        long a1 = threadMXBean.getThreadAllocatedBytes(tid);
        double firstNs = (t1 - t0) / (double) trials;
        double firstBytes = (a1 - a0) / (double) trials;

        // subsequent-call cost on the same (now cached) ids
        long a2 = threadMXBean.getThreadAllocatedBytes(tid);
        long t2 = System.nanoTime();
        for (int i = 0; i < trials; i++) {
            sink = ids[i].getTagsAsIterable();
        }
        long t3 = System.nanoTime();
        long a3 = threadMXBean.getThreadAllocatedBytes(tid);
        double hotNs = (t3 - t2) / (double) trials;
        double hotBytes = (a3 - a2) / (double) trials;

        System.out.printf(
                "RESULT scenario=id_gettags first_ns=%.1f first_bytes=%.1f hot_ns=%.1f hot_bytes=%.1f%n", firstNs,
                firstBytes, hotNs, hotBytes);
        if (sink == null) {
            System.out.println("sink was null");
        }
    }

}
