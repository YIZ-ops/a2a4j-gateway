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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.AgentSkillDefinition;
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.GatewayEvent;
import io.github.a2ap.gateway.api.model.OutboundCredentials;
import io.github.a2ap.gateway.api.model.OutboundRequest;
import io.github.a2ap.gateway.api.model.OutboundResponse;
import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.api.model.ProtocolDescriptor;
import io.github.a2ap.gateway.api.model.ProtocolPolicy;
import io.github.a2ap.gateway.api.model.RoutingContext;
import io.github.a2ap.gateway.api.model.TargetHint;
import io.github.a2ap.gateway.api.model.TaskRoute;
import io.github.a2ap.gateway.api.spi.AgentTransport;
import io.github.a2ap.gateway.api.spi.CredentialProvider;
import io.github.a2ap.gateway.core.discovery.InMemoryAgentRegistry;
import io.github.a2ap.gateway.core.protocol.JsonRpcProtocolAdapter;
import io.github.a2ap.gateway.core.routing.DeterministicRouteResolver;
import io.github.a2ap.gateway.core.routing.WeightedLeastActiveLoadBalancer;
import io.github.a2ap.gateway.core.security.DefaultAuthorizationPolicy;
import io.github.a2ap.gateway.core.store.InMemoryTaskRouteStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class GatewayStreamingForwarderTest {

    @Test
    void streamsOrderedEventsRewritesIdentifiersAndCompletesRoute() {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(agent());
        InMemoryTaskRouteStore routes = new InMemoryTaskRouteStore(10);
        GatewayForwarder forwarder = new GatewayForwarder(registry,
                new DeterministicRouteResolver(registry, routes, new DefaultAuthorizationPolicy(), Map.of()),
                new WeightedLeastActiveLoadBalancer(), new JsonRpcProtocolAdapter(), new StreamingTransport(),
                credentials(), routes, null, Duration.ofHours(1), Duration.ofSeconds(5),
                new TenantStreamLimiter(2));

        List<GatewayEvent> events = forwarder.stream(command(), context()).collectList().block();

        assertEquals(2, events.size());
        assertEquals(GatewayEvent.Type.TASK_STATUS, events.get(0).type());
        assertEquals(GatewayEvent.Type.TASK_COMPLETED, events.get(1).type());
        String gatewayTaskId = events.get(0).gatewayTaskId();
        assertEquals(gatewayTaskId, events.get(1).gatewayTaskId());
        assertFalse(events.get(0).payload().toString().contains("up-1"));
        TaskRoute route = routes.find("tenant-a", gatewayTaskId).block();
        assertEquals(TaskRoute.State.COMPLETED, route.state());
        assertEquals("up-1", route.upstreamTaskId());
    }

    @Test
    void cancelsUpstreamWhenTheDownstreamCancelsAfterTheFirstEvent() {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(agent());
        InMemoryTaskRouteStore routes = new InMemoryTaskRouteStore(10);
        CancellableTransport transport = new CancellableTransport();
        GatewayForwarder forwarder = new GatewayForwarder(registry,
                new DeterministicRouteResolver(registry, routes, new DefaultAuthorizationPolicy(), Map.of()),
                new WeightedLeastActiveLoadBalancer(), new JsonRpcProtocolAdapter(), transport, credentials(), routes,
                null, Duration.ofHours(1), Duration.ofSeconds(5), new TenantStreamLimiter(2));

        List<GatewayEvent> events = forwarder.stream(command(), context()).take(1).collectList().block();

        assertEquals(1, events.size());
        assertTrue(transport.cancelled.get());
    }

    @Test
    void appendsArtifactPartsIntoOneSnapshotArtifact() {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(agent());
        InMemoryTaskRouteStore routes = new InMemoryTaskRouteStore(10);
        GatewayForwarder forwarder = new GatewayForwarder(registry,
                new DeterministicRouteResolver(registry, routes, new DefaultAuthorizationPolicy(), Map.of()),
                new WeightedLeastActiveLoadBalancer(), new JsonRpcProtocolAdapter(), new AppendTransport(),
                credentials(), routes, null, Duration.ofHours(1), Duration.ofSeconds(5),
                new TenantStreamLimiter(2));

        forwarder.stream(command(), context()).collectList().block();

        TaskRoute route = routes.list(new io.github.a2ap.gateway.api.model.TaskRouteQuery("tenant-a", null,
                "agent-a", Set.of(), 10, null, "fp-a", null)).block().routes().get(0);
        List<?> artifacts = (List<?>) route.taskSnapshot().get("artifacts");
        assertEquals(1, artifacts.size());
        assertEquals(2, ((List<?>) ((Map<?, ?>) artifacts.get(0)).get("parts")).size());
    }

    @Test
    void rejectsStreamingWhenCapabilityIsMissing() {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(agent(Map.of()));
        InMemoryTaskRouteStore routes = new InMemoryTaskRouteStore(10);
        GatewayForwarder forwarder = new GatewayForwarder(registry,
                new DeterministicRouteResolver(registry, routes, new DefaultAuthorizationPolicy(), Map.of()),
                new WeightedLeastActiveLoadBalancer(), new JsonRpcProtocolAdapter(), new StreamingTransport(),
                credentials(), routes, null, Duration.ofHours(1), Duration.ofSeconds(5),
                new TenantStreamLimiter(2));

        GatewayForwardingException error = assertThrows(GatewayForwardingException.class,
                () -> forwarder.stream(command(), context()).collectList().block());
        assertEquals(GatewayForwardingException.Code.UNSUPPORTED_OPERATION, error.code());
    }

    @Test
    void mapsAnUpstreamStreamRejectionBeforeEmittingAnEvent() {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(agent());
        InMemoryTaskRouteStore routes = new InMemoryTaskRouteStore(10);
        GatewayForwarder forwarder = new GatewayForwarder(registry,
                new DeterministicRouteResolver(registry, routes, new DefaultAuthorizationPolicy(), Map.of()),
                new WeightedLeastActiveLoadBalancer(), new JsonRpcProtocolAdapter(), new RejectedStreamTransport(),
                credentials(), routes, null, Duration.ofHours(1), Duration.ofSeconds(5),
                new TenantStreamLimiter(2));

        GatewayUpstreamException error = assertThrows(GatewayUpstreamException.class,
                () -> forwarder.stream(command(), context()).collectList().block());
        assertEquals(404, error.httpStatus());
        assertEquals(-32001, error.rpcCode());
        assertEquals("TASK_NOT_FOUND", error.reason());
    }

    private AgentDefinition agent() {
        return agent(Map.of("capabilities", Map.of("streaming", true)));
    }

    private AgentDefinition agent(Map<String, Object> cardMetadata) {
        AgentInstance instance = new AgentInstance("instance-1", "https://agent.example.test/card",
                List.of(new AgentInterface("jsonrpc", "https://agent.example.test/a2a", "JSONRPC", "1.0", null)),
                1, null, AgentInstance.HealthStatus.HEALTHY, "hash", Instant.now());
        return new AgentDefinition("tenant-a", "agent-a", "Agent A", true,
                List.of(new AgentSkillDefinition("echo", "Echo", "Echo", List.of(), List.of("text/plain"),
                        List.of("text/plain"))), Map.of(), ProtocolPolicy.a2aV1Mvp(), List.of(instance), cardMetadata);
    }

    private GatewayCommand command() {
        PrincipalContext principal = new PrincipalContext("tenant-a", "user-a",
                Set.of("agent:invoke:agent-a"), Map.of(), "fp-a");
        return new GatewayCommand(GatewayCommand.Operation.SEND_STREAMING_MESSAGE, "tenant-a", principal,
                new TargetHint("agent-a", null, Map.of()), null, null,
                Map.of("messageId", "m-1", "role", "ROLE_USER"), Map.of(), Map.of(), null,
                ProtocolDescriptor.jsonRpcStreaming(), "1.0", Set.of());
    }

    private RoutingContext context() {
        return new RoutingContext("request-1", "trace-1", Instant.now().plusSeconds(30), Map.of());
    }

    private CredentialProvider credentials() {
        return (tenant, reference, target) -> Flux.<OutboundCredentials>empty().next();
    }

    private static final class CancellableTransport implements AgentTransport {

        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public Flux<OutboundResponse> exchange(AgentInstance target, OutboundRequest request,
                OutboundCredentials credentials) {
            return exchangeStream(target, request, credentials);
        }

        @Override
        public Flux<OutboundResponse> exchangeStream(AgentInstance target, OutboundRequest request,
                OutboundCredentials credentials) {
            return Flux.create(sink -> {
                sink.onCancel(() -> cancelled.set(true));
                sink.next(new OutboundResponse(ProtocolDescriptor.jsonRpcStreaming(), 200,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"task\":{"
                                + "\"id\":\"up-1\",\"contextId\":\"up-c\"}}}", Map.of(), false));
            });
        }

    }

    private static final class StreamingTransport implements AgentTransport {

        @Override
        public Flux<OutboundResponse> exchange(AgentInstance target, OutboundRequest request,
                OutboundCredentials credentials) {
            return exchangeStream(target, request, credentials);
        }

        @Override
        public Flux<OutboundResponse> exchangeStream(AgentInstance target, OutboundRequest request,
                OutboundCredentials credentials) {
            return Flux.just(new OutboundResponse(ProtocolDescriptor.jsonRpcStreaming(), 200,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"task\":{"
                            + "\"id\":\"up-1\",\"contextId\":\"up-c\"}}}", Map.of("SSE-Id", "e-1"), false),
                    new OutboundResponse(ProtocolDescriptor.jsonRpcStreaming(), 200,
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"statusUpdate\":{"
                                    + "\"taskId\":\"up-1\",\"contextId\":\"up-c\","
                                    + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}}}",
                            Map.of("SSE-Id", "e-2"), true));
        }

    }

    private static final class AppendTransport implements AgentTransport {

        @Override
        public Flux<OutboundResponse> exchange(AgentInstance target, OutboundRequest request,
                OutboundCredentials credentials) {
            return exchangeStream(target, request, credentials);
        }

        @Override
        public Flux<OutboundResponse> exchangeStream(AgentInstance target, OutboundRequest request,
                OutboundCredentials credentials) {
            return Flux.just(response("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"task\":{"
                            + "\"id\":\"up-1\",\"contextId\":\"up-c\",\"status\":{\"state\":\"TASK_STATE_WORKING\"}}}}", false),
                    response("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"artifactUpdate\":{"
                            + "\"taskId\":\"up-1\",\"contextId\":\"up-c\",\"append\":false,"
                            + "\"artifact\":{\"artifactId\":\"a-1\",\"parts\":[{\"text\":\"one\"}]}}}}", false),
                    response("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"artifactUpdate\":{"
                            + "\"taskId\":\"up-1\",\"contextId\":\"up-c\",\"append\":true,"
                            + "\"artifact\":{\"artifactId\":\"a-1\",\"parts\":[{\"text\":\"two\"}]}}}}", false),
                    response("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"statusUpdate\":{"
                            + "\"taskId\":\"up-1\",\"contextId\":\"up-c\","
                            + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}}}", true));
        }

        private OutboundResponse response(String body, boolean terminal) {
            return new OutboundResponse(ProtocolDescriptor.jsonRpcStreaming(), 200, body, Map.of(), terminal);
        }

    }

    private static final class RejectedStreamTransport implements AgentTransport {

        @Override
        public Flux<OutboundResponse> exchange(AgentInstance target, OutboundRequest request,
                OutboundCredentials credentials) {
            return exchangeStream(target, request, credentials);
        }

        @Override
        public Flux<OutboundResponse> exchangeStream(AgentInstance target, OutboundRequest request,
                OutboundCredentials credentials) {
            return Flux.just(new OutboundResponse(ProtocolDescriptor.jsonRpcStreaming(), 404,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32001,"
                            + "\"message\":\"missing\"}}",
                    Map.of(), true));
        }

    }

}
