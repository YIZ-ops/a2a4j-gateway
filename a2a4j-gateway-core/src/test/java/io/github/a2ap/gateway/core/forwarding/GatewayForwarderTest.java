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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.github.a2ap.gateway.api.spi.ProtocolAdapter;
import io.github.a2ap.gateway.core.discovery.InMemoryAgentRegistry;
import io.github.a2ap.gateway.core.exception.GatewayForwardingException;
import io.github.a2ap.gateway.core.protocol.HttpJsonProtocolAdapter;
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

    @Test
    void mapsTaskNotFoundSemanticsAcrossHttpJsonAndJsonRpc() throws Exception {
        CountingTransport httpError = new CountingTransport();
        httpError.response = new OutboundResponse(ProtocolDescriptor.httpJson(false), 404,
                "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\",\"message\":\"missing\"}}",
                Map.of(), true);
        GatewayResult rpcResult = forwarder(httpError, "JSONRPC").forward(command(), context()).block();
        JsonNode rpcBody = new ObjectMapper().readTree((String) rpcResult.payload());
        assertEquals(-32001, rpcBody.at("/error/code").asInt());
        assertEquals(404, rpcResult.metadata().get("statusCode"));

        CountingTransport rpcError = new CountingTransport();
        rpcError.response = new OutboundResponse(ProtocolDescriptor.jsonRpc(), 200,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32001,"
                        + "\"message\":\"missing\"}}", Map.of(), true);
        GatewayResult httpResult = forwarder(rpcError, "HTTP+JSON").forward(httpCommand(), context()).block();
        JsonNode httpBody = new ObjectMapper().readTree((String) httpResult.payload());
        assertEquals(404, httpResult.metadata().get("statusCode"));
        assertEquals(404, httpBody.at("/error/code").asInt());
        assertEquals("NOT_FOUND", httpBody.at("/error/status").asText());
    }

    @Test
    void mapsEveryStandardA2aErrorCodeAcrossBindings() throws Exception {
        Map<Integer, Integer> mappings = Map.of(-32001, 404, -32002, 400, -32003, 400, -32004, 400,
                -32005, 400, -32006, 500, -32007, 400, -32008, 400, -32009, 400);
        for (Map.Entry<Integer, Integer> mapping : mappings.entrySet()) {
            CountingTransport transport = new CountingTransport();
            transport.response = new OutboundResponse(ProtocolDescriptor.jsonRpc(), 200,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":" + mapping.getKey()
                            + ",\"message\":\"failure\"}}",
                    Map.of(), true);
            GatewayResult result = forwarder(transport, "HTTP+JSON").forward(httpCommand(), context()).block();
            JsonNode body = new ObjectMapper().readTree((String) result.payload());
            assertEquals(mapping.getValue(), result.metadata().get("statusCode"), mapping.toString());
            assertEquals(mapping.getValue(), body.at("/error/code").asInt(), mapping.toString());
        }
    }

    @Test
    void listsTheStoredTaskStatusHistoryAndArtifacts() throws Exception {
        CountingTransport transport = new CountingTransport();
        transport.response = new OutboundResponse(ProtocolDescriptor.jsonRpc(), 200,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"task\":{"
                        + "\"id\":\"up-1\",\"contextId\":\"up-c\","
                        + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"},"
                        + "\"history\":[{\"role\":\"ROLE_USER\"},{\"role\":\"ROLE_AGENT\"}],"
                        + "\"artifacts\":[{\"artifactId\":\"artifact-1\"}]}}}", Map.of(), true);
        GatewayForwarder forwarder = forwarder(transport, "JSONRPC");
        assertNotNull(forwarder.forward(command(), context()).block());

        GatewayCommand listCommand = new GatewayCommand(GatewayCommand.Operation.LIST_TASKS, "tenant-a",
                command().principal(), new TargetHint("agent-a", null, Map.of()), null, null,
                Map.of("pageSize", 5, "historyLength", 1, "includeArtifacts", true), Map.of(),
                Map.of("jsonRpcId", "list-1"), null, ProtocolDescriptor.jsonRpc(), "1.0", Set.of());
        GatewayResult list = forwarder.forward(listCommand, context()).block();
        Map<?, ?> envelope = (Map<?, ?>) list.payload();
        Map<?, ?> resultPayload = (Map<?, ?>) envelope.get("result");
        Map<?, ?> task = (Map<?, ?>) ((List<?>) resultPayload.get("tasks")).get(0);
        assertEquals("TASK_STATE_COMPLETED", ((Map<?, ?>) task.get("status")).get("state"));
        assertEquals(1, ((List<?>) task.get("history")).size());
        assertEquals("ROLE_AGENT", ((Map<?, ?>) ((List<?>) task.get("history")).get(0)).get("role"));
        assertEquals("artifact-1", ((Map<?, ?>) ((List<?>) task.get("artifacts")).get(0)).get("artifactId"));
        assertTrue(!"up-1".equals(task.get("id")));
    }

    @Test
    void projectsExtendedCardsBeforeReturningThemToTheCaller() throws Exception {
        CountingTransport transport = new CountingTransport();
        transport.response = new OutboundResponse(ProtocolDescriptor.jsonRpc(), 200,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"name\":\"upstream\","
                        + "\"supportedInterfaces\":[{\"url\":\"https://internal.example/a2a\","
                        + "\"protocolBinding\":\"JSONRPC\",\"protocolVersion\":\"1.0\"}],"
                        + "\"signatures\":[{\"alg\":\"RS256\"}],"
                        + "\"securityRequirements\":[{\"schemes\":{\"upstream\":{\"list\":[]}}}]}}",
                Map.of(), true);
        GatewayCommand command = new GatewayCommand(GatewayCommand.Operation.GET_EXTENDED_AGENT_CARD, "tenant-a",
                new PrincipalContext("tenant-a", "user-a",
                        Set.of("agent:discover"), Map.of(), "fp-a"), new TargetHint("agent-a", null, Map.of()),
                null, null, Map.of(), Map.of(), Map.of("jsonRpcId", 1), null, ProtocolDescriptor.jsonRpc(), "1.0",
                Set.of());
        GatewayResult result = forwarder(transport, "JSONRPC",
                Map.of("capabilities", Map.of("extendedAgentCard", true))).forward(command, context()).block();
        String body = (String) result.payload();
        assertTrue(body.contains("/gateway/v1/agents/agent-a/a2a"));
        assertTrue(!body.contains("internal.example"));
        assertTrue(!body.contains("signatures"));
        assertTrue(!body.contains("securityRequirements"));
    }

    @Test
    void distinguishesUnsupportedAndUnconfiguredExtendedCards() throws Exception {
        CountingTransport unsupportedTransport = new CountingTransport();
        GatewayForwardingException unsupported = assertThrows(GatewayForwardingException.class,
                () -> forwarder(unsupportedTransport, "JSONRPC").forward(extendedCardCommand(), context()).block());
        assertEquals(GatewayForwardingException.Code.UNSUPPORTED_OPERATION, unsupported.code());

        CountingTransport unconfiguredTransport = new CountingTransport();
        unconfiguredTransport.response = new OutboundResponse(ProtocolDescriptor.jsonRpc(), 404,
                "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\",\"message\":\"missing\"}}",
                Map.of(), true);
        GatewayResult unconfigured = forwarder(unconfiguredTransport, "JSONRPC",
                Map.of("capabilities", Map.of("extendedAgentCard", true)))
                .forward(extendedCardCommand(), context()).block();
        JsonNode unconfiguredBody = new ObjectMapper().readTree((String) unconfigured.payload());
        assertEquals(-32007, unconfiguredBody.at("/error/code").asInt());
        assertEquals(400, unconfigured.metadata().get("statusCode"));
    }

    private GatewayForwarder forwarder(CountingTransport transport, String binding) {
        return forwarder(transport, binding, Map.of());
    }

    private GatewayForwarder forwarder(CountingTransport transport, String binding,
            Map<String, Object> cardMetadata) {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(agent(binding, cardMetadata));
        InMemoryTaskRouteStore routes = new InMemoryTaskRouteStore(10);
        CredentialProvider credentials = (tenant, reference, target) -> Mono.empty();
        ProtocolAdapter adapter = "HTTP+JSON".equals(binding) ? new HttpJsonProtocolAdapter()
                : new JsonRpcProtocolAdapter();
        return new GatewayForwarder(registry,
                new DeterministicRouteResolver(registry, routes, new DefaultAuthorizationPolicy(), Map.of()),
                new WeightedLeastActiveLoadBalancer(), adapter, transport, credentials, routes,
                new InMemoryIdempotencyStore(10),
                Duration.ofHours(1), Duration.ofSeconds(30), new TenantStreamLimiter(20),
                io.github.a2ap.gateway.api.spi.GatewayMetrics.noop(), Map.of(binding, adapter),
                new io.github.a2ap.gateway.core.routing.DefaultAgentInterfaceSelector());
    }

    private AgentDefinition agent() {
        return agent("JSONRPC");
    }

    private AgentDefinition agent(String binding) {
        return agent(binding, Map.of());
    }

    private AgentDefinition agent(String binding, Map<String, Object> cardMetadata) {
        AgentInstance instance = new AgentInstance("instance-1", "https://agent.example.test/card",
                List.of(new AgentInterface(binding.toLowerCase(), "https://agent.example.test/a2a", binding, "1.0",
                        null)),
                1, "ref-1", AgentInstance.HealthStatus.HEALTHY, "hash", Instant.now());
        return new AgentDefinition("tenant-a", "agent-a", "Agent A", true,
                List.of(new AgentSkillDefinition("echo", "Echo", "Echo", List.of(), List.of("text/plain"),
                        List.of("text/plain"))),
                Map.of(), ProtocolPolicy.a2aV1Mvp(), List.of(instance), cardMetadata);
    }

    private GatewayCommand extendedCardCommand() {
        return new GatewayCommand(GatewayCommand.Operation.GET_EXTENDED_AGENT_CARD, "tenant-a",
                command().principal(), new TargetHint("agent-a", null, Map.of()), null, null, Map.of(), Map.of(),
                Map.of("jsonRpcId", 1), null, ProtocolDescriptor.jsonRpc(), "1.0", Set.of());
    }

    private GatewayCommand command() {
        PrincipalContext principal = new PrincipalContext("tenant-a", "user-a",
                Set.of("agent:invoke:agent-a", "task:read", "agent:discover"), Map.of(), "fp-a");
        return new GatewayCommand(GatewayCommand.Operation.SEND_MESSAGE, "tenant-a", principal,
                new TargetHint("agent-a", null, Map.of()), null, null,
                Map.of("messageId", "m-1", "role", "ROLE_USER"), Map.of(), Map.of(), "key-1",
                ProtocolDescriptor.jsonRpc(), "1.0", Set.of());
    }

    private GatewayCommand httpCommand() {
        return new GatewayCommand(GatewayCommand.Operation.SEND_MESSAGE, "tenant-a", command().principal(),
                new TargetHint("agent-a", null, Map.of()), null, null,
                Map.of("messageId", "m-1", "role", "ROLE_USER"), Map.of(), Map.of(), "key-http",
                ProtocolDescriptor.httpJson(false), "1.0", Set.of());
    }

    private RoutingContext context() {
        return new RoutingContext("request-1", "trace-1", Instant.now().plusSeconds(30), Map.of());
    }

    private static final class CountingTransport implements io.github.a2ap.gateway.api.spi.AgentTransport {

        private final AtomicInteger calls = new AtomicInteger();

        private OutboundCredentials credentials;

        private OutboundResponse response = new OutboundResponse(ProtocolDescriptor.jsonRpc(), 200,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"task\":{"
                        + "\"id\":\"up-1\",\"contextId\":\"up-c\"}}}", Map.of(), true);

        @Override
        public Flux<OutboundResponse> exchange(AgentInstance target, OutboundRequest request,
                OutboundCredentials credentials) {
            calls.incrementAndGet();
            this.credentials = credentials;
            return Flux.just(response);
        }

    }

}
