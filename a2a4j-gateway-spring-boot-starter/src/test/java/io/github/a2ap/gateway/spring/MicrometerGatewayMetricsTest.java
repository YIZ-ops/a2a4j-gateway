/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.a2ap.gateway.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.a2ap.gateway.api.model.GatewayMetricEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MicrometerGatewayMetricsTest {

    @Test
    void recordsCountersTimersAndGaugesWithStableTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerGatewayMetrics metrics = new MicrometerGatewayMetrics(registry);
        Map<String, String> tags = Map.of("operation", "SEND_MESSAGE", "status", "SUCCESS");

        metrics.record(GatewayMetricEvent.counter("gateway.requests.total", tags));
        metrics.record(GatewayMetricEvent.timer("gateway.request.duration", 12, tags));
        metrics.record(GatewayMetricEvent.gauge("gateway.task.routes", 3, tags));

        assertEquals(1, registry.get("gateway.requests.total").counter().count());
        assertEquals(1, registry.get("gateway.request.duration").timer().count());
        assertEquals(3, registry.get("gateway.task.routes").gauge().value());
    }

}
