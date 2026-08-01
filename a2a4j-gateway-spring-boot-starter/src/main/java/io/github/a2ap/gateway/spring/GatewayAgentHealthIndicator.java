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

package io.github.a2ap.gateway.spring;

import io.github.a2ap.gateway.core.discovery.InMemoryAgentRegistry;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/** Actuator health view for Agent Card snapshots and probe status. */
public final class GatewayAgentHealthIndicator implements HealthIndicator {

    private final InMemoryAgentRegistry registry;

    /** Creates an indicator backed by the in-memory Agent snapshot. */
    public GatewayAgentHealthIndicator(InMemoryAgentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        Map<String, ?> details = registry.healthSummary();
        if (details.isEmpty()) {
            return Health.unknown().withDetail("agents", details).build();
        }
        boolean unhealthy = details.values().stream()
                .anyMatch(value -> value == io.github.a2ap.gateway.api.model.AgentInstance.HealthStatus.UNHEALTHY);
        boolean degraded = details.values().stream()
                .anyMatch(value -> value == io.github.a2ap.gateway.api.model.AgentInstance.HealthStatus.DEGRADED);
        Health.Builder builder = unhealthy ? Health.down() : degraded ? Health.outOfService() : Health.up();
        return builder.withDetail("agents", details).build();
    }

}
