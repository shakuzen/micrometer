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
package io.micrometer.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link KeyValue} equality/hash code/ordering contract.
 */
class KeyValueTest {

    @Test
    void equalsAndHashCodeShouldBeSymmetricAcrossImplementations() {
        KeyValue immutable = KeyValue.of("a", "b");
        KeyValue validated = KeyValue.of("a", "b", v -> true);

        assertThat(immutable).isEqualTo(validated);
        assertThat(validated).isEqualTo(immutable);
        assertThat(immutable.hashCode()).isEqualTo(validated.hashCode());
    }

    @Test
    void equalsShouldAcceptAnyKeyValueImplementation() {
        KeyValue immutable = KeyValue.of("a", "b");
        KeyValue custom = new KeyValue() {
            @Override
            public String getKey() {
                return "a";
            }

            @Override
            public String getValue() {
                return "b";
            }
        };

        assertThat(immutable).isEqualTo(custom);
    }

    @Test
    void shouldNotBeEqualForDifferentKeyOrValue() {
        assertThat(KeyValue.of("a", "b")).isNotEqualTo(KeyValue.of("a", "c"));
        assertThat(KeyValue.of("a", "b")).isNotEqualTo(KeyValue.of("c", "b"));
        assertThat(KeyValue.of("a", "b", v -> true)).isNotEqualTo(KeyValue.of("a", "c", v -> true));
    }

    @Test
    void hashCodeShouldFollowTheDocumentedFormula() {
        assertThat(KeyValue.of("a", "b").hashCode()).isEqualTo(31 * "a".hashCode() + "b".hashCode());
        assertThat(KeyValue.of("a", "b", v -> true).hashCode()).isEqualTo(31 * "a".hashCode() + "b".hashCode());
    }

    @Test
    void compareToShouldOrderByKeyOnly() {
        assertThat(KeyValue.of("a", "b").compareTo(KeyValue.of("a", "z"))).isZero();
        assertThat(KeyValue.of("a", "b").compareTo(KeyValue.of("b", "b"))).isNegative();
        assertThat(KeyValue.of("b", "b").compareTo(KeyValue.of("a", "b"))).isPositive();
    }

    @Test
    void shouldNotBeEqualToNullOrOtherTypes() {
        assertThat(KeyValue.of("a", "b")).isNotEqualTo(null);
        assertThat(KeyValue.of("a", "b")).isNotEqualTo("a=b");
    }

}
