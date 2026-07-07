/*
 * Copyright 2017 VMware, Inc.
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
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Meter.Id}.
 *
 * @author Jon Schneider
 * @author Johnny Lim
 */
class MeterIdTest {

    @Test
    void withStatistic() {
        Meter.Id id = new Meter.Id("my.id", Tags.empty(), null, null, Meter.Type.TIMER);
        assertThat(id.withTag(Statistic.TOTAL_TIME).getTags()).contains(Tag.of("statistic", "total"));
    }

    @Test
    void equalsAndHashCode() {
        Meter.Id id = new Meter.Id("my.id", Tags.empty(), null, null, Meter.Type.COUNTER);
        Meter.Id id2 = new Meter.Id("my.id", Tags.empty(), null, null, Meter.Type.COUNTER);

        assertThat(id).isEqualTo(id2);
        assertThat(id.hashCode()).isEqualTo(id2.hashCode());
    }

    @Test
    void withTags() {
        Meter.Id id = new Meter.Id("my.id", Tags.of("k1", "v1", "k2", "v2"), null, null, Meter.Type.COUNTER);
        Meter.Id newId = id.withTags(Tags.of("k1", "n1", "k", "n"));
        assertThat(newId.getTags()).containsExactlyElementsOf(Tags.of("k2", "v2", "k1", "n1", "k", "n"));
    }

    @Test
    void replaceTags() {
        Meter.Id id = new Meter.Id("my.id", Tags.of("k1", "v1", "k2", "v2"), null, null, Meter.Type.COUNTER);
        Meter.Id newId = id.replaceTags(Tags.of("k1", "n1", "k", "n"));
        assertThat(newId.getTags()).containsExactlyElementsOf(Tags.of("k1", "n1", "k", "n"));
    }

    @Test
    void idsBuiltFromTagsAndKeyValuesWithSamePairsShouldBeEqual() {
        Meter.Id fromTags = new Meter.Id("my.meter", Tags.of("a", "1", "b", "2"), null, null, Meter.Type.COUNTER);
        Meter.Id fromKeyValues = Meter.Id.of("my.meter", KeyValues.of("a", "1", "b", "2"), null, null,
                Meter.Type.COUNTER);

        assertThat(fromTags).isEqualTo(fromKeyValues);
        assertThat(fromKeyValues).isEqualTo(fromTags);
        assertThat(fromTags.hashCode()).isEqualTo(fromKeyValues.hashCode());
    }

    @Test
    void idsBuiltFromUnsortedMixedKeyValuesShouldBeEqualToTagsBuiltOnes() {
        Meter.Id fromTags = new Meter.Id("my.meter", Tags.of("b", "2", "a", "1"), null, null, Meter.Type.COUNTER);
        Meter.Id fromKeyValues = Meter.Id.of("my.meter", Arrays.asList(Tag.of("b", "2"), KeyValue.of("a", "1")), null,
                null, Meter.Type.COUNTER);

        assertThat(fromTags).isEqualTo(fromKeyValues);
        assertThat(fromTags.hashCode()).isEqualTo(fromKeyValues.hashCode());
    }

    @Test
    void tagAccessorsOnKeyValuesBuiltIdShouldReturnEqualTags() {
        Meter.Id id = Meter.Id.of("my.meter", KeyValues.of("a", "1", "b", "2"), null, null, Meter.Type.COUNTER);

        assertThat(id.getTags()).containsExactly(Tag.of("a", "1"), Tag.of("b", "2"));
        assertThat(id.getTagsAsIterable()).containsExactly(Tag.of("a", "1"), Tag.of("b", "2"));
        assertThat(id.getTag("a")).isEqualTo("1");
        assertThat(id.getTag("nope")).isNull();
        // the lazily computed Tags view is cached
        assertThat(id.getTagsAsIterable()).isSameAs(id.getTagsAsIterable());
    }

    @Test
    void registeringWithTagsAndLookingUpWithKeyValuesShouldYieldTheSameMeter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Timer fromTags = registry.timer("my.timer", Tags.of("a", "1", "b", "2"));
        Timer fromKeyValues = registry.timer("my.timer", KeyValues.of("a", "1", "b", "2"));

        assertThat(fromKeyValues).isSameAs(fromTags);
        assertThat(registry.getMeters()).hasSize(1);
    }

    @Test
    void registeringWithKeyValuesAndLookingUpWithTagsShouldYieldTheSameMeter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Timer fromKeyValues = registry.timer("my.timer", KeyValues.of("a", "1", "b", "2"));
        Timer fromTags = registry.timer("my.timer", Tags.of("a", "1", "b", "2"));

        assertThat(fromTags).isSameAs(fromKeyValues);
        assertThat(registry.getMeters()).hasSize(1);
    }

    @Test
    void registeringWithKeyValuesWithMeterFilterConfiguredShouldYieldTheSameMeter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // a mapping filter forces the mapId/meterMap path in addition to the
        // preFilterIdToMeterMap path
        registry.config().commonTags("common", "tag");

        Timer fromKeyValues = registry.timer("my.timer", KeyValues.of("a", "1", "b", "2"));
        Timer fromTags = registry.timer("my.timer", Tags.of("a", "1", "b", "2"));

        assertThat(fromTags).isSameAs(fromKeyValues);
        assertThat(registry.getMeters()).hasSize(1);
        assertThat(fromTags.getId().getTag("common")).isEqualTo("tag");
    }

    @Test
    void counterAndLongTaskTimerShouldDeduplicateAcrossTagsAndKeyValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        Counter counter1 = registry.counter("my.counter", KeyValues.of("a", "1"));
        Counter counter2 = registry.counter("my.counter", Tags.of("a", "1"));
        assertThat(counter1).isSameAs(counter2);

        LongTaskTimer ltt1 = registry.more().longTaskTimer("my.ltt", KeyValues.of("a", "1"));
        LongTaskTimer ltt2 = registry.more().longTaskTimer("my.ltt", Tags.of("a", "1"));
        assertThat(ltt1).isSameAs(ltt2);

        assertThat(registry.getMeters()).hasSize(2);
    }

    @Test
    void withMethodsShouldPreserveKeyValues() {
        Meter.Id id = Meter.Id.of("my.meter", KeyValues.of("a", "1"), null, null, Meter.Type.COUNTER);

        assertThat(id.withName("other.meter").getTags()).containsExactly(Tag.of("a", "1"));
        assertThat(id.withBaseUnit("bytes").getTags()).containsExactly(Tag.of("a", "1"));
        assertThat(id.withTag(Tag.of("b", "2")).getTags()).containsExactly(Tag.of("a", "1"), Tag.of("b", "2"));
        assertThat(id.replaceTags(Tags.of("c", "3")).getTags()).containsExactly(Tag.of("c", "3"));
    }

    @Test
    void toStringShouldBeTheSameForTagsAndKeyValuesBuiltIds() {
        Meter.Id fromTags = new Meter.Id("my.meter", Tags.of("a", "1"), null, null, Meter.Type.COUNTER);
        Meter.Id fromKeyValues = Meter.Id.of("my.meter", KeyValues.of("a", "1"), null, null, Meter.Type.COUNTER);

        assertThat(fromKeyValues.toString()).isEqualTo(fromTags.toString());
    }

}
