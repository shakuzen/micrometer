import com.sun.management.ThreadMXBean;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.ObservationRegistry;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-threaded allocation/time harness using ThreadMXBean#getThreadAllocatedBytes.
 * Source-compatible with both old (1.16.x) and new (prototype) micrometer jars.
 *
 * Usage: java AllocBench <scenario> [warmupOps] [ops] [batches]
 * Scenarios: lifecycle_ltt | lifecycle_noltt | cached_sample | tags_convert
 */
public class AllocBench {

    static final ObservationConvention<Observation.Context> CONVENTION = new ObservationConvention<Observation.Context>() {
        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public String getName() {
            return "http.server.requests";
        }

        @Override
        public KeyValues getLowCardinalityKeyValues(Observation.Context context) {
            // 5 low-cardinality key-values, keys pre-sorted (as a well-behaved convention would)
            return KeyValues.of("exception", "none", "method", "GET", "outcome", "SUCCESS", "status", "200", "uri",
                    "/api/users/{id}");
        }
    };

    interface Op {
        Object run();
    }

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        int warmupOps = args.length > 1 ? Integer.parseInt(args[1]) : 300_000;
        int ops = args.length > 2 ? Integer.parseInt(args[2]) : 300_000;
        int batches = args.length > 3 ? Integer.parseInt(args[3]) : 7;

        Op op = createOp(scenario);
        runAndReport(scenario, op, warmupOps, ops, batches);
    }

    static Op createOp(String scenario) {
        final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        switch (scenario) {
            case "lifecycle_ltt": {
                final ObservationRegistry observationRegistry = ObservationRegistry.create();
                observationRegistry.observationConfig()
                    .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
                return new Op() {
                    @Override
                    public Object run() {
                        Observation observation = Observation
                            .createNotStarted(CONVENTION, Observation.Context::new, observationRegistry)
                            .start();
                        Observation.Scope scope = observation.openScope();
                        scope.close();
                        observation.stop();
                        return observation;
                    }
                };
            }
            case "lifecycle_noltt": {
                final ObservationRegistry observationRegistry = ObservationRegistry.create();
                observationRegistry.observationConfig()
                    .observationHandler(new DefaultMeterObservationHandler(meterRegistry,
                            DefaultMeterObservationHandler.IgnoredMeters.LONG_TASK_TIMER));
                return new Op() {
                    @Override
                    public Object run() {
                        Observation observation = Observation
                            .createNotStarted(CONVENTION, Observation.Context::new, observationRegistry)
                            .start();
                        Observation.Scope scope = observation.openScope();
                        scope.close();
                        observation.stop();
                        return observation;
                    }
                };
            }
            case "cached_sample": {
                final Timer timer = Timer.builder("cached.timer").tag("abc", "123").register(meterRegistry);
                return new Op() {
                    @Override
                    public Object run() {
                        Timer.Sample sample = Timer.start(meterRegistry);
                        return sample.stop(timer);
                    }
                };
            }
            case "tags_convert": {
                // The old DefaultMeterObservationHandler#createTags path: per-element KeyValue -> Tag
                // conversion into a List, plus the error tag, then Tags.of(list).
                final KeyValues keyValues = CONVENTION.getLowCardinalityKeyValues(null);
                return new Op() {
                    @Override
                    public Object run() {
                        List<Tag> tags = new ArrayList<>();
                        for (io.micrometer.common.KeyValue keyValue : keyValues) {
                            tags.add(Tag.of(keyValue.getKey(), keyValue.getValue()));
                        }
                        tags.add(Tag.of("error", "none"));
                        return Tags.of(tags);
                    }
                };
            }
            default:
                throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
    }

    static void runAndReport(String scenario, Op op, int warmupOps, int ops, int batches) {
        ThreadMXBean threadMXBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        Object sink = null;

        for (int i = 0; i < warmupOps; i++) {
            sink = op.run();
        }

        double[] bytesPerOp = new double[batches];
        double[] nanosPerOp = new double[batches];
        for (int b = 0; b < batches; b++) {
            long a0 = threadMXBean.getThreadAllocatedBytes(tid);
            long t0 = System.nanoTime();
            for (int i = 0; i < ops; i++) {
                sink = op.run();
            }
            long t1 = System.nanoTime();
            long a1 = threadMXBean.getThreadAllocatedBytes(tid);
            bytesPerOp[b] = (a1 - a0) / (double) ops;
            nanosPerOp[b] = (t1 - t0) / (double) ops;
        }

        java.util.Arrays.sort(bytesPerOp);
        java.util.Arrays.sort(nanosPerOp);
        System.out.printf("RESULT scenario=%s bytes/op(median)=%.1f bytes/op(min)=%.1f ns/op(median)=%.1f ns/op(min)=%.1f%n",
                scenario, bytesPerOp[batches / 2], bytesPerOp[0], nanosPerOp[batches / 2], nanosPerOp[0]);
        if (sink == null) {
            System.out.println("sink was null");
        }
    }

}
