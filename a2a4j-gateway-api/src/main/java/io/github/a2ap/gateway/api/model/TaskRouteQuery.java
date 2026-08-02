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
import java.util.Set;

/** Immutable query for task routes visible to one tenant. */
public record TaskRouteQuery(String tenantId, String gatewayTaskId, String agentId,
        Set<TaskRoute.State> states, int pageSize, String pageToken, String principalFingerprint,
        String gatewayContextId, Instant statusTimestampAfter) {

    /** Preserves the original tenant-scoped query shape for SPI callers. */
    public TaskRouteQuery(String tenantId, String gatewayTaskId, String agentId,
            Set<TaskRoute.State> states, int pageSize, String pageToken) {
        this(tenantId, gatewayTaskId, agentId, states, pageSize, pageToken, null, null, null);
    }

    /** Creates a principal-scoped query without a context filter. */
    public TaskRouteQuery(String tenantId, String gatewayTaskId, String agentId,
            Set<TaskRoute.State> states, int pageSize, String pageToken, String principalFingerprint) {
        this(tenantId, gatewayTaskId, agentId, states, pageSize, pageToken, principalFingerprint, null, null);
    }

    /** Creates a principal- and context-scoped query. */
    public TaskRouteQuery(String tenantId, String gatewayTaskId, String agentId,
            Set<TaskRoute.State> states, int pageSize, String pageToken, String principalFingerprint,
            String gatewayContextId) {
        this(tenantId, gatewayTaskId, agentId, states, pageSize, pageToken, principalFingerprint,
                gatewayContextId, null);
    }

    /** Creates a bounded immutable route query. */
    public TaskRouteQuery {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        states = states == null ? Set.of() : Set.copyOf(states);
        if (pageSize < 0 || pageSize > 1000) {
            throw new IllegalArgumentException("pageSize must be between 0 and 1000");
        }
        if (principalFingerprint != null && principalFingerprint.isBlank()) {
            throw new IllegalArgumentException("principalFingerprint must not be blank when supplied");
        }
        if (gatewayContextId != null && gatewayContextId.isBlank()) {
            throw new IllegalArgumentException("gatewayContextId must not be blank when supplied");
        }
    }

    /** Creates a query for one gateway task. */
    public static TaskRouteQuery forTask(String tenantId, String gatewayTaskId) {
        return new TaskRouteQuery(tenantId, gatewayTaskId, null, Set.of(), 1, null);
    }

    /** Creates a task query restricted to the authenticated principal. */
    public static TaskRouteQuery forTask(String tenantId, String gatewayTaskId, String principalFingerprint) {
        return new TaskRouteQuery(tenantId, gatewayTaskId, null, Set.of(), 1, null, principalFingerprint);
    }

}
