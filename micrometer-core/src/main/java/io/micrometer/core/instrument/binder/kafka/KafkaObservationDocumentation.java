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

import io.micrometer.common.docs.KeyName;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.docs.ObservationDocumentation;

/**
 * {@link ObservationDocumentation} for Kafka producer and consumer instrumentation.
 *
 * @since 1.17.0
 */
public enum KafkaObservationDocumentation implements ObservationDocumentation {

    /**
     * Observation for Kafka producer send.
     */
    KAFKA_PRODUCER {
        @Override
        public Class<? extends ObservationConvention<? extends Observation.Context>> getDefaultConvention() {
            return DefaultKafkaProducerObservationConvention.class;
        }

        @Override
        public KeyName[] getLowCardinalityKeyNames() {
            return KafkaProducerKeyNames.values();
        }
    },

    /**
     * Observation for Kafka consumer receive.
     */
    KAFKA_CONSUMER {
        @Override
        public Class<? extends ObservationConvention<? extends Observation.Context>> getDefaultConvention() {
            return DefaultKafkaConsumerObservationConvention.class;
        }

        @Override
        public KeyName[] getLowCardinalityKeyNames() {
            return KafkaConsumerKeyNames.values();
        }
    };

    /**
     * Low cardinality key names for Kafka producer observations.
     */
    public enum KafkaProducerKeyNames implements KeyName {

        TOPIC {
            @Override
            public String asString() {
                return "kafka.topic";
            }
        },

        PARTITION {
            @Override
            public String asString() {
                return "kafka.partition";
            }
        },

        OUTCOME {
            @Override
            public String asString() {
                return "outcome";
            }
        }

    }

    /**
     * Low cardinality key names for Kafka consumer observations.
     */
    public enum KafkaConsumerKeyNames implements KeyName {

        TOPIC {
            @Override
            public String asString() {
                return "kafka.topic";
            }
        },

        PARTITION {
            @Override
            public String asString() {
                return "kafka.partition";
            }
        },

        RECORD_COUNT {
            @Override
            public String asString() {
                return "kafka.record.count";
            }
        }

    }

}
