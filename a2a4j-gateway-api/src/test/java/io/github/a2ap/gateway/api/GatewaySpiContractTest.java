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

package io.github.a2ap.gateway.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.AgentSkillDefinition;
import io.github.a2ap.gateway.api.model.AuthorizationDecision;
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.GatewayEvent;
import io.github.a2ap.gateway.api.model.InboundExchange;
import io.github.a2ap.gateway.api.model.OutboundCredentials;
import io.github.a2ap.gateway.api.model.OutboundRequest;
import io.github.a2ap.gateway.api.model.OutboundResponse;
import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.api.model.ProtocolDescriptor;
import io.github.a2ap.gateway.api.model.ProtocolPolicy;
import io.github.a2ap.gateway.api.model.RouteDecision;
import io.github.a2ap.gateway.api.model.RoutingContext;
import io.github.a2ap.gateway.api.model.TargetHint;
import io.github.a2ap.gateway.api.model.TaskRoute;
import io.github.a2ap.gateway.api.model.TaskRoutePage;
import io.github.a2ap.gateway.api.model.TaskRouteQuery;
import io.github.a2ap.gateway.api.spi.AgentInterfaceSelector;
import io.github.a2ap.gateway.api.spi.AgentLoadBalancer;
import io.github.a2ap.gateway.api.spi.AgentRegistry;
import io.github.a2ap.gateway.api.spi.AgentTransport;
import io.github.a2ap.gateway.api.spi.AuthorizationPolicy;
import io.github.a2ap.gateway.api.spi.CredentialProvider;
import io.github.a2ap.gateway.api.spi.IdempotencyStore;
import io.github.a2ap.gateway.api.spi.ProtocolAdapter;
import io.github.a2ap.gateway.api.spi.RouteResolver;
import io.github.a2ap.gateway.api.spi.TaskRouteStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GatewaySpiContractTest {

    private static final AgentInterface AGENT_INTERFACE = new AgentInterface(
            "jsonrpc", "https://agent.example.test/a2a", "JSONRPC", "1.0", null);

    private static final AgentInstance INSTANCE = new AgentInstance(
            "instance-1", "https://agent.example.test/.well-known/agent-card.json",
            List.of(AGENT_INTERFACE), 1, "credential-ref", AgentInstance.HealthStatus.HEALTHY,
            "card-hash", Instant.parse("2026-07-31T00:00:00Z"));

    private static final AgentDefinition AGENT = new AgentDefinition(
            "tenant-a", "agent-a", "Agent A", true,
            List.of(new AgentSkillDefinition("echo", "Echo", "Echo text", List.of("text"),
                    List.of("text/plain"), List.of("text/plain"))),
            Map.of("region", "cn"), ProtocolPolicy.a2aV1Mvp(), List.of(INSTANCE));

    private static final PrincipalContext PRINCIPAL = new PrincipalContext(
            "tenant-a", "user-a", Set.of("agent:invoke"), Map.of("role", "user"), "principal-hash");

    private static final GatewayCommand COMMAND = new GatewayCommand(
            GatewayCommand.Operation.SEND_MESSAGE, "tenant-a", PRINCIPAL,
            new TargetHint("agent-a", "echo", Map.of()), null, null,
            Map.of("role", "ROLE_USER"), Map.of(), Map.of(), "idem-1",
            ProtocolDescriptor.jsonRpc(), "1.0", Set.of());

    @Test
    void modelsAreImmutableAndSecretsAreRedacted() {
        assertThrows(UnsupportedOperationException.class, () -> AGENT.skills().add(
                new AgentSkillDefinition("other", "Other", "Other", List.of(), List.of(), List.of())));
        assertThrows(UnsupportedOperationException.class, () -> COMMAND.metadata().put("secret", "value"));

        OutboundCredentials credentials = new OutboundCredentials("Bearer", "top-secret");
        assertTrue(credentials.toString().contains("[REDACTED]"));
        assertTrue(!credentials.toString().contains("top-secret"));
        assertTrue(INSTANCE.eligibleForNewWork());
    }

    @Test
    void spiContractsRemainAsynchronousAndTenantScoped() {
        AgentRegistry registry = new AgentRegistry() {
            @Override
            public Flux<AgentDefinition> list(String tenantId, TargetHint targetHint) {
                return Flux.just(AGENT);
            }

            @Override
            public Mono<AgentDefinition> get(String tenantId, String agentId) {
                return Mono.just(AGENT);
            }

            @Override
            public Flux<AgentDefinition> findBySkill(String tenantId, String skillId) {
                return Flux.just(AGENT);
            }
        };
        RouteResolver resolver = (command, context) -> Mono.just(
                new RouteDecision("decision-1", command.tenantId(), AGENT.agentId(), Map.of("rule", "explicit"),
                        Instant.now()));
        AgentLoadBalancer loadBalancer = (agent, command, context) -> Mono.just(INSTANCE);
        AgentInterfaceSelector selector = (instance, inbound, command) -> Mono.just(AGENT_INTERFACE);
        ProtocolAdapter adapter = new ProtocolAdapter() {
            @Override
            public ProtocolDescriptor descriptor() {
                return ProtocolDescriptor.jsonRpc();
            }

            @Override
            public Mono<GatewayCommand> decode(InboundExchange exchange) {
                return Mono.just(COMMAND);
            }

            @Override
            public Mono<OutboundRequest> encode(GatewayCommand command, AgentInstance target) {
                return Mono.just(new OutboundRequest(descriptor(), AGENT_INTERFACE.endpointUrl(), "{}", Map.of(), null));
            }

            @Override
            public Flux<GatewayEvent> decodeResponse(OutboundResponse response) {
                return Flux.just(new GatewayEvent(GatewayEvent.Type.TASK_COMPLETED, "tenant-a", null,
                        response.body(), Instant.now(), Map.of()));
            }
        };
        AgentTransport transport = (target, request, credentials) -> Flux.just(
                new OutboundResponse(request.protocol(), 200, "{}", Map.of(), true));
        TaskRouteStore routeStore = new TaskRouteStore() {
            @Override
            public Mono<TaskRoute> find(String tenantId, String gatewayTaskId) {
                return Mono.empty();
            }

            @Override
            public Mono<TaskRoutePage> list(TaskRouteQuery query) {
                return Mono.just(new TaskRoutePage(List.of(), null));
            }

            @Override
            public Mono<Void> save(TaskRoute route) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> touch(String tenantId, String gatewayTaskId, Instant expiresAt) {
                return Mono.empty();
            }
        };
        CredentialProvider credentialProvider = (tenantId, credentialRef, target) ->
                Mono.just(new OutboundCredentials("Bearer", "secret"));
        AuthorizationPolicy authorizationPolicy = (principal, command, agent) -> Mono.just(
                AuthorizationDecision.allow());
        IdempotencyStore idempotencyStore = new IdempotencyStore() {
            @Override
            public Mono<io.github.a2ap.gateway.api.model.IdempotencyRecord> find(String tenantId, String key) {
                return Mono.empty();
            }

            @Override
            public Mono<io.github.a2ap.gateway.api.model.IdempotencyRecord> begin(String tenantId, String key,
                    String requestHash) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> complete(String tenantId, String key,
                    io.github.a2ap.gateway.api.model.GatewayResult result) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> markOutcomeUnknown(String tenantId, String key) {
                return Mono.empty();
            }
        };

        StepVerifier.create(registry.get("tenant-a", "agent-a")).expectNext(AGENT).verifyComplete();
        StepVerifier.create(resolver.resolve(COMMAND, new RoutingContext("req-1", "trace-1", null, Map.of())))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(loadBalancer.choose(AGENT, COMMAND, new RoutingContext("req-1", "trace-1", null, Map.of())))
                .expectNext(INSTANCE).verifyComplete();
        StepVerifier.create(selector.choose(INSTANCE, ProtocolDescriptor.jsonRpc(), COMMAND))
                .expectNext(AGENT_INTERFACE).verifyComplete();
        StepVerifier.create(adapter.decode(new InboundExchange(ProtocolDescriptor.jsonRpc(), "{}", Map.of(), "req-1")))
                .expectNext(COMMAND).verifyComplete();
        StepVerifier.create(adapter.encode(COMMAND, INSTANCE)).expectNextCount(1).verifyComplete();
        StepVerifier.create(transport.exchange(INSTANCE, new OutboundRequest(ProtocolDescriptor.jsonRpc(),
                AGENT_INTERFACE.endpointUrl(), "{}", Map.of(), null), new OutboundCredentials("Bearer", "secret")))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(routeStore.list(new TaskRouteQuery("tenant-a", null, null, Set.of(), 10, null)))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(credentialProvider.resolve("tenant-a", "credential-ref", INSTANCE))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(authorizationPolicy.authorize(PRINCIPAL, COMMAND, AGENT))
                .expectNext(AuthorizationDecision.allow()).verifyComplete();
        StepVerifier.create(idempotencyStore.find("tenant-a", "idem-1")).verifyComplete();
    }

}
