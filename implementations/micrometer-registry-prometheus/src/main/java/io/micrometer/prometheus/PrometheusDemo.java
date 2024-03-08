package io.micrometer.prometheus;

import io.micrometer.core.instrument.Clock;
import io.prometheus.metrics.core.exemplars.ExemplarSampler;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.tracer.common.SpanContext;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public class PrometheusDemo {
    public static void main(String[] args) throws InterruptedException, IOException {
        PrometheusRegistry prometheusRegistry = new PrometheusRegistry();
//        SpanContextSupplier.setSpanContext(new CustomSpanContext());
        ExemplarSampler exemplarSampler = null; // new ExemplarSampler(new ExemplarSamplerConfig(ExemplarsProperties.builder().builder(), 1));
        PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, prometheusRegistry, Clock.SYSTEM, exemplarSampler);

        meterRegistry.counter("mr.test.counter", "status", "1").increment();
        meterRegistry.counter("mr.test.counter", "status", "1").increment();
        meterRegistry.counter("mr.test.counter", "status", "2").increment();

//        meterRegistry.gauge("mr.test.gauge", Tags.of("status", "1"), args, x -> 1);
//        meterRegistry.gauge("mr.test.gauge", Tags.of("status", "2"), args, x -> 2);
//        FunctionTimer.builder("mr.test.fnt", args, x -> 1, x -> 100, TimeUnit.MILLISECONDS).tag("status", "1").register(meterRegistry);
//        FunctionTimer.builder("mr.test.fnt", args, x -> 2, x -> 200, TimeUnit.MILLISECONDS).tag("status", "2").register(meterRegistry);
//        FunctionCounter.builder("mr.test.fnc", args, x -> 1).tag("status", "1").register(meterRegistry);
//        FunctionCounter.builder("mr.test.fnc", args, x -> 2).tag("status", "2").register(meterRegistry);
//
//        List<Measurement> measurements = new ArrayList<>();
//        for (int i = 0; i < Statistic.values().length; i++) {
//            final int value = i;
//            measurements.add(new Measurement(() -> value + 10, Statistic.values()[i]));
//        }
//        Meter.builder("mr.test.custom", Type.OTHER, measurements).tag("status", "1").register(meterRegistry);
//        Meter.builder("mr.test.custom", Type.OTHER,
//            measurements.stream()
//                .map(m -> new Measurement(() -> m.getValue() + 10, m.getStatistic()))
//                .collect(Collectors.toList())
//        ).tag("status", "2").register(meterRegistry);
//
//        DistributionSummary.builder("mr.test.ds").tags("status", "1").register(meterRegistry).record(10);
//        DistributionSummary.builder("mr.test.ds").tags("status", "1").register(meterRegistry).record(11);
//        DistributionSummary.builder("mr.test.ds").tags("status", "2").register(meterRegistry).record(20);
//
//        DistributionSummary.builder("mr.test.ds").tags("status", "1").publishPercentileHistogram().register(meterRegistry).record(10);
//        DistributionSummary.builder("mr.test.ds").tags("status", "1").publishPercentileHistogram().register(meterRegistry).record(11);
//        DistributionSummary.builder("mr.test.ds").tags("status", "2").publishPercentileHistogram().register(meterRegistry).record(20);
//
//        DistributionSummary.builder("mr.test.ds").tags("status", "1").publishPercentiles(0.9).register(meterRegistry).record(10);
//        DistributionSummary.builder("mr.test.ds").tags("status", "1").publishPercentiles(0.9).register(meterRegistry).record(11);
//        DistributionSummary.builder("mr.test.ds").tags("status", "2").publishPercentiles(0.9).register(meterRegistry).record(20);
//
//        DistributionSummary.builder("mr.test.ds").tags("status", "1").publishPercentiles(0.9).publishPercentileHistogram().register(meterRegistry).record(10);
//        DistributionSummary.builder("mr.test.ds").tags("status", "1").publishPercentiles(0.9).publishPercentileHistogram().register(meterRegistry).record(11);
//        DistributionSummary.builder("mr.test.ds").tags("status", "2").publishPercentiles(0.9).publishPercentileHistogram().register(meterRegistry).record(20);
//
//        Timer.builder("mr.test.timer").tags("status", "1").register(meterRegistry).record(10, TimeUnit.SECONDS);
//        Timer.builder("mr.test.timer").tags("status", "1").register(meterRegistry).record(11, TimeUnit.SECONDS);
//        Timer.builder("mr.test.timer").tags("status", "2").register(meterRegistry).record(20, TimeUnit.SECONDS);
//
//        Timer.builder("mr.test.timer").tags("status", "1").publishPercentileHistogram().register(meterRegistry).record(10, TimeUnit.SECONDS);
//        Timer.builder("mr.test.timer").tags("status", "1").publishPercentileHistogram().register(meterRegistry).record(11, TimeUnit.SECONDS);
//        Timer.builder("mr.test.timer").tags("status", "2").publishPercentileHistogram().register(meterRegistry).record(20, TimeUnit.SECONDS);
//
//        Timer.builder("mr.test.timer").tags("status", "1").publishPercentiles(0.9).register(meterRegistry).record(10, TimeUnit.SECONDS);
//        Timer.builder("mr.test.timer").tags("status", "1").publishPercentiles(0.9).register(meterRegistry).record(11, TimeUnit.SECONDS);
//        Timer.builder("mr.test.timer").tags("status", "2").publishPercentiles(0.9).register(meterRegistry).record(20, TimeUnit.SECONDS);
//
//        Timer.builder("mr.test.timer").tags("status", "1").publishPercentiles(0.9).publishPercentileHistogram().register(meterRegistry).record(10, TimeUnit.SECONDS);
//        Timer.builder("mr.test.timer").tags("status", "1").publishPercentiles(0.9).publishPercentileHistogram().register(meterRegistry).record(11, TimeUnit.SECONDS);
//        Timer.builder("mr.test.timer").tags("status", "2").publishPercentiles(0.9).publishPercentileHistogram().register(meterRegistry).record(20, TimeUnit.SECONDS);
//
//        LongTaskTimer.builder("mr.test.ltt").tags("status", "1").publishPercentileHistogram().register(meterRegistry).start();
//        Thread.sleep(300);
//        LongTaskTimer.builder("mr.test.ltt").tags("status", "1").publishPercentileHistogram().register(meterRegistry).start();
//        Thread.sleep(200);
//        LongTaskTimer.builder("mr.test.ltt").tags("status", "2").publishPercentileHistogram().register(meterRegistry).start();
//        Thread.sleep(100);

        System.out.println(meterRegistry.scrape("application/openmetrics-text"));

//        PrometheusRegistry prometheusRegistry = new PrometheusRegistry();
//        SpanContextSupplier.setSpanContext(new CustomSpanContext());
//        io.prometheus.metrics.core.metrics.Counter counter = io.prometheus.metrics.core.metrics.Counter.builder()
//            .name("new_test")
//            .help("new test counter")
//            .labelNames("status")
//            .register(prometheusRegistry);
//        counter.labelValues("ok").inc();
//        counter.labelValues("fail").inc();
//
//        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
//        new OpenMetricsTextFormatWriter(false, true).write(outputStream, prometheusRegistry.scrape());
//        System.out.println(outputStream);
    }

    static class CustomSpanContext implements SpanContext {

        AtomicInteger counter = new AtomicInteger();

        @Override
        public String getCurrentTraceId() {
            return String.valueOf(this.counter.incrementAndGet());
        }

        @Override
        public String getCurrentSpanId() {
            return "test";
        }

        @Override
        public boolean isCurrentSpanSampled() {
            return true;
        }

        @Override
        public void markCurrentSpanAsExemplar() {
            System.out.println("marked");
        }
    }
}
