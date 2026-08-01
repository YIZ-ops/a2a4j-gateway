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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.AgentSkillDefinition;
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.api.model.ProtocolDescriptor;
import io.github.a2ap.gateway.api.model.ProtocolPolicy;
import io.github.a2ap.gateway.api.model.RoutingContext;
import io.github.a2ap.gateway.api.model.TargetHint;
import io.github.a2ap.gateway.api.model.TaskRoute;
import io.github.a2ap.gateway.api.spi.TaskRouteStore;
import io.github.a2ap.gateway.core.discovery.InMemoryAgentRegistry;
import io.github.a2ap.gateway.core.security.DefaultAuthorizationPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class DeterministicRouteResolverTest {

    private static final PrincipalContext PRINCIPAL = new PrincipalContext("tenant-a", "user-a",
            Set.of("agent:invoke:agent-a", "agent:invoke:agent-b", "skill:invoke:echo"), Map.of(), "fp-a");

    @Test
    void resolvesExplicitAgentAndSkillAndRecordsRules() {
        InMemoryAgentRegistry registry = registry(agent("agent-a", Map.of("region", "cn"), "echo"));
        DeterministicRouteResolver resolver = new DeterministicRouteResolver(registry,
                new DefaultAuthorizationPolicy(), Map.of());

        var decision = resolver.resolve(command(new TargetHint("agent-a", "echo", Map.of("region", "cn"))),
                context()).block();

        assertEquals("agent-a", decision.agentId());
        assertEquals("explicit-agent", decision.matchedRules().get("rule"));
        assertEquals("agent-a", decision.matchedRules().get("agentId"));
    }

    @Test
    void rejectsSkillConflictsAndUsesTenantDefaultOnlyWhenConfigured() {
        InMemoryAgentRegistry registry = registry(agent("agent-a", Map.of(), "echo"),
                agent("agent-b", Map.of(), "echo"));
        DeterministicRouteResolver conflictResolver = new DeterministicRouteResolver(registry,
                new DefaultAuthorizationPolicy(), Map.of());
        RouteResolutionException conflict = assertThrows(RouteResolutionException.class,
                () -> conflictResolver.resolve(command(new TargetHint(null, "echo", Map.of())), context()).block());
        assertEquals(RouteResolutionException.Code.ROUTE_CONFLICT, conflict.code());
        assertEquals(List.of("agent-a", "agent-b"), conflict.candidates());

        DeterministicRouteResolver defaultResolver = new DeterministicRouteResolver(registry,
                new DefaultAuthorizationPolicy(), Map.of("tenant-a", "agent-b"));
        var decision = defaultResolver.resolve(command(TargetHint.empty()), context()).block();
        assertEquals("agent-b", decision.agentId());
        assertEquals("tenant-default", decision.matchedRules().get("rule"));
    }

    @Test
    void honorsTaskAffinityAndRejectsMissingAuthorization() {
        InMemoryAgentRegistry registry = registry(agent("agent-a", Map.of(), "echo"));
        TaskRouteStore routes = new SingleRouteStore(new TaskRoute("tenant-a", "task-1", null,
                "agent-a", "instance-1", "jsonrpc", "upstream-task", null, "JSONRPC", "1.0", "fp-a", null,
                TaskRoute.State.ACTIVE, Instant.now(), Instant.now(), null));
        DeterministicRouteResolver resolver = new DeterministicRouteResolver(registry, routes,
                new DefaultAuthorizationPolicy(), Map.of());

        var decision = resolver.resolve(command(new TargetHint(null, null, Map.of()), "task-1"), context()).block();
        assertEquals("agent-a", decision.agentId());
        assertEquals("task-affinity", decision.matchedRules().get("rule"));

        PrincipalContext otherPrincipal = new PrincipalContext("tenant-a", "other-user", Set.of(), Map.of(), "fp-b");
        RouteResolutionException affinityError = assertThrows(RouteResolutionException.class,
                () -> resolver.resolve(command(new TargetHint(null, null, Map.of()), "task-1", otherPrincipal),
                        context()).block());
        assertEquals(RouteResolutionException.Code.AUTHORIZATION_DENIED, affinityError.code());

        PrincipalContext denied = new PrincipalContext("tenant-a", "user-a", Set.of(), Map.of(), "fp-a");
        RouteResolutionException error = assertThrows(RouteResolutionException.class,
                () -> resolver.resolve(command(new TargetHint("agent-a", null, Map.of()), null, denied), context())
                        .block());
        assertEquals(RouteResolutionException.Code.AUTHORIZATION_DENIED, error.code());
    }

    private InMemoryAgentRegistry registry(AgentDefinition... agents) {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replaceAll(List.of(agents));
        return registry;
    }

    private AgentDefinition agent(String id, Map<String, String> labels, String skill) {
        return new AgentDefinition("tenant-a", id, id, true,
                List.of(new AgentSkillDefinition(skill, skill, skill, List.of("tag"), List.of("text/plain"),
                        List.of("text/plain"))), labels, ProtocolPolicy.a2aV1Mvp(), List.of(instance()));
    }

    private AgentInstance instance() {
        return new AgentInstance("instance-1", "https://agent.example.test/card",
                List.of(new AgentInterface("jsonrpc", "https://agent.example.test/a2a", "JSONRPC", "1.0", null)),
                1, null, AgentInstance.HealthStatus.HEALTHY, "hash", Instant.now());
    }

    private GatewayCommand command(TargetHint hint) {
        return command(hint, null, PRINCIPAL);
    }

    private GatewayCommand command(TargetHint hint, String taskId) {
        return command(hint, taskId, PRINCIPAL);
    }

    private GatewayCommand command(TargetHint hint, String taskId, PrincipalContext principal) {
        return new GatewayCommand(GatewayCommand.Operation.SEND_MESSAGE, "tenant-a", principal, hint, taskId, null,
                Map.of(), Map.of(), Map.of(), null, ProtocolDescriptor.jsonRpc(), "1.0", Set.of());
    }

    private RoutingContext context() {
        return new RoutingContext("request-1", "trace-1", Instant.now().plusSeconds(30), Map.of());
    }

    private static final class SingleRouteStore implements TaskRouteStore {

        private final TaskRoute route;

        private SingleRouteStore(TaskRoute route) {
            this.route = route;
        }

        @Override
        public Mono<TaskRoute> find(String tenantId, String gatewayTaskId) {
            return tenantId.equals(route.tenantId()) && gatewayTaskId.equals(route.gatewayTaskId())
                    ? Mono.just(route) : Mono.empty();
        }

        @Override
        public Mono<io.github.a2ap.gateway.api.model.TaskRoutePage> list(
                io.github.a2ap.gateway.api.model.TaskRouteQuery query) {
            return Mono.error(new UnsupportedOperationException());
        }

        @Override
        public Mono<Void> save(TaskRoute route) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> touch(String tenantId, String gatewayTaskId, Instant expiresAt) {
            return Mono.empty();
        }

    }

}
