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
package io.micrometer.core.instrument.internal;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Tag;

import java.util.Iterator;

/**
 * A lazily converting {@code Iterable<Tag>} view of {@link KeyValues}, for internal use
 * when passing key values through {@code Iterable}-typed meter-creation methods that
 * may be overridden by registries compiled against versions where those methods were
 * declared with {@code Iterable<Tag>}. Such overrides can safely iterate this view
 * (each element is a {@link Tag}), while {@code Meter.Id} unwraps it to the underlying
 * {@link KeyValues} without any per-element conversion when no override intervenes.
 *
 * @author Tommy Ludwig
 * @since 1.18.0
 */
public final class KeyValuesTagIterable implements Iterable<Tag> {

    private final KeyValues keyValues;

    public KeyValuesTagIterable(KeyValues keyValues) {
        this.keyValues = keyValues;
    }

    /**
     * The underlying key values backing this view.
     * @return the key values this instance was created with
     */
    public KeyValues getKeyValues() {
        return keyValues;
    }

    @Override
    public Iterator<Tag> iterator() {
        Iterator<KeyValue> delegate = keyValues.iterator();
        return new Iterator<Tag>() {

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public Tag next() {
                KeyValue keyValue = delegate.next();
                return keyValue instanceof Tag ? (Tag) keyValue : Tag.of(keyValue.getKey(), keyValue.getValue());
            }

        };
    }

}
