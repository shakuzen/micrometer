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
import io.micrometer.observation.transport.SenderContext;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * A {@link SenderContext} for Kafka producer sends.
 *
 * @since 1.17.0
 */
public class KafkaSenderContext extends SenderContext<ProducerRecord<?, ?>> {

    /**
     * Create a new {@link KafkaSenderContext} for the given producer record.
     * @param producerRecord the record being sent
     */
    public KafkaSenderContext(ProducerRecord<?, ?> producerRecord) {
        super((carrier, key, value) -> {
            if (carrier != null) {
                carrier.headers().add(key, value != null ? value.getBytes(StandardCharsets.UTF_8) : null);
            }
        }, Kind.PRODUCER);
        setCarrier(producerRecord);
    }

    private static final String KEY_PARTITION = "kafka.partition";

    private static final String KEY_OFFSET = "kafka.offset";

    private static final String KEY_EXCEPTION = "kafka.exception";

    /**
     * Set the record metadata or exception when available (e.g. in
     * {@code ProducerInterceptor.onAcknowledgement}). Used by the default convention to
     * set outcome and partition key values.
     * @param partition the partition the record was sent to, or null if unknown
     * @param offset the offset, or null if unknown
     * @param exception the exception if send failed
     */
    @SuppressWarnings("NullAway")
    public void setRecordMetadata(@Nullable Integer partition, @Nullable Long offset, @Nullable Exception exception) {
        if (partition != null) {
            put(KEY_PARTITION, partition);
        }
        if (offset != null) {
            put(KEY_OFFSET, offset);
        }
        if (exception != null) {
            put(KEY_EXCEPTION, exception);
        }
    }

    /**
     * Get the partition from record metadata, if set.
     * @return partition or null
     */
    @Nullable
    public Integer getPartition() {
        return get(KEY_PARTITION);
    }

    /**
     * Get the offset from record metadata, if set.
     * @return offset or null
     */
    @Nullable
    public Long getOffset() {
        return get(KEY_OFFSET);
    }

    /**
     * Get the exception from send failure, if set.
     * @return exception or null
     */
    @Nullable
    public Exception getException() {
        return get(KEY_EXCEPTION);
    }

}
