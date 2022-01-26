/*
 * Copyright 2022 VMware, Inc.
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

import io.micrometer.api.lang.Nullable;

import java.io.Closeable;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Record the observation of arbitrary code execution by marking the start and stop.
 * This produces a {@link Timer} and handlers configured on the {@link MeterRegistry} can provide
 * additional behavior based on the observation.
 * For example, a handler can add distributed tracing or logging to an Observation instrumentation.
 *
 * @since 2.0.0
 */
public final class Observation {
    // TODO port wrap runnable/lambda; recommend this to users over lower-level explicit calls to Scope etc.

    private final static Observation NO_OP = null;

    private final String name;
    private final Collection<ObservationHandler> handlers;
    private final HandlerContext handlerContext;
    private final MeterRegistry registry;
    private final long startTime;
    private final Clock clock;

    /**
     * Start a timing sample.
     *
     * @param registry A meter registry whose clock is to be used
     * @return A timing sample with start time recorded.
     */
    public static Observation start(String name, MeterRegistry registry) {
        return start(name, registry, new HandlerContext());
    }
    // Idea from Jonatan: start with a Timer - cannot change tags/name; useful for static tags timer and saving allocation

    /**
     * Start an Observable.
     *
     * @param registry A meter registry whose clock is to be used
     * @param handlerContext handler context
     * @return A timing sample with start time recorded.
     */
    public static Observation start(String name, MeterRegistry registry, HandlerContext handlerContext) {
        if (!registry.config().observation().isEnabled(name)) {
            return NO_OP;
        }
        return new Observation(name, registry, handlerContext);
    }

    private Observation(String name, MeterRegistry registry, HandlerContext ctx) {
        this.name = name;
        this.clock = registry.config().clock();
        this.startTime = this.clock.monotonicTime();
        this.handlerContext = ctx;
        this.handlers = registry.config().observation().getObservationHandlers().stream()
                .filter(handler -> handler.supportsContext(this.handlerContext))
                .collect(Collectors.toList());
        notifyOnSampleStarted();
        this.registry = registry;
    }

    public void tags() {
        // TODO do we want two ways to set tags? here and via the context
    }

    public void error(Throwable throwable) {
        notifyOnError(throwable);
    }

    public void stop() {
        Timer timer = Timer.builder(name).tags(this.handlerContext.getLowCardinalityTags()).register(this.registry);
        long duration = clock.monotonicTime() - startTime;
        timer.record(duration, TimeUnit.NANOSECONDS);
        notifyOnSampleStopped(timer, Duration.ofNanos(duration));
    }

    /**
     * Make this sample current.
     *
     * @return newly opened scope
     * @since 2.0.0
     */
    public Scope makeCurrent() {
        notifyOnScopeOpened();
        return registry.openNewScope(this);
    }

    private void notifyOnSampleStarted() {
        this.handlers.forEach(handler -> handler.onStart(this, this.handlerContext));
    }

    private void notifyOnError(Throwable throwable) {
        this.handlers.forEach(handler -> handler.onError(this, this.handlerContext, throwable));
    }

    private void notifyOnScopeOpened() {
        this.handlers.forEach(handler -> handler.onScopeOpened(this, this.handlerContext));
    }

    private void notifyOnScopeClosed() {
        this.handlers.forEach(handler -> handler.onScopeClosed(this, this.handlerContext));
    }

    private void notifyOnSampleStopped(Timer timer, Duration duration) {
        this.handlers.forEach(handler -> handler.onStop(this, this.handlerContext, timer, duration));
    }

    /**
     * Nestable bounding for {@link Timer timed} operations that capture and pass along already opened scopes.
     *
     * @since 2.0.0
     */
    public static class Scope implements Closeable {
        private final ThreadLocal<Observation> threadLocal;
        private final Observation currentSample;
        private final Observation previousSample;

        public Scope(ThreadLocal<Observation> threadLocal, Observation currentSample) {
            this.threadLocal = threadLocal;
            this.currentSample = currentSample;
            this.previousSample = threadLocal.get();
            threadLocal.set(currentSample);
        }

        public Observation getSample() {
            return this.currentSample;
        }

        @Override
        public void close() {
            this.currentSample.notifyOnScopeClosed();
            threadLocal.set(previousSample);
        }
    }

    /**
     * Context for {@link Observation} instances used by {@link ObservationHandler} to pass arbitrary objects between
     * handler methods. Usage is similar to the JDK {@link Map} API.
     *
     * @since 2.0.0
     */
    @SuppressWarnings("unchecked")
    public static class HandlerContext implements TagsProvider {
        private final Map<Class<?>, Object> map = new HashMap<>();

        /**
         * The name of the recorded measurement in the context of the {@link ObservationHandler}.
         * The Timer itself has a name but you might want to use a different name in the {@link ObservationHandler}.
         * This method makes it possible to use a different name.
         *
         * @return the contextual name
         */
        @Nullable
        public String getContextualName() {
            return null;
        }

        public <T> HandlerContext put(Class<T> clazz, T object) {
            this.map.put(clazz, object);
            return this;
        }

        public void remove(Class<?> clazz) {
            this.map.remove(clazz);
        }

        @Nullable public <T> T get(Class<T> clazz) {
            return (T) this.map.get(clazz);
        }

        public <T> T getOrDefault(Class<T> clazz, T defaultObject) {
            return (T) this.map.getOrDefault(clazz, defaultObject);
        }

        public <T> T computeIfAbsent(Class<T> clazz, Function<Class<?>, ? extends T> mappingFunction) {
            return (T) this.map.computeIfAbsent(clazz, mappingFunction);
        }

        public void clear() {
            this.map.clear();
        }
    }

}
