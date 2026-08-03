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

package io.github.a2ap.gateway.spring.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentSkillDefinition;
import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.api.model.TargetHint;
import io.github.a2ap.gateway.api.spi.AgentRegistry;
import io.github.a2ap.gateway.spring.autoconfigure.GatewaySecurityProperties;
import io.github.a2ap.gateway.spring.exception.GatewayHttpException;
import io.github.a2ap.gateway.spring.security.GatewayAuthenticationToken;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Tenant-scoped read-only Agent catalog and public Card projection. */
@RestController
public final class GatewayAgentCatalogController {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private final AgentRegistry registry;

    private final ObjectMapper objectMapper;

    private final GatewaySecurityProperties securityProperties;

    /** Creates the catalog controller. */
    public GatewayAgentCatalogController(AgentRegistry registry, ObjectMapper objectMapper) {
        this(registry, objectMapper, null);
    }

    /** Creates the catalog controller with optional gateway authentication metadata. */
    public GatewayAgentCatalogController(AgentRegistry registry, ObjectMapper objectMapper,
            GatewaySecurityProperties securityProperties) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.securityProperties = securityProperties;
    }

    /** Lists logical Agents visible to the authenticated tenant. */
    @GetMapping(value = { "/gateway/v1/agents", "/agents" }, produces = "application/json")
    public Mono<ResponseEntity<byte[]>> list(ServerWebExchange exchange) {
        return principal(exchange).flatMap(principal -> registry.list(principal.tenantId(), TargetHint.empty())
                .map(this::summary)
                .collectList()
                .flatMap(agents -> write(Map.of("agents", agents))));
    }

    /** Returns one tenant-scoped logical Agent definition. */
    @GetMapping(value = { "/gateway/v1/agents/{agentId}", "/agents/{agentId}" }, produces = "application/json")
    public Mono<ResponseEntity<byte[]>> get(@PathVariable String agentId, ServerWebExchange exchange) {
        return principal(exchange).flatMap(principal -> registry.get(principal.tenantId(), agentId)
                .switchIfEmpty(Mono.error(new GatewayHttpException(404, "GATEWAY_ROUTE_NOT_FOUND",
                        "Agent was not found")))
                .flatMap(agent -> write(summary(agent))));
    }

    /** Returns a synthesized A2A 1.0 Card from the normalized gateway snapshot. */
    @GetMapping(value = { "/gateway/v1/agents/{agentId}/card", "/agents/{agentId}/card" },
            produces = { "application/json", "application/a2a+json" })
    public Mono<ResponseEntity<byte[]>> card(@PathVariable String agentId, ServerWebExchange exchange) {
        return principal(exchange).flatMap(principal -> registry.get(principal.tenantId(), agentId)
                .switchIfEmpty(Mono.error(new GatewayHttpException(404, "GATEWAY_ROUTE_NOT_FOUND",
                        "Agent was not found")))
                .flatMap(agent -> write(card(agent, exchange))));
    }

    /** Standard public discovery entry point; an unqualified request uses the deterministic default Card. */
    @GetMapping(value = "/.well-known/agent-card.json",
            produces = { "application/json", "application/a2a+json" })
    public Mono<ResponseEntity<byte[]>> wellKnown(@RequestParam(required = false) String agentId,
            @RequestParam(required = false) String tenantId, ServerWebExchange exchange) {
        return discover(agentId, tenantId, exchange);
    }

    /** Preserves the direct-call shape used by embedders of the controller. */
    public Mono<ResponseEntity<byte[]>> wellKnown(ServerWebExchange exchange) {
        return discover(null, null, exchange);
    }

    /** Agent-specific standard discovery path for gateways hosting multiple logical Agents. */
    @GetMapping(value = "/.well-known/agents/{agentId}/agent-card.json",
            produces = { "application/json", "application/a2a+json" })
    public Mono<ResponseEntity<byte[]>> wellKnownAgent(@PathVariable String agentId,
            @RequestParam(required = false) String tenantId, ServerWebExchange exchange) {
        return discover(agentId, tenantId, exchange);
    }

    /** Projects an extended-card response through the same gateway-owned Card view. */
    public Mono<Object> projectExtendedCard(String agentId, ServerWebExchange exchange) {
        return principal(exchange).flatMap(principal -> {
            Mono<AgentDefinition> selected = agentId == null || agentId.isBlank()
                    ? registry.list(principal.tenantId(), TargetHint.empty()).next()
                    : registry.get(principal.tenantId(), agentId);
            return selected.switchIfEmpty(Mono.error(new GatewayHttpException(404, "GATEWAY_ROUTE_NOT_FOUND",
                    "Agent was not found"))).map(agent -> card(agent, exchange));
        });
    }

    /** Projects the actual upstream extended Card while retaining its extended fields. */
    public Mono<Object> projectExtendedCard(String agentId, ServerWebExchange exchange, Object upstreamPayload) {
        return principal(exchange).flatMap(principal -> {
            Mono<AgentDefinition> selected = agentId == null || agentId.isBlank()
                    ? registry.list(principal.tenantId(), TargetHint.empty()).next()
                    : registry.get(principal.tenantId(), agentId);
            return selected.switchIfEmpty(Mono.error(new GatewayHttpException(404, "GATEWAY_ROUTE_NOT_FOUND",
                    "Agent was not found"))).map(agent -> projectExtendedPayload(agent, exchange, upstreamPayload));
        });
    }

    private Mono<ResponseEntity<byte[]>> discover(String agentId, String tenantId, ServerWebExchange exchange) {
        return registry.listAll().collectList().map(agents -> agents.stream()
                        .filter(agent -> tenantId == null || tenantId.equals(agent.tenantId()))
                        .filter(agent -> agentId == null || agentId.equals(agent.agentId()))
                        .sorted(java.util.Comparator.comparing(AgentDefinition::tenantId)
                                .thenComparing(AgentDefinition::agentId)).toList())
                .flatMap(agents -> {
                    if (agents.isEmpty()) {
                        return Mono.error(new GatewayHttpException(404, "GATEWAY_ROUTE_NOT_FOUND",
                                "no matching Agent Card was found"));
                    }
                    if (agents.size() > 1 && (agentId != null || tenantId != null)) {
                        return Mono.error(new GatewayHttpException(400, "AGENT_ID_REQUIRED",
                                "agentId and tenantId are required when discovery matches multiple Agents"));
                    }
                    return write(card(agents.get(0), exchange));
                });
    }

    private Mono<ResponseEntity<byte[]>> write(Object value) {
        try {
            return Mono.just(ResponseEntity.ok().contentType(JSON)
                    .body(objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        }
        catch (JsonProcessingException ex) {
            return Mono.error(new GatewayHttpException(500, "INTERNAL", "could not serialize Agent response"));
        }
    }

    private Map<String, Object> summary(AgentDefinition agent) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenantId", agent.tenantId());
        response.put("agentId", agent.agentId());
        response.put("displayName", agent.displayName());
        response.put("enabled", agent.enabled());
        response.put("skills", agent.skills().stream().map(this::skill).toList());
        response.put("routingLabels", agent.routingLabels());
        response.put("protocolPolicy", Map.of("protocolVersions", agent.protocolPolicy().protocolVersions(),
                "protocolBindings", agent.protocolPolicy().protocolBindings()));
        response.put("instances", agent.instances().stream().map(this::instance).toList());
        return response;
    }

    private Map<String, Object> card(AgentDefinition agent, ServerWebExchange exchange) {
        List<Map<String, Object>> interfaces = new ArrayList<>();
        boolean jsonRpc = agent.instances().stream().flatMap(instance -> instance.interfaces().stream())
                .anyMatch(agentInterface -> "JSONRPC".equals(agentInterface.protocolBinding()));
        boolean httpJson = agent.instances().stream().flatMap(instance -> instance.interfaces().stream())
                .anyMatch(agentInterface -> "HTTP+JSON".equals(agentInterface.protocolBinding()));
        if (jsonRpc) {
            interfaces.add(Map.of("url", gatewayUrl(exchange, "/gateway/v1/agents/"
                    + pathSegment(agent.agentId()) + "/a2a"),
                    "protocolBinding", "JSONRPC", "protocolVersion", "1.0"));
        }
        if (httpJson) {
            interfaces.add(Map.of("url", gatewayUrl(exchange, "/gateway/v1/agents/"
                    + pathSegment(agent.agentId())),
                    "protocolBinding", "HTTP+JSON", "protocolVersion", "1.0"));
        }
        List<String> inputModes = agent.skills().stream().flatMap(skill -> skill.inputModes().stream()).distinct().toList();
        List<String> outputModes = agent.skills().stream().flatMap(skill -> skill.outputModes().stream()).distinct().toList();
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", agent.agentId());
        card.put("description", agent.displayName() + " (Gateway projection)");
        card.put("version", "1.0.0");
        card.put("supportedInterfaces", interfaces);
        Map<String, Object> upstreamCapabilities = cardMap(agent.cardMetadata().get("capabilities"));
        Map<String, Object> capabilities = new LinkedHashMap<>(upstreamCapabilities);
        capabilities.put("streaming", Boolean.TRUE.equals(upstreamCapabilities.get("streaming")));
        capabilities.put("pushNotifications", Boolean.TRUE.equals(upstreamCapabilities.get("pushNotifications")));
        capabilities.put("extendedAgentCard", Boolean.TRUE.equals(upstreamCapabilities.get("extendedAgentCard")));
        Object legacyExtensions = agent.cardMetadata().get("extensions");
        if (!capabilities.containsKey("extensions") && legacyExtensions != null) {
            capabilities.put("extensions", legacyExtensions);
        }
        card.put("capabilities", capabilities);
        card.put("defaultInputModes", inputModes.isEmpty() ? List.of("text/plain") : inputModes);
        card.put("defaultOutputModes", outputModes.isEmpty() ? List.of("text/plain") : outputModes);
        card.put("skills", agent.skills().stream().map(this::skill).toList());
        projectGatewaySecurity(card);
        return card;
    }

    private String projectExtendedPayload(AgentDefinition agent, ServerWebExchange exchange, Object upstreamPayload) {
        try {
            JsonNode root = upstreamPayload instanceof String value ? objectMapper.readTree(value)
                    : objectMapper.valueToTree(upstreamPayload);
            if (root == null || !root.isObject()) {
                return upstreamPayload instanceof String ? (String) upstreamPayload
                        : objectMapper.writeValueAsString(upstreamPayload);
            }
            ObjectNode envelope = (ObjectNode) root.deepCopy();
            JsonNode cardNode = envelope.has("result") ? envelope.get("result") : envelope;
            if (cardNode == null || !cardNode.isObject()) {
                return objectMapper.writeValueAsString(envelope);
            }
            ObjectNode card = (ObjectNode) cardNode.deepCopy();
            card.remove(List.of("signatures", "securitySchemes", "securityRequirements"));
            JsonNode interfaces = card.get("supportedInterfaces");
            if (interfaces != null && interfaces.isArray()) {
                for (JsonNode item : interfaces) {
                    if (item instanceof ObjectNode interfaceNode) {
                        String binding = interfaceNode.path("protocolBinding").asText();
                        String path = "JSONRPC".equals(binding) ? "/gateway/v1/agents/"
                                + pathSegment(agent.agentId()) + "/a2a" : "/gateway/v1/agents/"
                                        + pathSegment(agent.agentId());
                        interfaceNode.put("url", gatewayUrl(exchange, path));
                    }
                }
            }
            Map<String, Object> projected = objectMapper.convertValue(card, Map.class);
            projectGatewaySecurity(projected);
            if (envelope.has("result")) {
                envelope.set("result", objectMapper.valueToTree(projected));
                return objectMapper.writeValueAsString(envelope);
            }
            return objectMapper.writeValueAsString(projected);
        }
        catch (JsonProcessingException ex) {
            throw new GatewayHttpException(502, "GATEWAY_SERIALIZATION_ERROR",
                    "could not project upstream extended Agent Card");
        }
    }

    private void projectGatewaySecurity(Map<String, Object> card) {
        if (securityProperties == null || !securityProperties.isEnabled()) {
            return;
        }
        Map<String, Object> schemes = new LinkedHashMap<>();
        String mode = securityProperties.getMode() == null ? "" : securityProperties.getMode().trim().toLowerCase();
        String schemeName;
        if ("api-key".equals(mode)) {
            schemeName = "gatewayApiKey";
            Map<String, Object> apiKey = new LinkedHashMap<>();
            apiKey.put("name", securityProperties.getApiKey().getHeaderName());
            apiKey.put("location", "header");
            schemes.put(schemeName, Map.of("apiKeySecurityScheme", apiKey));
        }
        else {
            schemeName = "gatewayJwt";
            schemes.put(schemeName, Map.of("httpAuthSecurityScheme", Map.of("scheme", "Bearer",
                    "bearerFormat", "JWT")));
        }
        card.put("securitySchemes", schemes);
        card.put("securityRequirements", List.of(Map.of("schemes", Map.of(schemeName,
                Map.of("list", List.of())))));
    }

    private Map<String, Object> cardMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private String gatewayUrl(ServerWebExchange exchange, String path) {
        java.net.URI uri = exchange.getRequest().getURI();
        String authority = uri.getRawAuthority();
        if (uri.getScheme() == null || authority == null) {
            return path;
        }
        return uri.getScheme() + "://" + authority + path;
    }

    private String pathSegment(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Map<String, Object> skill(AgentSkillDefinition skill) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", skill.skillId());
        value.put("name", skill.name());
        value.put("description", skill.description());
        value.put("tags", skill.tags());
        value.put("inputModes", skill.inputModes());
        value.put("outputModes", skill.outputModes());
        return value;
    }

    private Map<String, Object> instance(AgentInstance instance) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("instanceId", instance.instanceId());
        value.put("cardUrl", instance.cardUrl());
        value.put("healthStatus", instance.healthStatus().name());
        value.put("weight", instance.weight());
        value.put("interfaces", instance.interfaces().stream().map(agentInterface -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("interfaceKey", agentInterface.interfaceKey());
            item.put("endpointUrl", agentInterface.endpointUrl());
            item.put("protocolBinding", agentInterface.protocolBinding());
            item.put("protocolVersion", agentInterface.protocolVersion());
            if (agentInterface.upstreamTenant() != null) {
                item.put("tenant", agentInterface.upstreamTenant());
            }
            return item;
        }).toList());
        if (instance.lastCheckedAt() != null) {
            value.put("lastCheckedAt", instance.lastCheckedAt());
        }
        return value;
    }

    private Mono<PrincipalContext> principal(ServerWebExchange exchange) {
        return exchange.getPrincipal().flatMap(value -> value instanceof GatewayAuthenticationToken token
                ? Mono.just(token.principalContext()) : Mono.<PrincipalContext>empty())
                .switchIfEmpty(ReactiveSecurityContextHolder.getContext().map(context -> context.getAuthentication())
                        .filter(GatewayAuthenticationToken.class::isInstance)
                        .map(GatewayAuthenticationToken.class::cast).map(GatewayAuthenticationToken::principalContext))
                .switchIfEmpty(Mono.error(new GatewayHttpException(401, "UNAUTHENTICATED",
                        "authenticated principal is required")));
    }

}
