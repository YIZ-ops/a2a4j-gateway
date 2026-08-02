/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.a2ap.gateway.api.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable mapping between a gateway task and its selected upstream instance. */
public record TaskRoute(String tenantId, String gatewayTaskId, String gatewayContextId, String agentId,
        String instanceId, String interfaceKey, String upstreamTaskId, String upstreamContextId,
        String protocolBinding, String protocolVersion, String principalFingerprint, String idempotencyKey,
        State state, Instant createdAt, Instant updatedAt, Instant expiresAt, Map<String, Object> taskSnapshot,
        Instant statusTimestamp) {

    /** Preserves the original route constructor for SPI implementations. */
    public TaskRoute(String tenantId, String gatewayTaskId, String gatewayContextId, String agentId,
            String instanceId, String interfaceKey, String upstreamTaskId, String upstreamContextId,
            String protocolBinding, String protocolVersion, String principalFingerprint, String idempotencyKey,
            State state, Instant createdAt, Instant updatedAt, Instant expiresAt) {
        this(tenantId, gatewayTaskId, gatewayContextId, agentId, instanceId, interfaceKey, upstreamTaskId,
                upstreamContextId, protocolBinding, protocolVersion, principalFingerprint, idempotencyKey, state,
                createdAt, updatedAt, expiresAt, Map.of(), updatedAt);
    }

    /** Preserves the snapshot-aware route constructor for SPI implementations. */
    public TaskRoute(String tenantId, String gatewayTaskId, String gatewayContextId, String agentId,
            String instanceId, String interfaceKey, String upstreamTaskId, String upstreamContextId,
            String protocolBinding, String protocolVersion, String principalFingerprint, String idempotencyKey,
            State state, Instant createdAt, Instant updatedAt, Instant expiresAt, Map<String, Object> taskSnapshot) {
        this(tenantId, gatewayTaskId, gatewayContextId, agentId, instanceId, interfaceKey, upstreamTaskId,
                upstreamContextId, protocolBinding, protocolVersion, principalFingerprint, idempotencyKey, state,
                createdAt, updatedAt, expiresAt, taskSnapshot, updatedAt);
    }

    /** Lifecycle states persisted by a task route store. */
    public enum State {
        /** Route is being created. */
        PENDING,
        /** Upstream task is active. */
        ACTIVE,
        /** Upstream task completed successfully. */
        COMPLETED,
        /** Upstream task failed. */
        FAILED,
        /** Upstream task was canceled. */
        CANCELED,
        /** Upstream Agent requires more user input. */
        INPUT_REQUIRED,
        /** Upstream Agent requires authentication. */
        AUTH_REQUIRED,
        /** Upstream Agent rejected the task. */
        REJECTED,
        /** Upstream outcome could not be determined. */
        OUTCOME_UNKNOWN
    }

    /** Creates a validated immutable task route. */
    public TaskRoute {
        requireText(tenantId, "tenantId");
        requireText(gatewayTaskId, "gatewayTaskId");
        requireText(agentId, "agentId");
        requireText(instanceId, "instanceId");
        requireText(interfaceKey, "interfaceKey");
        requireText(protocolBinding, "protocolBinding");
        requireText(protocolVersion, "protocolVersion");
        requireText(principalFingerprint, "principalFingerprint");
        state = state == null ? State.PENDING : state;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        statusTimestamp = statusTimestamp == null ? updatedAt : statusTimestamp;
        taskSnapshot = taskSnapshot == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(taskSnapshot));
        if (expiresAt != null && expiresAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("expiresAt must not be before createdAt");
        }
    }

    /** Returns whether this route is terminal. */
    public boolean terminal() {
        return state == State.COMPLETED || state == State.FAILED || state == State.CANCELED
                || state == State.REJECTED;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
