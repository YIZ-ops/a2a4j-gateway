/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.a2ap.gateway.spring;

import io.github.a2ap.gateway.core.discovery.InMemoryAgentRegistry;
import java.util.Map;
import io.github.a2ap.gateway.api.model.AgentInstance;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/** Readiness-oriented dependency health derived from the discovered Agent population. */
public final class GatewayDependencyHealthIndicator implements HealthIndicator {

    private final InMemoryAgentRegistry registry;

    public GatewayDependencyHealthIndicator(InMemoryAgentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        Map<String, AgentInstance.HealthStatus> summary = registry.healthSummary();
        int healthy = (int) summary.values().stream().filter(status -> status == AgentInstance.HealthStatus.HEALTHY)
                .count();
        int degraded = (int) summary.values().stream().filter(status -> status == AgentInstance.HealthStatus.DEGRADED)
                .count();
        int total = summary.size();
        if (total == 0) {
            return Health.unknown().withDetail("agents", 0).build();
        }
        if (healthy > 0) {
            return Health.up().withDetails(Map.of("agents", total, "healthy", healthy, "degraded", degraded)).build();
        }
        return Health.outOfService().withDetails(Map.of("agents", total, "healthy", healthy, "degraded", degraded))
                .build();
    }

}
