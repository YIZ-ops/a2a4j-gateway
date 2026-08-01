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
import java.util.Map;

/** Immutable auditable result of deterministic route resolution. */
public record RouteDecision(String decisionId, String tenantId, String agentId,
        Map<String, String> matchedRules, Instant decidedAt) {

    /** Creates a validated immutable route decision. */
    public RouteDecision {
        requireText(decisionId, "decisionId");
        requireText(tenantId, "tenantId");
        requireText(agentId, "agentId");
        matchedRules = matchedRules == null ? Map.of() : Map.copyOf(matchedRules);
        decidedAt = decidedAt == null ? Instant.now() : decidedAt;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
