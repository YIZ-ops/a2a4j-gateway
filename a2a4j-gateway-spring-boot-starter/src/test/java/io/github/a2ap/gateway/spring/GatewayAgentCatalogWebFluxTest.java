/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.a2ap.gateway.spring;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.core.context.ReactiveSecurityContextHolder.withAuthentication;

import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.AgentSkillDefinition;
import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.api.model.ProtocolPolicy;
import io.github.a2ap.gateway.core.discovery.InMemoryAgentRegistry;
import io.github.a2ap.gateway.spring.controller.GatewayAgentCatalogController;
import io.github.a2ap.gateway.spring.error.GatewayHttpErrorHandler;
import io.github.a2ap.gateway.spring.security.GatewayAuthenticationToken;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class GatewayAgentCatalogWebFluxTest {

    @Test
    void mapsCatalogRoutesAndUsesTheSharedErrorEnvelope() {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(agent("tenant-a", "agent-a"));
        GatewayAgentCatalogController controller = new GatewayAgentCatalogController(registry,
                new com.fasterxml.jackson.databind.ObjectMapper());

        WebTestClient unauthenticated = WebTestClient.bindToController(controller)
                .controllerAdvice(new GatewayHttpErrorHandler()).build();
        unauthenticated.get().uri("/gateway/v1/agents").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.parseMediaType("application/a2a+json"))
                .expectBody().consumeWith(result -> assertTrue(new String(result.getResponseBody(), StandardCharsets.UTF_8)
                        .contains("UNAUTHENTICATED")));

        GatewayAuthenticationToken authentication = new GatewayAuthenticationToken(principal("tenant-a"));
        WebTestClient authenticated = WebTestClient.bindToController(controller)
                .controllerAdvice(new GatewayHttpErrorHandler())
                .webFilter((exchange, chain) -> chain.filter(exchange).contextWrite(withAuthentication(authentication)))
                .build();
        authenticated.get().uri("/gateway/v1/agents").exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody().consumeWith(result -> assertTrue(new String(result.getResponseBody(), StandardCharsets.UTF_8)
                        .contains("\"agentId\":\"agent-a\"")));
        authenticated.get().uri("/agents/agent-a/card").exchange()
                .expectStatus().isOk()
                .expectBody().consumeWith(result -> assertTrue(new String(result.getResponseBody(), StandardCharsets.UTF_8)
                        .contains("\"name\":\"agent-a\"")));
    }

    private PrincipalContext principal(String tenantId) {
        return new PrincipalContext(tenantId, "catalog-user", Set.of("*"), Map.of(), "catalog-fingerprint");
    }

    private AgentDefinition agent(String tenantId, String agentId) {
        AgentSkillDefinition skill = new AgentSkillDefinition("echo", "Echo", "Echo text", List.of("sample"),
                List.of("text/plain"), List.of("text/plain"));
        AgentInterface agentInterface = new AgentInterface("jsonrpc", "https://agent.example.test/a2a", "JSONRPC",
                "1.0", null);
        AgentInstance instance = new AgentInstance("instance-1", "https://agent.example.test/card",
                List.of(agentInterface), 1, null, AgentInstance.HealthStatus.HEALTHY, "hash", null);
        return new AgentDefinition(tenantId, agentId, "Agent " + agentId, true, List.of(skill), Map.of(),
                ProtocolPolicy.a2aV1Mvp(), List.of(instance));
    }

}
