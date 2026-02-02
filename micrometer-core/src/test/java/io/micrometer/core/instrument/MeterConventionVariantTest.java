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
package io.micrometer.core.instrument;

import io.micrometer.common.util.internal.logging.InternalLogger;
import io.micrometer.common.util.internal.logging.InternalLoggerFactory;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.MeterConvention;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@NullMarked
class MeterConventionVariantTest {
    MeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        // 1. An application framework can register known conventions before they will be retrieved
        registry.config().registerConvention(new OtelSomeConvention(), "otel");
    }

    @Test
    void conventionVariant() {
        // 2. A user configures the variant they want
        registry.config().conventionVariant("otel");

        assertThat(registry.config().getConvention(SomeConvention.class)).isNotNull().isInstanceOf(OtelSomeConvention.class);
    }

    interface SomeConvention extends MeterConvention<@Nullable Void> {}

    static class OtelSomeConvention implements SomeConvention {

        @Override
        public String getName() {
            return "some.counter";
        }
    }

    static class SomeInstrumentation implements MeterBinder {

        private static final InternalLogger logger = InternalLoggerFactory.getInstance(SomeInstrumentation.class);

        private final @Nullable SomeConvention userProvidedConvention;

        public SomeInstrumentation() {
            this.userProvidedConvention = null;
        }

        public SomeInstrumentation(SomeConvention userProvidedConvention) {
            // 3. Each instrumentation offers a way for users to override with a specific convention
            this.userProvidedConvention = userProvidedConvention;
        }

        @Override
        public void bindTo(MeterRegistry registry) {
            // 4. When binding, use the user-provided convention or retrieve from the registry
            SomeConvention convention = this.userProvidedConvention == null ? registry.config().getConvention(SomeConvention.class) : this.userProvidedConvention;
            if (convention == null) {
                logger.warn("No convention configured or found");
                return;
            }

            Counter.builder(convention.getName()).tags(convention.getTags(null)).register(registry);
        }
    }
}
