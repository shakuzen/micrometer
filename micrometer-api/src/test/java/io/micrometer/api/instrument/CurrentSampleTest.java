/*
 * Copyright 2021 VMware, Inc.
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
package io.micrometer.api.instrument;

import io.micrometer.api.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentSampleTest {

    MeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void nestedSamples_parentChildThreadsInstrumented() throws ExecutionException, InterruptedException {
        ExecutorService taskRunner = Executors.newSingleThreadExecutor();

        Observation sample = Observation.start(registry);
        System.out.println("Outside task: " + sample);
        assertThat(registry.getCurrentSample()).isNull();
        try (Observation.Scope scope = sample.makeCurrent()) {
            assertThat(registry.getCurrentSample()).isSameAs(sample);
            taskRunner.submit(() -> {
                System.out.println("In task: " + registry.getCurrentSample());
                assertThat(registry.getCurrentSample()).isNotEqualTo(sample);
            }).get();
        }
        assertThat(registry.getCurrentSample()).isNull();
        sample.stop(Timer.builder("my.service"));
    }

    @Test
    void start_thenStopOnChildThread() throws InterruptedException, ExecutionException {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Observation sample = Observation.start(registry);
        assertThat(registry.getCurrentSample()).isNull();
        executor.submit(() -> {
            try (Observation.Scope scope = sample.makeCurrent()) {
                assertThat(registry.getCurrentSample()).isEqualTo(sample);
            }
            sample.stop(Timer.builder("my.timer"));
        }).get();

        assertThat(registry.getCurrentSample()).isNull();
    }

    @Test
    void startOnChildThread_thenStopOnSiblingThread() throws InterruptedException, ExecutionException {
        // 2 thread pools with 1 thread each, so a different thread is used for the 2 tasks
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService executor2 = Executors.newSingleThreadExecutor();
        Map<String, Observation> sampleMap = new HashMap<>();

        executor.submit(() -> {
            Observation sample = Observation.start(registry);
            assertThat(registry.getCurrentSample()).isNull();
            sampleMap.put("mySample", sample);
        }).get();

        executor2.submit(() -> {
            Observation mySample = sampleMap.get("mySample");
            try (Observation.Scope scope = mySample.makeCurrent()) {
                assertThat(registry.getCurrentSample()).isEqualTo(mySample);
            }
            mySample.stop(Timer.builder("my.timer"));
            assertThat(registry.getCurrentSample()).isNull();
        }).get();

        assertThat(registry.getCurrentSample()).isNull();
    }

    @Test
    void nestedSamples_sameThread() {
        Observation sample = Observation.start(registry);
        Observation sample2;
        assertThat(registry.getCurrentSample()).isNull();
        try (Observation.Scope scope = sample.makeCurrent()) {
            sample2 = Observation.start(registry);
            assertThat(registry.getCurrentSample()).isSameAs(sample);
        }
        try (Observation.Scope scope = sample2.makeCurrent()) {
            sample.stop(Timer.builder("test1"));
            assertThat(registry.getCurrentSample()).isSameAs(sample2);
        }
        sample2.stop(Timer.builder("test2"));

        assertThat(registry.getCurrentSample()).isNull();
    }
}
