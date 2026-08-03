/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.a2ap.gateway.spring.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.a2ap.gateway.api.model.GatewayAuditEvent;
import io.github.a2ap.gateway.api.spi.GatewayAuditSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JSON structured audit logger that deliberately excludes request bodies and secrets. */
public final class Slf4jGatewayAuditSink implements GatewayAuditSink {

    private static final Logger LOGGER = LoggerFactory.getLogger("a2a.gateway.audit");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void record(GatewayAuditEvent event) {
        if (event == null) {
            return;
        }
        try {
            LOGGER.info("gateway.audit {}", objectMapper.writeValueAsString(event));
        }
        catch (Exception ignored) {
            LOGGER.info("gateway.audit operation={} outcome={} requestId={}", event.operation(), event.outcome(),
                    event.requestId());
        }
    }

}
