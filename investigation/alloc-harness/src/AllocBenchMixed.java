import com.sun.management.ThreadMXBean;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.lang.management.ManagementFactory;

/**
 * Probes call-site polymorphism: whether mixing the Tags-typed meter path and the
 * KeyValues-typed observation path in ONE JVM slows either down compared to exercising
 * each alone. On the new jars, Meter.Id instances hold ImmutableTag elements when built
 * from Tags and ImmutableKeyValue elements when built from KeyValues, so shared library
 * call sites (Meter.Id.hashCode/equals element accesses, etc.) see two receiver types.
 * On the old jars everything is converted to ImmutableTag up front, so the same
 * workload keeps those sites monomorphic - run old jars as the control.
 *
 * Both ops share one MeterRegistry, like a typical application would.
 * Source-compatible with both old (1.16.x) and new (prototype) jars.
 *
 * Usage: java AllocBenchMixed <timer_solo|lifecycle_solo|mixed> [warmupOps] [ops] [batches]
 */
public class AllocBenchMixed {

    static final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    static ObservationRegistry observationRegistry;

    static final Tags TAGS = Tags.of("exception", "none", "method", "GET", "outcome", "SUCCESS", "status", "200",
            "uri", "/api/users/{id}");

    static Object timerOp() {
        // Tags-typed path: Meter.Id built from Tags (ImmutableTag elements)
        return meterRegistry.timer("mixed.timer", TAGS);
    }

    static Object lifecycleOp() {
        // Observation path: on new jars the Meter.Id is built from KeyValues
        // (ImmutableKeyValue elements); on old jars everything becomes ImmutableTag
        Observation observation = Observation
            .createNotStarted(AllocBench.CONVENTION, Observation.Context::new, observationRegistry)
            .start();
        Observation.Scope scope = observation.openScope();
        scope.close();
        observation.stop();
        return observation;
    }

    public static void main(String[] args) {
        String mode = args[0];
        int warmupOps = args.length > 1 ? Integer.parseInt(args[1]) : 300_000;
        int ops = args.length > 2 ? Integer.parseInt(args[2]) : 300_000;
        int batches = args.length > 3 ? Integer.parseInt(args[3]) : 7;

        observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig()
            .observationHandler(new DefaultMeterObservationHandler(meterRegistry,
                    DefaultMeterObservationHandler.IgnoredMeters.LONG_TASK_TIMER));

        boolean doTimer = !mode.equals("lifecycle_solo");
        boolean doLifecycle = !mode.equals("timer_solo");

        ThreadMXBean threadMXBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        Object sink = null;

        // interleaved warmup so mixed mode pollutes the shared profiles before compilation
        for (int i = 0; i < warmupOps; i++) {
            if (doTimer) {
                sink = timerOp();
            }
            if (doLifecycle) {
                sink = lifecycleOp();
            }
        }

        double[] timerNs = new double[batches];
        double[] timerBytes = new double[batches];
        double[] lifecycleNs = new double[batches];
        double[] lifecycleBytes = new double[batches];
        for (int b = 0; b < batches; b++) {
            if (doTimer) {
                long a0 = threadMXBean.getThreadAllocatedBytes(tid);
                long t0 = System.nanoTime();
                for (int i = 0; i < ops; i++) {
                    sink = timerOp();
                }
                long t1 = System.nanoTime();
                long a1 = threadMXBean.getThreadAllocatedBytes(tid);
                timerNs[b] = (t1 - t0) / (double) ops;
                timerBytes[b] = (a1 - a0) / (double) ops;
            }
            if (doLifecycle) {
                long a0 = threadMXBean.getThreadAllocatedBytes(tid);
                long t0 = System.nanoTime();
                for (int i = 0; i < ops; i++) {
                    sink = lifecycleOp();
                }
                long t1 = System.nanoTime();
                long a1 = threadMXBean.getThreadAllocatedBytes(tid);
                lifecycleNs[b] = (t1 - t0) / (double) ops;
                lifecycleBytes[b] = (a1 - a0) / (double) ops;
            }
        }

        if (doTimer) {
            report(mode, "timer_tags_path", timerNs, timerBytes, batches);
        }
        if (doLifecycle) {
            report(mode, "observation_path", lifecycleNs, lifecycleBytes, batches);
        }
        if (sink == null) {
            System.out.println("sink was null");
        }
    }

    static void report(String mode, String op, double[] ns, double[] bytes, int batches) {
        java.util.Arrays.sort(ns);
        java.util.Arrays.sort(bytes);
        System.out.printf("RESULT mode=%s op=%s ns/op(median)=%.1f ns/op(min)=%.1f bytes/op(median)=%.1f%n", mode, op,
                ns[batches / 2], ns[0], bytes[batches / 2]);
    }

}
