/*
 * Copyright 2026 VMware, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.prometheusmetrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.distribution.*;
import io.micrometer.core.instrument.distribution.pause.NoPauseDetector;
import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusMemoryFootprintTest {

    @Test
    void verifyTimerMemoryOptimization() throws Exception {
        Meter.Id id = new Meter.Id("benchmark_timer", Tags.empty(), null, null, Meter.Type.TIMER);
        Clock clock = Clock.SYSTEM;
        DistributionStatisticConfig config = DistributionStatisticConfig.builder()
                .percentilesHistogram(true)
                .minimumExpectedValue((double) TimeUnit.MILLISECONDS.toNanos(1))
                .maximumExpectedValue((double) TimeUnit.SECONDS.toNanos(30))
                .build()
                .merge(DistributionStatisticConfig.DEFAULT);

        // Instantiate PrometheusTimer
        PrometheusTimer timer = new PrometheusTimer(id, clock, config, new NoPauseDetector(), null);

        // Programmatically verify that super.histogram is NoopHistogram.INSTANCE,
        // proving that NO duplicate TimeWindowFixedBoundaryHistogram was created!
        Field superHistogramField = AbstractTimer.class.getDeclaredField("histogram");
        superHistogramField.setAccessible(true);
        Histogram superHistogram = (Histogram) superHistogramField.get(timer);

        System.out.println("=== Timer Memory Optimization Verification ===");
        System.out.println("AbstractTimer.histogram (super): " + superHistogram.getClass().getName());
        System.out.println("PrometheusTimer.histogram (this): " + timer.takeSnapshot().getClass().getName());

        assertThat(superHistogram).isSameAs(NoopHistogram.INSTANCE);

        // Print JOL footprint
        System.out.println("\nPrometheusTimer JOL Footprint:\n" + GraphLayout.parseInstance(timer).toFootprint());
    }

    @Test
    void verifyDistributionSummaryMemoryOptimization() throws Exception {
        Meter.Id id = new Meter.Id("benchmark_summary", Tags.empty(), null, null, Meter.Type.DISTRIBUTION_SUMMARY);
        Clock clock = Clock.SYSTEM;
        DistributionStatisticConfig config = DistributionStatisticConfig.builder()
                .percentilesHistogram(true)
                .minimumExpectedValue(1.0)
                .maximumExpectedValue(100.0)
                .build()
                .merge(DistributionStatisticConfig.DEFAULT);

        // Instantiate PrometheusDistributionSummary
        PrometheusDistributionSummary summary = new PrometheusDistributionSummary(id, clock, config, 1.0, null);

        // Programmatically verify that super.histogram is NoopHistogram.INSTANCE,
        // proving that NO duplicate TimeWindowFixedBoundaryHistogram was created!
        Field superHistogramField = AbstractDistributionSummary.class.getDeclaredField("histogram");
        superHistogramField.setAccessible(true);
        Histogram superHistogram = (Histogram) superHistogramField.get(summary);

        System.out.println("=== DistributionSummary Memory Optimization Verification ===");
        System.out.println("AbstractDistributionSummary.histogram (super): " + superHistogram.getClass().getName());
        System.out.println("PrometheusDistributionSummary.histogram (this): " + summary.takeSnapshot().getClass().getName());

        assertThat(superHistogram).isSameAs(NoopHistogram.INSTANCE);

        // Print JOL footprint
        System.out.println("\nPrometheusDistributionSummary JOL Footprint:\n" + GraphLayout.parseInstance(summary).toFootprint());
    }
}
