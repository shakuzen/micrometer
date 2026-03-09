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
import io.micrometer.observation.tck.TestObservationRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("docker")
class ObservationKafkaClientSupplierIntegrationTest {

    @Container
    private final ConfluentKafkaContainer kafkaContainer = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:8.0.3"));

    TestObservationRegistry registry = TestObservationRegistry.create();
    ObservationKafkaClientSupplier supplier = new ObservationKafkaClientSupplier(registry);

    @Test
    void producerFromSupplierRecordsObservationsWhenSending() throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, Object> producerConfig = new HashMap<>();
        producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());

        try (var producer = supplier.getProducer(producerConfig)) {
            Future<RecordMetadata> metadataFuture = producer.send(new ProducerRecord<>("observation-test-topic", "key".getBytes(StandardCharsets.UTF_8), "value".getBytes(StandardCharsets.UTF_8)));
            producer.flush();
            metadataFuture.get(1, TimeUnit.SECONDS);
        }

        assertThat(registry).hasObservationWithNameEqualTo("kafka.send")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("kafka.topic", "observation-test-topic")
                .hasLowCardinalityKeyValue("outcome", "SUCCESS");
    }

    @Test
    void consumerFromSupplierRecordsObservationsWhenPolling() {
        String topic = "observation-receive-test";

        Map<String, Object> producerConfig = new HashMap<>();
        producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());

        try (var producer = supplier.getProducer(producerConfig)) {
            producer.send(new ProducerRecord<>(topic, "key".getBytes(StandardCharsets.UTF_8), "value".getBytes(StandardCharsets.UTF_8)));
            producer.flush();
        }

        Map<String, Object> consumerConfig = new HashMap<>();
        consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "observation-receive-test-group");
        consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (var consumer = supplier.getConsumer(consumerConfig)) {
            consumer.subscribe(Collections.singletonList(topic));
            ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofSeconds(1));
            assertThat(records).isNotEmpty();
        }

        assertThat(registry).hasObservationWithNameEqualTo("kafka.receive")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("kafka.topic", topic);
    }

    @Test
    void kafkaStreamsWithSupplierRecordsObservations() throws Exception {
        String inputTopic = "streams-input";
        String outputTopic = "streams-output";

        Map<String, Object> adminConfig = new HashMap<>();
        adminConfig.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        try (AdminClient adminClient = AdminClient.create(adminConfig)) {
            adminClient.createTopics(java.util.Arrays.asList(
                    new NewTopic(inputTopic, 1, (short) 1),
                    new NewTopic(outputTopic, 1, (short) 1)
            )).all().get(10, TimeUnit.SECONDS);
        }

        Map<String, Object> streamProps = new HashMap<>();
        streamProps.put("bootstrap.servers", kafkaContainer.getBootstrapServers());
        streamProps.put("application.id", "observation-streams-test");
        streamProps.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, org.apache.kafka.common.serialization.Serdes.ByteArraySerde.class.getName());
        streamProps.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, org.apache.kafka.common.serialization.Serdes.ByteArraySerde.class.getName());
        streamProps.put(StreamsConfig.STATE_DIR_CONFIG, Files.createTempDirectory("kafka-streams").toAbsolutePath().toString());

        StreamsBuilder builder = new StreamsBuilder();
        builder.stream(inputTopic).to(outputTopic);

        Properties streamsProps = new Properties();
        streamsProps.putAll(streamProps);
        try (KafkaStreams streams = new KafkaStreams(builder.build(), streamsProps, supplier)) {
            streams.setUncaughtExceptionHandler(e -> {
                System.err.println("Streams uncaught exception:");
                e.printStackTrace();
                return org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT;
            });
            streams.start();
            awaitStreamsReady(streams);

            try (var producer = supplier.getProducer(producerConfig(streamProps))) {
                producer.send(new ProducerRecord<>(inputTopic, "k".getBytes(StandardCharsets.UTF_8), "v".getBytes(StandardCharsets.UTF_8)));
                producer.flush();
            }

            awaitRecordInOutput(outputTopic, kafkaContainer.getBootstrapServers());
        }

        assertThat(registry).hasObservationWithNameEqualTo("kafka.send");
        assertThat(registry).hasObservationWithNameEqualTo("kafka.receive");
    }

    private static Map<String, Object> producerConfig(Map<String, Object> base) {
        Map<String, Object> config = new HashMap<>(base);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return config;
    }

    private static void awaitStreamsReady(KafkaStreams streams) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            if (streams.state() == KafkaStreams.State.RUNNING) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Streams did not reach RUNNING state. Current state: " + streams.state());
    }

    private static void awaitRecordInOutput(String outputTopic, String bootstrapServers)
            throws InterruptedException {
        Map<String, Object> consumerConfig = new HashMap<>();
        consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "streams-output-reader");
        consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        ObservationKafkaClientSupplier supplier = new ObservationKafkaClientSupplier(ObservationRegistry.NOOP);
        try (var consumer = supplier.getConsumer(consumerConfig)) {
            consumer.subscribe(Collections.singletonList(outputTopic));
            for (int i = 0; i < 20; i++) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofSeconds(1));
                if (!records.isEmpty()) {
                    return;
                }
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("No record received from " + outputTopic);
    }
}
