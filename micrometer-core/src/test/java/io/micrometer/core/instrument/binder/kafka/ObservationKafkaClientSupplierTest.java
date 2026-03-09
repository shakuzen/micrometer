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

import io.micrometer.observation.tck.TestObservationRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ObservationKafkaClientSupplier}.
 */
class ObservationKafkaClientSupplierTest {

    TestObservationRegistry registry = TestObservationRegistry.create();

    ObservationKafkaClientSupplier supplier = new ObservationKafkaClientSupplier(registry);

    @Test
    void getProducerReturnsInstrumentedProducer() {
        Map<String, Object> config = producerConfig();
        Producer<byte[], byte[]> producer = supplier.getProducer(config);
        assertThat(producer).isNotNull();

        producer.send(new ProducerRecord<>("test-topic", "key".getBytes(StandardCharsets.UTF_8), "value".getBytes(StandardCharsets.UTF_8)));

        assertThat(registry).hasObservationWithNameEqualTo("kafka.send")
            .that()
            .hasBeenStarted();
        producer.close();
    }


    private static Map<String, Object> producerConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", "localhost:9092");
        config.put("key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        config.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        return config;
    }

    @Test
    void getConsumerReturnsInstrumentedConsumer() {
        Consumer<byte[], byte[]> consumer = supplier.getConsumer(consumerConfig("test-group"));
        assertThat(consumer).isNotNull();
        consumer.close();
    }

    @Test
    void getRestoreConsumerReturnsConsumer() {
        Consumer<byte[], byte[]> consumer = supplier.getRestoreConsumer(consumerConfig("restore-group"));
        assertThat(consumer).isNotNull();
        consumer.close();
    }

    @Test
    void getGlobalConsumerReturnsConsumer() {
        Consumer<byte[], byte[]> consumer = supplier.getGlobalConsumer(consumerConfig("global-group"));
        assertThat(consumer).isNotNull();
        consumer.close();
    }

    private static Map<String, Object> consumerConfig(String groupId) {
        Map<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", "localhost:9092");
        config.put("group.id", groupId);
        config.put("key.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        config.put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        return config;
    }

    @Test
    void getAdminReturnsAdminClient() {
        Map<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", "localhost:9092");
        assertThat(supplier.getAdmin(config)).isNotNull();
    }

}
