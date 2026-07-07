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

/**
 * Key/value pair representing a dimension of a meter used to classify and drill into
 * measurements.
 * <p>
 * As of 1.18.0, {@code Tag} extends {@link KeyValue} and all implementations must obey
 * the equality, hash code, and ordering contract specified on {@link KeyValue}. Any
 * {@code Tag} is equal to any other {@link KeyValue} with an equal key and an equal
 * value.
 *
 * @author Jon Schneider
 */
public interface Tag extends KeyValue {

    @Override
    String getKey();

    @Override
    String getValue();

    static Tag of(String key, String value) {
        return new ImmutableTag(key, value);
    }

    /**
     * Compares by key only, like {@link KeyValue#compareTo(KeyValue)}.
     * @param o the {@code Tag} to be compared
     * @return a negative integer, zero, or a positive integer as the key of this
     * {@code Tag} is lexicographically less than, equal to, or greater than the key of
     * the given {@code Tag}
     * @deprecated since 1.18.0 in favor of {@link KeyValue#compareTo(KeyValue)}. This
     * overload is retained for binary and source compatibility with code compiled against
     * earlier versions. Be aware that for implementations compiled against 1.18.0 or
     * later, overriding this method no longer affects sorting through the
     * {@link Comparable} interface (e.g. {@code Arrays.sort}), which dispatches to
     * {@link KeyValue#compareTo(KeyValue)}.
     */
    @Deprecated
    default int compareTo(Tag o) {
        return getKey().compareTo(o.getKey());
    }

}
