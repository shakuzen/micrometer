/*
 * Copyright 2024 VMware, Inc.
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
package io.micrometer.concurrencytests;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.LongTaskTimer.Sample;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.Z_Result;

import java.time.Duration;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

/**
 * Concurrency tests for histogram using {@link PrometheusMeterRegistry}.
 */
public class PrometheusMeterRegistryConcurrencyTest {

    // @Issue("#5193")
    @JCStressTest
    @State
    @Outcome(id = "true", expect = ACCEPTABLE, desc = "Successful scrape")
    @Outcome(expect = FORBIDDEN, desc = "Failed scrape")
    public static class ConsistentTimerHistogram {

        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        Timer timer = Timer.builder("test").publishPercentileHistogram().register(registry);

        @Actor
        public void record() {
            timer.record(Duration.ofMillis(100));
        }

        @Actor
        public void scrape(Z_Result r) {
            try {
                registry.scrape();
                r.r1 = true;
            }
            catch (Exception e) {
                r.r1 = false;
            }
        }

    }

    // @Issue("#5193")
    @JCStressTest
    @State
    @Outcome(id = "true", expect = ACCEPTABLE, desc = "Successful scrape")
    @Outcome(expect = FORBIDDEN, desc = "Failed scrape")
    public static class ConsistentDistributionSummaryHistogram {

        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        DistributionSummary ds = DistributionSummary.builder("test").publishPercentileHistogram().register(registry);

        @Actor
        public void record() {
            ds.record(100);
        }

        @Actor
        public void scrape(Z_Result r) {
            try {
                registry.scrape();
                r.r1 = true;
            }
            catch (Exception e) {
                r.r1 = false;
            }
        }

    }

    // @Issue("#5193")
    @JCStressTest
    @State
    @Outcome(id = "true", expect = ACCEPTABLE, desc = "Successful scrape")
    @Outcome(expect = FORBIDDEN, desc = "Failed scrape")
    public static class ConsistentLongTaskTimerHistogram {

        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        LongTaskTimer ltt = LongTaskTimer.builder("test").publishPercentileHistogram().register(registry);

        @Actor
        public void record() {
            Sample sample = ltt.start();
            sample.stop();
        }

        @Actor
        public void scrape(Z_Result r) {
            try {
                registry.scrape();
                r.r1 = true;
            }
            catch (Exception e) {
                r.r1 = false;
            }
        }

    }

    /*
     * Concurrent register and remove on the same meter name in PrometheusMeterRegistry.
     * Thread 1 removes an existing counter, Thread 2 registers a new counter with the same name.
     * We want to verify that Thread 2's registration is not lost or overwritten by Thread 1's removal.
     */
    @JCStressTest
    @State
    @Outcome(id = "true", expect = ACCEPTABLE, desc = "Scrape successfully includes the counter")
    @Outcome(id = "false", expect = FORBIDDEN, desc = "Scrape missed the registered counter")
    public static class ConcurrentRegisterAndRemove {

        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        Counter c1 = registry.counter("test_counter", "tag", "1");

        @Actor
        public void remove() {
            registry.remove(c1);
        }

        @Actor
        public void register() {
            registry.counter("test_counter", "tag", "2");
        }

        @Arbiter
        public void check(Z_Result r) {
            String scrape = registry.scrape();
            r.r1 = scrape.contains("test_counter_total{tag=\"2\"}");
        }
    }

}
