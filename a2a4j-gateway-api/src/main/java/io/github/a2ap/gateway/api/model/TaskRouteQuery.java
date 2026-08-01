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

import java.util.Set;

/** Immutable query for task routes visible to one tenant. */
public record TaskRouteQuery(String tenantId, String gatewayTaskId, String agentId,
        Set<TaskRoute.State> states, int pageSize, String pageToken) {

    /** Creates a bounded immutable route query. */
    public TaskRouteQuery {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        states = states == null ? Set.of() : Set.copyOf(states);
        if (pageSize < 0 || pageSize > 1000) {
            throw new IllegalArgumentException("pageSize must be between 0 and 1000");
        }
    }

    /** Creates a query for one gateway task. */
    public static TaskRouteQuery forTask(String tenantId, String gatewayTaskId) {
        return new TaskRouteQuery(tenantId, gatewayTaskId, null, Set.of(), 1, null);
    }

}
