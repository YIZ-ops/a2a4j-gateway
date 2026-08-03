/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.a2ap.gateway.spring.metrics;

import io.github.a2ap.gateway.api.model.GatewayMetricEvent;
import io.github.a2ap.gateway.api.spi.GatewayMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Micrometer bridge with fixed metric names and caller-supplied low-cardinality tags. */
public final class MicrometerGatewayMetrics implements GatewayMetrics {

    private final MeterRegistry registry;

    private final Map<String, AtomicReference<Double>> gauges = new ConcurrentHashMap<>();

    public MicrometerGatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void record(GatewayMetricEvent event) {
        if (event == null) {
            return;
        }
        String[] tags = event.tags().entrySet().stream()
                .flatMap(entry -> java.util.stream.Stream.of(entry.getKey(), entry.getValue()))
                .toArray(String[]::new);
        switch (event.kind()) {
            case COUNTER -> Counter.builder(event.name()).tags(tags).register(registry).increment(event.value());
            case TIMER -> Timer.builder(event.name()).tags(tags).register(registry)
                    .record(Duration.ofMillis((long) event.value()));
            case GAUGE -> {
                String key = event.name() + event.tags();
                AtomicReference<Double> value = gauges.computeIfAbsent(key, ignored -> {
                    AtomicReference<Double> created = new AtomicReference<>(event.value());
                    registry.gauge(event.name(), event.tags().entrySet().stream()
                            .map(entry -> Tag.of(entry.getKey(), entry.getValue())).toList(), created,
                            AtomicReference::get);
                    return created;
                });
                value.set(event.value());
            }
            default -> throw new IllegalStateException("unsupported metric kind: " + event.kind());
        }
    }

}
