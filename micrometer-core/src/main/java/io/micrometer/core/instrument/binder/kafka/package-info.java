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

/**
 * Meter binders and Observation API support for Apache Kafka.
 * <p>
 * Observation support for <strong>Kafka Streams</strong> is provided via
 * {@link io.micrometer.core.instrument.binder.kafka.ObservationKafkaClientSupplier}. Set a custom
 * {@code KafkaClientSupplier} to {@link io.micrometer.core.instrument.binder.kafka.ObservationKafkaClientSupplier}
 * when building the streams application (e.g. by passing this supplier to the {@code KafkaStreams} constructor
 * that accepts a {@code KafkaClientSupplier}) so that internal producer and consumer operations are observed.
 */
@NullMarked
package io.micrometer.core.instrument.binder.kafka;

import org.jspecify.annotations.NullMarked;
