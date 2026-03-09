package io.micrometer.core.instrument.binder.kafka;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.errors.ProducerFencedException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

final class ObservationProducer<K, V> implements Producer<K, V> {

    private final Producer<K, V> delegate;
    private final ObservationRegistry observationRegistry;

    ObservationProducer(Producer<K, V> delegate, ObservationRegistry observationRegistry) {
        this.delegate = delegate;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public void initTransactions() {
        delegate.initTransactions();
    }

    @Override
    public void beginTransaction() throws ProducerFencedException {
        delegate.beginTransaction();
    }

    @Override
    public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> offsets, String consumerGroupId) throws ProducerFencedException {
        delegate.sendOffsetsToTransaction(offsets, consumerGroupId);
    }

    @Override
    public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> offsets, ConsumerGroupMetadata groupMetadata) throws ProducerFencedException {
        delegate.sendOffsetsToTransaction(offsets, groupMetadata);
    }

    @Override
    public void commitTransaction() throws ProducerFencedException {
        delegate.commitTransaction();
    }

    @Override
    public void abortTransaction() throws ProducerFencedException {
        delegate.abortTransaction();
    }

    @Override
    @SuppressWarnings("NullAway")
    public Future<RecordMetadata> send(ProducerRecord<K, V> record) {
        return send(record, null);
    }

    @Override
    public Future<RecordMetadata> send(ProducerRecord<K, V> record, @org.jspecify.annotations.Nullable Callback callback) {
        if (observationRegistry.isNoop()) {
            return delegate.send(record, callback);
        }

        KafkaSenderContext context = new KafkaSenderContext(record);
        Observation observation = KafkaObservationDocumentation.KAFKA_PRODUCER
                .observation(null, new DefaultKafkaProducerObservationConvention(), () -> context, observationRegistry)
                .start();

        @SuppressWarnings("unchecked")
        ProducerRecord<K, V> carrier = (ProducerRecord<K, V>) context.getCarrier();
        ProducerRecord<K, V> recordToSend = carrier != null ? carrier : record;

        Callback wrappedCallback = (metadata, exception) -> {
            if (metadata != null) {
                context.setRecordMetadata(metadata.partition(), metadata.offset(), exception);
            } else if (exception != null) {
                context.setRecordMetadata(null, null, exception);
                observation.error(exception);
            }
            observation.stop();
            if (callback != null) {
                callback.onCompletion(metadata, exception);
            }
        };

        return delegate.send(recordToSend, wrappedCallback);
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public List<PartitionInfo> partitionsFor(String topic) {
        return delegate.partitionsFor(topic);
    }

    @Override
    public Map<MetricName, ? extends Metric> metrics() {
        return delegate.metrics();
    }

    @Override
    public Uuid clientInstanceId(Duration timeout) {
        return delegate.clientInstanceId(timeout);
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public void close(Duration timeout) {
        delegate.close(timeout);
    }
}
