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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.AgentSkillDefinition;
import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.api.model.TargetHint;
import io.github.a2ap.gateway.api.spi.AgentRegistry;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Tenant-scoped read-only Agent catalog and public Card projection. */
@RestController
public final class GatewayAgentCatalogController {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private final AgentRegistry registry;

    private final ObjectMapper objectMapper;

    /** Creates the catalog controller. */
    public GatewayAgentCatalogController(AgentRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
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
                .flatMap(agent -> write(card(agent))));
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

    private Map<String, Object> card(AgentDefinition agent) {
        List<Map<String, Object>> interfaces = new ArrayList<>();
        for (AgentInstance instance : agent.instances()) {
            for (AgentInterface agentInterface : instance.interfaces()) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("url", agentInterface.endpointUrl());
                value.put("protocolBinding", agentInterface.protocolBinding());
                value.put("protocolVersion", agentInterface.protocolVersion());
                interfaces.add(value);
            }
        }
        List<String> inputModes = agent.skills().stream().flatMap(skill -> skill.inputModes().stream()).distinct().toList();
        List<String> outputModes = agent.skills().stream().flatMap(skill -> skill.outputModes().stream()).distinct().toList();
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", agent.agentId());
        card.put("description", agent.displayName() + " (Gateway projection)");
        card.put("version", "1.0.0");
        card.put("supportedInterfaces", interfaces);
        card.put("capabilities", Map.of("streaming", interfaces.stream()
                .anyMatch(value -> "JSONRPC".equals(value.get("protocolBinding"))
                        || "HTTP+JSON".equals(value.get("protocolBinding"))), "pushNotifications", false,
                "extendedAgentCard", false));
        card.put("defaultInputModes", inputModes.isEmpty() ? List.of("text/plain") : inputModes);
        card.put("defaultOutputModes", outputModes.isEmpty() ? List.of("text/plain") : outputModes);
        card.put("skills", agent.skills().stream().map(this::skill).toList());
        return card;
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
