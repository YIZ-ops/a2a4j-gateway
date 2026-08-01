/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.a2ap.gateway.api.spi;

import io.github.a2ap.gateway.api.model.GatewayMetricEvent;

/** Replaceable metrics sink; implementations must keep tags low-cardinality. */
@FunctionalInterface
public interface GatewayMetrics {

    void record(GatewayMetricEvent event);

    static GatewayMetrics noop() {
        return event -> { };
    }

}
