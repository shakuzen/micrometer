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

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jspecify.annotations.Nullable;

import static io.micrometer.core.instrument.binder.kafka.KafkaObservationDocumentation.KafkaProducerKeyNames;

/**
 * Default {@link KafkaProducerObservationConvention} for Kafka producer send.
 *
 * @author Gary Russell
 * @since 1.17.0
 */
public class DefaultKafkaProducerObservationConvention implements KafkaProducerObservationConvention {

    private static final String UNKNOWN = "unknown";

    @Override
    public String getName() {
        return "kafka.send";
    }

    @Override
    @Nullable
    public String getContextualName(KafkaSenderContext context) {
        ProducerRecord<?, ?> record = context.getCarrier();
        if (record == null) {
            return "send";
        }
        return "send to " + record.topic();
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(KafkaSenderContext context) {
        ProducerRecord<?, ?> record = context.getCarrier();
        String topic = record != null ? record.topic() : UNKNOWN;
        Integer partition = context.getPartition();
        String partitionStr = partition != null ? String.valueOf(partition) : UNKNOWN;
        String outcome = outcome(context.getException());

        return KeyValues.of(KafkaProducerKeyNames.TOPIC.withValue(topic),
                KafkaProducerKeyNames.PARTITION.withValue(partitionStr),
                KafkaProducerKeyNames.OUTCOME.withValue(outcome));
    }

    private static String outcome(@Nullable Exception exception) {
        if (exception == null) {
            return "SUCCESS";
        }
        return "ERROR";
    }

}
