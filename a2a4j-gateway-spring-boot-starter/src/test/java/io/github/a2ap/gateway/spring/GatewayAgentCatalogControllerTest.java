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
import io.github.a2ap.gateway.spring.autoconfigure.GatewaySecurityProperties;
import io.github.a2ap.gateway.spring.controller.GatewayAgentCatalogController;
import io.github.a2ap.gateway.spring.exception.GatewayHttpException;
import io.github.a2ap.gateway.spring.security.GatewayAuthenticationToken;
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

    @Test
    void removesUpstreamSignaturesAndProjectsGatewaySecurity() throws Exception {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        AgentDefinition original = agent("tenant-a", "agent-a");
        AgentDefinition withCardMetadata = new AgentDefinition(original.tenantId(), original.agentId(),
                original.displayName(), original.enabled(), original.skills(), original.routingLabels(),
                original.protocolPolicy(), original.instances(), Map.of("signatures", List.of(Map.of("alg", "RS256")),
                        "securityRequirements", List.of(Map.of("upstream", List.of())),
                        "capabilities", Map.of("streaming", true)));
        registry.replace(withCardMetadata);
        GatewaySecurityProperties security = new GatewaySecurityProperties();
        security.setEnabled(true);
        security.setMode("jwt");
        GatewayAgentCatalogController controller = new GatewayAgentCatalogController(registry, objectMapper, security);

        JsonNode card = objectMapper.readTree(controller.card("agent-a", exchange("/card", "tenant-a"))
                .block().getBody());
        assertTrue(card.path("signatures").isMissingNode());
        assertEquals("Bearer", card.at("/securitySchemes/gatewayJwt/httpAuthSecurityScheme/scheme").asText());
        assertEquals("JWT", card.at("/securitySchemes/gatewayJwt/httpAuthSecurityScheme/bearerFormat").asText());
        assertEquals(List.of(), objectMapper.convertValue(card.at("/securityRequirements/0/schemes/gatewayJwt/list"),
                List.class));
    }

    @Test
    void discoversEachCardWithoutAuthenticationUsingTheStandardAgentPath() throws Exception {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(agent("tenant-a", "agent-a"));
        registry.replace(agent("tenant-a", "agent-b"));
        GatewayAgentCatalogController controller = new GatewayAgentCatalogController(registry, objectMapper);

        JsonNode card = objectMapper.readTree(controller.wellKnownAgent("agent-b", "tenant-a",
                exchange("/.well-known/agents/agent-b/agent-card.json", "tenant-a")).block().getBody());
        assertEquals("agent-b", card.path("name").asText());
    }

    @Test
    void exposesTheStandardDiscoveryEntryPointWithoutAuthenticationWhenSelected() throws Exception {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(agent("tenant-a", "agent-a"));
        GatewayAgentCatalogController controller = new GatewayAgentCatalogController(registry, objectMapper);

        JsonNode card = objectMapper.readTree(controller.wellKnown(
                MockServerWebExchange.from(MockServerHttpRequest.get("/.well-known/agent-card.json")
                        .build())).block().getBody());
        assertEquals("agent-a", card.path("name").asText());
    }

    @Test
    void selectsTheDeterministicDefaultForUnqualifiedMultiAgentDiscovery() throws Exception {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(agent("tenant-b", "agent-z"));
        registry.replace(agent("tenant-a", "agent-a"));
        GatewayAgentCatalogController controller = new GatewayAgentCatalogController(registry, objectMapper);

        JsonNode card = objectMapper.readTree(controller.wellKnown(
                MockServerWebExchange.from(MockServerHttpRequest.get("/.well-known/agent-card.json")
                        .build())).block().getBody());
        assertEquals("agent-a", card.path("name").asText());
    }

    @Test
    void projectsTheActualExtendedCardWithoutDroppingItsExtendedFields() throws Exception {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(agent("tenant-a", "agent-a"));
        GatewaySecurityProperties security = new GatewaySecurityProperties();
        security.setEnabled(true);
        security.setMode("jwt");
        GatewayAgentCatalogController controller = new GatewayAgentCatalogController(registry, objectMapper, security);
        String upstream = "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"name\":\"extended\","
                + "\"provider\":{\"organization\":\"Research\"},"
                + "\"documentationUrl\":\"https://internal.example/docs\","
                + "\"capabilities\":{\"extensions\":[{\"uri\":\"https://example.test/ext/v1\"}]},"
                + "\"supportedInterfaces\":[{\"url\":\"https://internal.example/a2a\","
                + "\"protocolBinding\":\"JSONRPC\",\"protocolVersion\":\"1.0\"}],"
                + "\"signatures\":[{\"alg\":\"RS256\"}],"
                + "\"securityRequirements\":[{\"schemes\":{\"upstream\":{\"list\":[]}}}]}}";

        JsonNode projected = objectMapper.readTree((String) controller.projectExtendedCard("agent-a",
                exchange("/gateway/v1/a2a", "tenant-a"), upstream).block());
        assertEquals("Research", projected.at("/result/provider/organization").asText());
        assertEquals("https://example.test/ext/v1", projected.at("/result/capabilities/extensions/0/uri").asText());
        assertTrue(projected.at("/result/supportedInterfaces/0/url").asText().contains("/gateway/v1/agents/agent-a/a2a"));
        assertTrue(projected.at("/result/signatures").isMissingNode());
        assertEquals("Bearer", projected.at("/result/securitySchemes/gatewayJwt/httpAuthSecurityScheme/scheme")
                .asText());
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
