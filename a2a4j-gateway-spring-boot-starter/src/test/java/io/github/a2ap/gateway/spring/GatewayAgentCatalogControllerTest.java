/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.a2ap.gateway.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.a2ap.core.protocol.v1.A2AProtocolV1Validator;
import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.AgentSkillDefinition;
import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.api.model.ProtocolPolicy;
import io.github.a2ap.gateway.core.discovery.InMemoryAgentRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Web boundary contract tests for tenant-scoped Agent discovery. */
class GatewayAgentCatalogControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void listsOnlyTheAuthenticatedTenantAndProjectsAValidCard() throws Exception {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(agent("tenant-a", "agent-a"));
        registry.replace(agent("tenant-b", "agent-b"));
        GatewayAgentCatalogController controller = new GatewayAgentCatalogController(registry, objectMapper);
        ServerWebExchange exchange = exchange("/gateway/v1/agents", "tenant-a");

        ResponseEntity<byte[]> list = controller.list(exchange).block();
        JsonNode listBody = objectMapper.readTree(list.getBody());
        assertEquals(1, listBody.path("agents").size());
        assertEquals("agent-a", listBody.path("agents").get(0).path("agentId").asText());

        ResponseEntity<byte[]> card = controller.card("agent-a", exchange("/gateway/v1/agents/agent-a/card", "tenant-a"))
                .block();
        JsonNode cardBody = objectMapper.readTree(card.getBody());
        A2AProtocolV1Validator.validateAgentCard(cardBody);
        assertEquals("agent-a", cardBody.path("name").asText());
        assertTrue(cardBody.path("supportedInterfaces").size() > 0);
    }

    @Test
    void hidesAnAgentFromAnotherTenant() {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(agent("tenant-b", "agent-b"));
        GatewayAgentCatalogController controller = new GatewayAgentCatalogController(registry, objectMapper);

        GatewayHttpException error = assertThrows(GatewayHttpException.class,
                () -> controller.get("agent-b", exchange("/gateway/v1/agents/agent-b", "tenant-a")).block());
        assertEquals(404, error.status());
    }

    private ServerWebExchange exchange(String path, String tenantId) {
        PrincipalContext principal = new PrincipalContext(tenantId, "catalog-user", Set.of("*"), Map.of(),
                "catalog-fingerprint");
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build()).mutate()
                .principal(Mono.just(new GatewayAuthenticationToken(principal))).build();
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
