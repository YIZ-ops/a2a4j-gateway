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
import java.util.Objects;

/** Immutable gateway-owned definition of a logical Agent. */
public record AgentDefinition(String tenantId, String agentId, String displayName, boolean enabled,
        List<AgentSkillDefinition> skills, Map<String, String> routingLabels, ProtocolPolicy protocolPolicy,
        List<AgentInstance> instances, Map<String, Object> cardMetadata) {

    /** Preserves the original normalized definition shape for gateway SPI callers. */
    public AgentDefinition(String tenantId, String agentId, String displayName, boolean enabled,
            List<AgentSkillDefinition> skills, Map<String, String> routingLabels, ProtocolPolicy protocolPolicy,
            List<AgentInstance> instances) {
        this(tenantId, agentId, displayName, enabled, skills, routingLabels, protocolPolicy, instances, Map.of());
    }

    /** Creates a validated immutable Agent definition. */
    public AgentDefinition {
        requireText(tenantId, "tenantId");
        requireText(agentId, "agentId");
        requireText(displayName, "displayName");
        skills = skills == null ? List.of() : List.copyOf(skills);
        routingLabels = routingLabels == null ? Map.of() : Map.copyOf(routingLabels);
        protocolPolicy = Objects.requireNonNull(protocolPolicy, "protocolPolicy");
        instances = instances == null ? List.of() : List.copyOf(instances);
        cardMetadata = cardMetadata == null ? Map.of() : Map.copyOf(cardMetadata);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
