/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.a2ap.gateway.api.model;

import java.time.Instant;
import java.util.Map;

/** Structured security/audit record. Payloads, credentials and request bodies are intentionally absent. */
public record GatewayAuditEvent(Instant occurredAt, String requestId, String traceId, String tenantId,
        String principalId, String operation, String agentId, String skillId, String policyDecision,
        String outcome, String gatewayTaskId, String latencyBucket, String errorCode,
        Map<String, String> metadata) {

    public GatewayAuditEvent {
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        requestId = textOrEmpty(requestId);
        traceId = textOrEmpty(traceId);
        tenantId = textOrEmpty(tenantId);
        principalId = textOrEmpty(principalId);
        operation = textOrEmpty(operation);
        agentId = textOrEmpty(agentId);
        skillId = textOrEmpty(skillId);
        policyDecision = textOrEmpty(policyDecision);
        outcome = textOrEmpty(outcome);
        gatewayTaskId = textOrEmpty(gatewayTaskId);
        latencyBucket = textOrEmpty(latencyBucket);
        errorCode = textOrEmpty(errorCode);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static GatewayAuditEvent access(Instant occurredAt, String requestId, String traceId,
            String operation, String outcome, String latencyBucket, String errorCode) {
        return new GatewayAuditEvent(occurredAt, requestId, traceId, "", "", operation, "", "", "", outcome,
                "", latencyBucket, errorCode, Map.of());
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }

}
