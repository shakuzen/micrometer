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

import io.micrometer.common.KeyValues;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.jspecify.annotations.Nullable;

import java.util.Set;

import static io.micrometer.core.instrument.binder.kafka.KafkaObservationDocumentation.KafkaConsumerKeyNames;

/**
 * Default {@link KafkaConsumerObservationConvention} for Kafka consumer receive.
 *
 * @since 1.17.0
 */
public class DefaultKafkaConsumerObservationConvention implements KafkaConsumerObservationConvention {

    private static final String UNKNOWN = "unknown";

    @Override
    public String getName() {
        return "kafka.receive";
    }

    @Override
    @Nullable
    public String getContextualName(KafkaReceiverContext context) {
        ConsumerRecords<?, ?> records = context.getCarrier();
        if (records == null || records.isEmpty()) {
            return "receive";
        }
        Set<TopicPartition> partitions = records.partitions();
        if (partitions.isEmpty()) {
            return "receive";
        }
        TopicPartition first = partitions.iterator().next();
        return "receive from " + first.topic();
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(KafkaReceiverContext context) {
        ConsumerRecords<?, ?> records = context.getCarrier();
        int count = records != null ? records.count() : 0;
        String topic = UNKNOWN;
        String partition = UNKNOWN;
        if (records != null && !records.isEmpty()) {
            Set<TopicPartition> partitions = records.partitions();
            if (!partitions.isEmpty()) {
                TopicPartition first = partitions.iterator().next();
                topic = first.topic();
                partition = String.valueOf(first.partition());
            }
        }
        return KeyValues.of(KafkaConsumerKeyNames.TOPIC.withValue(topic),
                KafkaConsumerKeyNames.PARTITION.withValue(partition),
                KafkaConsumerKeyNames.RECORD_COUNT.withValue(String.valueOf(count)));
    }

}
