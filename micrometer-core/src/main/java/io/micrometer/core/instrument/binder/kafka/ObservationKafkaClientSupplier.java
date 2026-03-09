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
package io.micrometer.core.instrument.binder.kafka;

import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.streams.KafkaClientSupplier;

import java.util.Map;

/**
 * A {@link KafkaClientSupplier} that creates producers and consumers instrumented with
 * the Observation API. Use this when building Kafka Streams applications to enable
 * observability instrumentation for internal producer and consumer operations.
 * <p>
 * Pass this supplier to the {@link org.apache.kafka.streams.KafkaStreams} constructor
 * that accepts a {@code KafkaClientSupplier}:
 *
 * <pre>
 * Properties props = new Properties();
 * props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
 * props.put(StreamsConfig.APPLICATION_ID_CONFIG, "my-app");
 * KafkaStreams streams = new KafkaStreams(builder.build(), props,
 *         new ObservationKafkaClientSupplier(observationRegistry));
 * </pre>
 *
 * @since 1.17.0
 */
public final class ObservationKafkaClientSupplier implements KafkaClientSupplier {

    private final ObservationRegistry observationRegistry;

    /**
     * Create a new supplier that instruments clients with the given registry.
     * @param observationRegistry the registry to use for observations
     */
    public ObservationKafkaClientSupplier(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Override
    public AdminClient getAdmin(Map<String, Object> config) {
        return AdminClient.create(config);
    }

    @Override
    public Producer<byte[], byte[]> getProducer(Map<String, Object> config) {
        return new ObservationProducer<>(new KafkaProducer<>(config, new ByteArraySerializer(), new ByteArraySerializer()), observationRegistry);
    }

    @Override
    public Consumer<byte[], byte[]> getConsumer(Map<String, Object> config) {
        return new ObservationConsumer<>(new KafkaConsumer<>(config, new ByteArrayDeserializer(), new ByteArrayDeserializer()), observationRegistry);
    }

    @Override
    public Consumer<byte[], byte[]> getRestoreConsumer(Map<String, Object> config) {
        return getConsumer(config);
    }

    @Override
    public Consumer<byte[], byte[]> getGlobalConsumer(Map<String, Object> config) {
        return getConsumer(config);
    }

}
