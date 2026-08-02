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

package io.github.a2ap.gateway.core.forwarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.AgentSkillDefinition;
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.GatewayResult;
import io.github.a2ap.gateway.api.model.OutboundCredentials;
import io.github.a2ap.gateway.api.model.OutboundRequest;
import io.github.a2ap.gateway.api.model.OutboundResponse;
import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.api.model.ProtocolDescriptor;
import io.github.a2ap.gateway.api.model.ProtocolPolicy;
import io.github.a2ap.gateway.api.model.RoutingContext;
import io.github.a2ap.gateway.api.model.TargetHint;
import io.github.a2ap.gateway.api.spi.CredentialProvider;
import io.github.a2ap.gateway.core.discovery.InMemoryAgentRegistry;
import io.github.a2ap.gateway.core.protocol.JsonRpcProtocolAdapter;
import io.github.a2ap.gateway.core.routing.DeterministicRouteResolver;
import io.github.a2ap.gateway.core.routing.WeightedLeastActiveLoadBalancer;
import io.github.a2ap.gateway.core.security.DefaultAuthorizationPolicy;
import io.github.a2ap.gateway.core.store.InMemoryIdempotencyStore;
import io.github.a2ap.gateway.core.store.InMemoryTaskRouteStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class GatewayForwarderTest {

    @Test
    void routesSendsPersistsAffinityRewritesIdsAndReplaysIdempotency() {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(agent());
        InMemoryTaskRouteStore routes = new InMemoryTaskRouteStore(10);
        InMemoryIdempotencyStore idempotency = new InMemoryIdempotencyStore(10);
        CountingTransport transport = new CountingTransport();
        CredentialProvider credentials = (tenant, reference, target) -> Mono.just(new OutboundCredentials("Bearer",
                "secret"));
        GatewayForwarder forwarder = new GatewayForwarder(registry,
                new DeterministicRouteResolver(registry, routes, new DefaultAuthorizationPolicy(), Map.of()),
                new WeightedLeastActiveLoadBalancer(), new JsonRpcProtocolAdapter(), transport, credentials, routes,
                idempotency, Duration.ofHours(1));

        GatewayResult first = forwarder.forward(command(), context()).block();
        GatewayResult replay = forwarder.forward(command(), context()).block();

        assertNotNull(first);
        assertEquals(first.payload(), replay.payload());
        assertEquals(1, transport.calls.get());
        assertEquals("Bearer", transport.credentials.scheme());
        assertEquals(1, routes.size());
        assertEquals(1, idempotency.size());
        assertEquals(200, first.metadata().get("statusCode"));
    }

    private AgentDefinition agent() {
        AgentInstance instance = new AgentInstance("instance-1", "https://agent.example.test/card",
                List.of(new AgentInterface("jsonrpc", "https://agent.example.test/a2a", "JSONRPC", "1.0", null)),
                1, "ref-1", AgentInstance.HealthStatus.HEALTHY, "hash", Instant.now());
        return new AgentDefinition("tenant-a", "agent-a", "Agent A", true,
                List.of(new AgentSkillDefinition("echo", "Echo", "Echo", List.of(), List.of("text/plain"),
                        List.of("text/plain"))),
                Map.of(), ProtocolPolicy.a2aV1Mvp(), List.of(instance));
    }

    private GatewayCommand command() {
        PrincipalContext principal = new PrincipalContext("tenant-a", "user-a",
                Set.of("agent:invoke:agent-a"), Map.of(), "fp-a");
        return new GatewayCommand(GatewayCommand.Operation.SEND_MESSAGE, "tenant-a", principal,
                new TargetHint("agent-a", null, Map.of()), null, null,
                Map.of("messageId", "m-1", "role", "ROLE_USER"), Map.of(), Map.of(), "key-1",
                ProtocolDescriptor.jsonRpc(), "1.0", Set.of());
    }

    private RoutingContext context() {
        return new RoutingContext("request-1", "trace-1", Instant.now().plusSeconds(30), Map.of());
    }

    private static final class CountingTransport implements io.github.a2ap.gateway.api.spi.AgentTransport {

        private final AtomicInteger calls = new AtomicInteger();

        private OutboundCredentials credentials;

        @Override
        public Flux<OutboundResponse> exchange(AgentInstance target, OutboundRequest request,
                OutboundCredentials credentials) {
            calls.incrementAndGet();
            this.credentials = credentials;
            return Flux.just(new OutboundResponse(ProtocolDescriptor.jsonRpc(), 200,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"task\":{"
                            + "\"id\":\"up-1\",\"contextId\":\"up-c\","
                            + "\"status\":{\"state\":\"TASK_STATE_WORKING\"}}}}", Map.of(), true));
        }

    }

}
