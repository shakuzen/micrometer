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
package io.micrometer.common;

import java.util.function.Function;
import java.util.function.Predicate;

import io.micrometer.common.docs.KeyName;

/**
 * Key/value pair representing a dimension of a meter used to classify and drill into
 * measurements.
 * <p>
 * All implementations must obey the following contract, comparable to the one of
 * {@link java.util.Map.Entry}, so that instances of any two implementations can be used
 * interchangeably, for example as elements of the same {@link KeyValues} or as parts of
 * map keys:
 * <ul>
 * <li>equality: two {@code KeyValue} instances are equal if and only if both their keys
 * and their values are equal, i.e.
 * {@code o instanceof KeyValue && getKey().equals(((KeyValue) o).getKey()) && getValue().equals(((KeyValue) o).getValue())}</li>
 * <li>hash code: {@code 31 * getKey().hashCode() + getValue().hashCode()}</li>
 * <li>ordering: the {@linkplain #compareTo(KeyValue) natural ordering} is by key only.
 * Note that the natural ordering is therefore inconsistent with equals: two instances
 * with the same key and different values compare as equal but are not equal.</li>
 * </ul>
 *
 * @author Jon Schneider
 * @since 1.10.0
 */
public interface KeyValue extends Comparable<KeyValue> {

    /**
     * Use this if you want to indicate that the value is missing.
     */
    String NONE_VALUE = "none";

    String getKey();

    String getValue();

    /**
     * Creates a {@link KeyValue} for the given key and value.
     * @param key key of the KeyValue
     * @param value value for key
     * @return KeyValue
     */
    static KeyValue of(String key, String value) {
        return new ImmutableKeyValue(key, value);
    }

    /**
     * Creates a {@link KeyValue} for the given {@link KeyName} and value.
     * @param keyName name of the key of the KeyValue
     * @param value value for key
     * @return KeyValue
     */
    static KeyValue of(KeyName keyName, String value) {
        return KeyValue.of(keyName.asString(), value);
    }

    /**
     * Creates a {@link KeyValue} for the given {@code element} by extracting a key and
     * value from it.
     * @param element the source element
     * @param keyExtractor function to extract the key from the element
     * @param valueExtractor function to extract the value from the element
     * @return KeyValue
     */
    static <E> KeyValue of(E element, Function<E, String> keyExtractor, Function<E, String> valueExtractor) {
        return KeyValue.of(keyExtractor.apply(element), valueExtractor.apply(element));
    }

    /**
     * Creates a {@link KeyValue} for the given key and value and additionally validates
     * it with the {@link Predicate}.
     * @param key key of the KeyValue
     * @param value value for key
     * @param validator the {@link Predicate} used for validating the value
     * @return KeyValue
     */
    static <T> KeyValue of(String key, T value, Predicate<? super T> validator) {
        return new ValidatedKeyValue<>(key, value, validator);
    }

    /**
     * Creates a {@link KeyValue} for the given {@link KeyName} and value and additionally
     * validates it with the {@link Predicate}.
     * @param keyName name of the key of the KeyValue
     * @param value value for key
     * @param validator the {@link Predicate} used for validating the value
     * @return KeyValue
     */
    static <T> KeyValue of(KeyName keyName, T value, Predicate<? super T> validator) {
        return KeyValue.of(keyName.asString(), value, validator);
    }

    /**
     * Compares by key only. Note that this natural ordering is inconsistent with equals:
     * two instances with the same key and different values compare as equal but are not
     * equal.
     * @param o the {@code KeyValue} to be compared
     * @return the value {@code 0} if the key of the argument is equal to the key of this
     * {@code KeyValue}; a value less than {@code 0} if the key of this {@code KeyValue}
     * is lexicographically less than the key of the argument; and a value greater than
     * {@code 0} if the key of this {@code KeyValue} is lexicographically greater than the
     * key of the argument
     */
    @Override
    default int compareTo(KeyValue o) {
        return getKey().compareTo(o.getKey());
    }

}
