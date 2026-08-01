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

import java.util.List;
import java.util.Map;

/** Immutable configuration fact for one logical Agent and its card endpoints. */
public record AgentRegistration(String tenantId, String agentId, String displayName, boolean enabled,
        Map<String, String> routingLabels, ProtocolPolicy protocolPolicy,
        List<AgentInstanceRegistration> instances) {

    /** Creates a validated immutable registration. */
    public AgentRegistration {
        requireText(tenantId, "tenantId");
        requireText(agentId, "agentId");
        routingLabels = routingLabels == null ? Map.of() : Map.copyOf(routingLabels);
        protocolPolicy = protocolPolicy == null ? ProtocolPolicy.a2aV1Mvp() : protocolPolicy;
        instances = instances == null ? List.of() : List.copyOf(instances);
        if (instances.isEmpty()) {
            throw new IllegalArgumentException("instances must not be empty");
        }
    }

    /** Returns the configured display name, or the card name when absent. */
    public String configuredDisplayName() {
        return displayName == null ? "" : displayName.trim();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
