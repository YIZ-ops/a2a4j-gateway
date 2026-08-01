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
import java.util.List;

/** Immutable runtime instance of a logical Agent. */
public record AgentInstance(String instanceId, String cardUrl, List<AgentInterface> interfaces, int weight,
        String credentialRef, HealthStatus healthStatus, String lastCardHash, Instant lastCheckedAt) {

    /** Runtime health states understood by routing policy. */
    public enum HealthStatus {
        /** Instance can receive new work. */
        HEALTHY,
        /** Instance is serving existing work but should not receive new work. */
        DEGRADED,
        /** Instance must not receive new work. */
        UNHEALTHY,
        /** No health result has been recorded yet. */
        UNKNOWN
    }

    /** Creates a validated immutable Agent instance. */
    public AgentInstance {
        requireText(instanceId, "instanceId");
        requireText(cardUrl, "cardUrl");
        interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
        if (weight < 1) {
            throw new IllegalArgumentException("weight must be positive");
        }
        healthStatus = healthStatus == null ? HealthStatus.UNKNOWN : healthStatus;
    }

    /** Returns whether this instance is eligible for new work. */
    public boolean eligibleForNewWork() {
        return healthStatus == HealthStatus.HEALTHY;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
