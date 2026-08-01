/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.a2ap.gateway.api.model;

import java.util.Map;

/** Low-cardinality metric event emitted by the gateway data plane. */
public record GatewayMetricEvent(String name, Kind kind, double value, Map<String, String> tags) {

    /** Metric instrument semantic. */
    public enum Kind {
        COUNTER, TIMER, GAUGE
    }

    public GatewayMetricEvent {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        kind = kind == null ? Kind.COUNTER : kind;
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("value must be finite and non-negative");
        }
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    public static GatewayMetricEvent counter(String name, Map<String, String> tags) {
        return new GatewayMetricEvent(name, Kind.COUNTER, 1, tags);
    }

    public static GatewayMetricEvent timer(String name, long millis, Map<String, String> tags) {
        return new GatewayMetricEvent(name, Kind.TIMER, Math.max(0, millis), tags);
    }

    public static GatewayMetricEvent gauge(String name, double value, Map<String, String> tags) {
        return new GatewayMetricEvent(name, Kind.GAUGE, value, tags);
    }

}
