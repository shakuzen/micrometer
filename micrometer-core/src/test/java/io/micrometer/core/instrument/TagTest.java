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
package io.micrometer.core.instrument;

import io.micrometer.common.KeyValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Tag} as a {@link KeyValue}, in particular the cross-implementation
 * equality/hash code/ordering contract.
 */
class TagTest {

    @Test
    void tagIsAKeyValue() {
        assertThat(Tag.of("a", "b")).isInstanceOf(KeyValue.class);
    }

    @Test
    void equalsAndHashCodeShouldBeSymmetricBetweenTagAndKeyValue() {
        Tag tag = Tag.of("a", "b");
        KeyValue keyValue = KeyValue.of("a", "b");

        assertThat(tag).isEqualTo(keyValue);
        assertThat(keyValue).isEqualTo(tag);
        assertThat(tag.hashCode()).isEqualTo(keyValue.hashCode());
    }

    @Test
    void shouldNotBeEqualForDifferentKeyOrValue() {
        assertThat(Tag.of("a", "b")).isNotEqualTo(KeyValue.of("a", "c"));
        assertThat(Tag.of("a", "b")).isNotEqualTo(KeyValue.of("c", "b"));
        assertThat(Tag.of("a", "b")).isNotEqualTo(Tag.of("a", "c"));
    }

    @Test
    void hashCodeShouldFollowTheKeyValueContract() {
        assertThat(Tag.of("a", "b").hashCode()).isEqualTo(31 * "a".hashCode() + "b".hashCode());
    }

    @Test
    void naturalOrderingShouldBeByKey() {
        Tag[] tags = new Tag[] { Tag.of("c", "1"), Tag.of("a", "2"), Tag.of("b", "3") };
        Arrays.sort(tags);
        assertThat(tags).extracting(Tag::getKey).containsExactly("a", "b", "c");

        List<Tag> tagList = new ArrayList<>(Arrays.asList(Tag.of("b", "1"), Tag.of("a", "2")));
        Collections.sort(tagList);
        assertThat(tagList).extracting(Tag::getKey).containsExactly("a", "b");

        TreeSet<Tag> tagSet = new TreeSet<>(Arrays.asList(Tag.of("b", "1"), Tag.of("a", "2")));
        assertThat(tagSet).extracting(Tag::getKey).containsExactly("a", "b");
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedCompareToTagOverloadShouldCompareByKeyOnly() {
        assertThat(Tag.of("a", "b").compareTo(Tag.of("a", "z"))).isZero();
        assertThat(Tag.of("a", "b").compareTo(Tag.of("b", "b"))).isNegative();
    }

    @Test
    void compareToKeyValueShouldCompareByKeyOnly() {
        KeyValue tag = Tag.of("a", "b");
        assertThat(tag.compareTo(KeyValue.of("a", "z"))).isZero();
        assertThat(tag.compareTo(KeyValue.of("b", "b"))).isNegative();
    }

}
