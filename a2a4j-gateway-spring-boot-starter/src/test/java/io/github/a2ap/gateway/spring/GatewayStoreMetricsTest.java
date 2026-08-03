/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.a2ap.gateway.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.a2ap.gateway.core.store.InMemoryIdempotencyStore;
import io.github.a2ap.gateway.core.store.InMemoryTaskRouteStore;
import io.github.a2ap.gateway.spring.metrics.GatewayStoreMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class GatewayStoreMetricsTest {

    @Test
    void registersOccupancyGaugesForDefaultStores() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new GatewayStoreMetrics(registry, new InMemoryTaskRouteStore(2), new InMemoryIdempotencyStore(2));

        assertEquals(0.0, registry.get("gateway.store.entries").tag("store", "task-route").gauge().value());
        assertEquals(0.0, registry.get("gateway.store.entries").tag("store", "idempotency").gauge().value());
        assertEquals(2.0, registry.get("gateway.store.capacity").tag("store", "task-route").gauge().value());
        assertEquals(2.0, registry.get("gateway.store.capacity").tag("store", "idempotency").gauge().value());
        assertEquals(0.0, registry.get("gateway.store.evictions").tag("store", "task-route")
                .functionCounter().count());
        assertEquals(0.0, registry.get("gateway.store.expired").tag("store", "idempotency")
                .functionCounter().count());
    }

}
