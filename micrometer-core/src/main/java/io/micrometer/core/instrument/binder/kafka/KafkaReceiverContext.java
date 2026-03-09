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
package io.micrometer.core.instrument.binder.kafka;

import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.ReceiverContext;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * A {@link ReceiverContext} for Kafka consumer receives.
 *
 * @since 1.17.0
 */
public class KafkaReceiverContext extends ReceiverContext<ConsumerRecords<?, ?>> {

    /**
     * Create a new {@link KafkaReceiverContext} for the given consumer records.
     * @param consumerRecords the records received in a poll
     */
    public KafkaReceiverContext(ConsumerRecords<?, ?> consumerRecords) {
        super((carrier, key) -> {
            if (carrier == null || carrier.isEmpty()) {
                return null;
            }
            Header header = carrier.iterator().next().headers().lastHeader(key);
            if (header != null && header.value() != null) {
                return new String(header.value(), StandardCharsets.UTF_8);
            }
            return null;
        }, Kind.CONSUMER);
        setCarrier(consumerRecords);
    }

}
