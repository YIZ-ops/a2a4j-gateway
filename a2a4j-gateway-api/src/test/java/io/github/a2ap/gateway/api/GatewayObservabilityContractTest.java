/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.a2ap.gateway.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.a2ap.gateway.api.model.GatewayMetricEvent;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayObservabilityContractTest {

    @Test
    void parsesW3cTraceparentAndRejectsInvalidValues() {
        var trace = GatewayTraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertTrue(trace.isPresent());
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", trace.orElseThrow().traceId());
        assertTrue(GatewayTraceContext.parse("not-a-trace").isEmpty());
        assertTrue(GatewayTraceContext.parse("00-00000000000000000000000000000000-00f067aa0ba902b7-01")
                .isEmpty());
    }

    @Test
    void metricEventDefensivelyCopiesTags() {
        var event = GatewayMetricEvent.counter("gateway.requests", Map.of("operation", "SEND_MESSAGE"));
        assertEquals("SEND_MESSAGE", event.tags().get("operation"));
    }

}
