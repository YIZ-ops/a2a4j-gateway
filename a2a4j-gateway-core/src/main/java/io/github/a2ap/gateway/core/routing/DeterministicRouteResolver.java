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

package io.github.a2ap.gateway.core.routing;

import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AuthorizationDecision;
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.RouteDecision;
import io.github.a2ap.gateway.api.model.RoutingContext;
import io.github.a2ap.gateway.api.model.TargetHint;
import io.github.a2ap.gateway.api.model.TaskRoute;
import io.github.a2ap.gateway.api.model.TaskRouteQuery;
import io.github.a2ap.gateway.api.spi.AgentRegistry;
import io.github.a2ap.gateway.api.spi.AuthorizationPolicy;
import io.github.a2ap.gateway.api.spi.RouteResolver;
import io.github.a2ap.gateway.api.spi.TaskRouteStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Resolves explicit Agent, Skill, label and tenant-default hints without guessing. */
public final class DeterministicRouteResolver implements RouteResolver {

    private final AgentRegistry registry;

    private final TaskRouteStore taskRouteStore;

    private final AuthorizationPolicy authorizationPolicy;

    private final Map<String, String> defaultAgentByTenant;

    /** Creates a resolver without task affinity support. */
    public DeterministicRouteResolver(AgentRegistry registry, AuthorizationPolicy authorizationPolicy,
            Map<String, String> defaultAgentByTenant) {
        this(registry, null, authorizationPolicy, defaultAgentByTenant);
    }

    /** Creates a resolver with optional task affinity and tenant defaults. */
    public DeterministicRouteResolver(AgentRegistry registry, TaskRouteStore taskRouteStore,
            AuthorizationPolicy authorizationPolicy, Map<String, String> defaultAgentByTenant) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.taskRouteStore = taskRouteStore;
        this.authorizationPolicy = Objects.requireNonNull(authorizationPolicy, "authorizationPolicy");
        this.defaultAgentByTenant = defaultAgentByTenant == null ? Map.of() : Map.copyOf(defaultAgentByTenant);
    }

    @Override
    public Mono<RouteDecision> resolve(GatewayCommand command, RoutingContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        if (context.expired()) {
            return Mono.error(new RouteResolutionException(RouteResolutionException.Code.DEADLINE_EXCEEDED,
                    "routing deadline has elapsed"));
        }
        if (!command.tenantId().equals(command.principal().tenantId())) {
            return Mono.error(new RouteResolutionException(RouteResolutionException.Code.AUTHORIZATION_DENIED,
                    "command tenant does not match principal tenant"));
        }
        if (command.gatewayTaskId() != null && !command.gatewayTaskId().isBlank()) {
            if (taskRouteStore == null) {
                return Mono.error(new RouteResolutionException(RouteResolutionException.Code.TASK_ROUTE_NOT_FOUND,
                        "task affinity store is not configured"));
            }
            return taskRouteStore.find(command.tenantId(), command.gatewayTaskId())
                    .switchIfEmpty(Mono.error(new RouteResolutionException(
                            RouteResolutionException.Code.TASK_ROUTE_NOT_FOUND, "gateway task route was not found")))
                    .flatMap(route -> resolveAffinity(command, context, route));
        }
        if (contextAffinityEligible(command)) {
            String agentId = command.targetHint().agentId();
            TaskRouteQuery query = new TaskRouteQuery(command.tenantId(), null, agentId, java.util.Set.of(), 1000,
                    null, command.principal().fingerprint(), command.gatewayContextId());
            return taskRouteStore.list(query)
                    .flatMap(page -> page.routes().stream().findFirst()
                            .map(route -> resolveContextAffinity(command, context, route))
                            .orElseGet(() -> resolveNew(command, context)));
        }
        return resolveNew(command, context);
    }

    private boolean contextAffinityEligible(GatewayCommand command) {
        return taskRouteStore != null && command.gatewayContextId() != null
                && !command.gatewayContextId().isBlank()
                && (command.operation() == GatewayCommand.Operation.SEND_MESSAGE
                        || command.operation() == GatewayCommand.Operation.SEND_STREAMING_MESSAGE);
    }

    private Mono<RouteDecision> resolveContextAffinity(GatewayCommand command, RoutingContext context,
            TaskRoute route) {
        if (!command.tenantId().equals(route.tenantId())) {
            return Mono.error(new RouteResolutionException(RouteResolutionException.Code.AUTHORIZATION_DENIED,
                    "context route tenant does not match command tenant"));
        }
        if (!command.principal().fingerprint().equals(route.principalFingerprint())) {
            return Mono.error(new RouteResolutionException(RouteResolutionException.Code.AUTHORIZATION_DENIED,
                    "context route principal does not match command principal"));
        }
        return registry.get(command.tenantId(), route.agentId())
                .switchIfEmpty(Mono.error(new RouteResolutionException(RouteResolutionException.Code.AGENT_UNAVAILABLE,
                        "context route Agent is not available")))
                .flatMap(agent -> authorize(command, agent).map(ignored -> decision(command, context, agent,
                        Map.of("rule", "context-affinity", "contextId", command.gatewayContextId(),
                                "instanceId", route.instanceId()))));
    }

    private Mono<RouteDecision> resolveAffinity(GatewayCommand command, RoutingContext context, TaskRoute route) {
        if (!command.tenantId().equals(route.tenantId())) {
            return Mono.error(new RouteResolutionException(RouteResolutionException.Code.AUTHORIZATION_DENIED,
                    "task route tenant does not match command tenant"));
        }
        if (!command.principal().fingerprint().equals(route.principalFingerprint())) {
            return Mono.error(new RouteResolutionException(RouteResolutionException.Code.AUTHORIZATION_DENIED,
                    "task route principal does not match command principal"));
        }
        return registry.get(command.tenantId(), route.agentId()).switchIfEmpty(Mono.error(new RouteResolutionException(
                RouteResolutionException.Code.AGENT_UNAVAILABLE, "task route Agent is not available")))
                .flatMap(agent -> authorize(command, agent).map(ignored -> decision(command, context, agent,
                        Map.of("rule", "task-affinity", "gatewayTaskId", command.gatewayTaskId(),
                                "instanceId", route.instanceId()))));
    }

    private Mono<RouteDecision> resolveNew(GatewayCommand command, RoutingContext context) {
        TargetHint hint = command.targetHint();
        if (hint.agentId() != null && !hint.agentId().isBlank()) {
            return registry.get(command.tenantId(), hint.agentId())
                    .switchIfEmpty(Mono.error(new RouteResolutionException(RouteResolutionException.Code.AGENT_NOT_FOUND,
                            "target Agent was not found")))
                    .flatMap(agent -> validateHint(agent, hint).then(authorize(command, agent))
                            .map(ignored -> decision(command, context, agent,
                                    Map.of("rule", "explicit-agent", "agentId", agent.agentId()))));
        }
        if (hint.skillId() != null && !hint.skillId().isBlank()) {
            return registry.list(command.tenantId(), hint).collectList()
                    .flatMap(agents -> chooseUnique(command, context, agents, "exact-skill"));
        }
        if (!hint.labels().isEmpty()) {
            return registry.list(command.tenantId(), hint).collectList()
                    .flatMap(agents -> chooseUnique(command, context, agents, "routing-labels"));
        }
        String defaultAgent = defaultAgentByTenant.get(command.tenantId());
        if (defaultAgent == null || defaultAgent.isBlank()) {
            return Mono.error(new RouteResolutionException(RouteResolutionException.Code.AGENT_NOT_FOUND,
                    "no explicit target or tenant default Agent was supplied"));
        }
        return registry.get(command.tenantId(), defaultAgent)
                .switchIfEmpty(Mono.error(new RouteResolutionException(RouteResolutionException.Code.AGENT_NOT_FOUND,
                        "tenant default Agent was not found")))
                .flatMap(agent -> authorize(command, agent).map(ignored -> decision(command, context, agent,
                        Map.of("rule", "tenant-default", "agentId", agent.agentId()))));
    }

    private Mono<RouteDecision> chooseUnique(GatewayCommand command, RoutingContext context,
            List<AgentDefinition> agents, String rule) {
        List<AgentDefinition> ordered = new ArrayList<>(agents);
        ordered.sort(Comparator.comparing(AgentDefinition::agentId));
        if (ordered.isEmpty()) {
            return Mono.error(new RouteResolutionException(RouteResolutionException.Code.AGENT_NOT_FOUND,
                    "no Agent matched routing constraints"));
        }
        return Flux.fromIterable(ordered)
                .flatMap(agent -> authorizationPolicy.authorize(command.principal(), command, agent)
                        .filter(AuthorizationDecision::allowed)
                        .map(ignored -> agent))
                .collectList()
                .flatMap(authorized -> {
                    if (authorized.isEmpty()) {
                        return Mono.error(new RouteResolutionException(
                                RouteResolutionException.Code.AUTHORIZATION_DENIED,
                                "no matched Agent is authorized for this principal"));
                    }
                    if (authorized.size() > 1) {
                        return Mono.error(new RouteResolutionException(RouteResolutionException.Code.ROUTE_CONFLICT,
                                "routing constraints matched more than one authorized Agent",
                                authorized.stream().map(AgentDefinition::agentId).toList()));
                    }
                    AgentDefinition agent = authorized.get(0);
                    return Mono.just(decision(command, context, agent,
                            Map.of("rule", rule, "agentId", agent.agentId(),
                                    "skillId", command.targetHint().skillId() == null
                                            ? "" : command.targetHint().skillId())));
                });
    }

    private Mono<Void> validateHint(AgentDefinition agent, TargetHint hint) {
        if (hint.skillId() != null && agent.skills().stream().noneMatch(skill -> skill.skillId().equals(hint.skillId()))) {
            return Mono.error(new RouteResolutionException(RouteResolutionException.Code.ROUTE_CONFLICT,
                    "explicit Agent does not advertise requested Skill"));
        }
        if (hint.labels().entrySet().stream()
                .anyMatch(entry -> !entry.getValue().equals(agent.routingLabels().get(entry.getKey())))) {
            return Mono.error(new RouteResolutionException(RouteResolutionException.Code.ROUTE_CONFLICT,
                    "explicit Agent does not match routing labels"));
        }
        return Mono.empty();
    }

    private Mono<AuthorizationDecision> authorize(GatewayCommand command, AgentDefinition agent) {
        return authorizationPolicy.authorize(command.principal(), command, agent)
                .flatMap(decision -> decision.allowed() ? Mono.just(decision) : Mono.error(
                        new RouteResolutionException(RouteResolutionException.Code.AUTHORIZATION_DENIED,
                                decision.reason())));
    }

    private RouteDecision decision(GatewayCommand command, RoutingContext context, AgentDefinition agent,
            Map<String, String> matchedRules) {
        Map<String, String> rules = new LinkedHashMap<>(matchedRules);
        rules.put("requestId", context.requestId());
        return new RouteDecision(UUID.randomUUID().toString(), command.tenantId(), agent.agentId(), rules,
                Instant.now());
    }

}
