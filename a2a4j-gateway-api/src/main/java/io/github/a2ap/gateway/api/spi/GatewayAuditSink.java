/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.a2ap.gateway.api.spi;

import io.github.a2ap.gateway.api.model.GatewayAuditEvent;

/** Replaceable structured audit sink. Implementations must never persist payloads or secrets. */
@FunctionalInterface
public interface GatewayAuditSink {

    void record(GatewayAuditEvent event);

    static GatewayAuditSink noop() {
        return event -> { };
    }

}
