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

package io.github.a2ap.gateway.core.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.a2ap.gateway.api.GatewayHeaders;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.InboundExchange;
import io.github.a2ap.gateway.api.model.OutboundRequest;
import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.api.model.ProtocolDescriptor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HttpJsonProtocolAdapterTest {

    private final HttpJsonProtocolAdapter adapter = new HttpJsonProtocolAdapter();

    @Test
    void decodesHttpRequestAndKeepsGatewayRoutingHeaders() {
        PrincipalContext principal = new PrincipalContext("tenant-a", "user-a", Set.of(), Map.of(), "fp-a");
        InboundExchange exchange = new InboundExchange(ProtocolDescriptor.httpJson(false),
                "{\"message\":{\"messageId\":\"m-1\",\"role\":\"ROLE_USER\","
                        + "\"parts\":[{\"text\":\"hello\"}]},\"configuration\":{\"returnImmediately\":true}}",
                Map.of(GatewayHeaders.GATEWAY_OPERATION, "SEND_MESSAGE", GatewayHeaders.TARGET_AGENT, "agent-a",
                        GatewayHeaders.A2A_VERSION, "1.0", GatewayHeaders.TRACEPARENT,
                        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"),
                "req-1", principal);

        GatewayCommand command = adapter.decode(exchange).block();
        assertEquals(GatewayCommand.Operation.SEND_MESSAGE, command.operation());
        assertEquals("agent-a", command.targetHint().agentId());
        assertEquals("m-1", command.message().get("messageId"));
        assertEquals(Boolean.TRUE, command.configuration().get("returnImmediately"));
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                command.metadata().get(GatewayHeaders.TRACEPARENT));
    }

    @Test
    void encodesHttpJsonAndUnwrapsJsonRpcResponse() throws Exception {
        PrincipalContext principal = new PrincipalContext("tenant-a", "user-a", Set.of(), Map.of(), "fp-a");
        InboundExchange exchange = new InboundExchange(ProtocolDescriptor.httpJson(false),
                "{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[]}}",
                Map.of(GatewayHeaders.GATEWAY_OPERATION, "SEND_MESSAGE", GatewayHeaders.TRACEPARENT,
                        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"), "req-1", principal);
        GatewayCommand command = adapter.decode(exchange).block();
        OutboundRequest request = adapter.encode(command, httpInstance()).block();
        assertEquals("application/a2a+json", request.headers().get("Content-Type"));
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                request.headers().get(GatewayHeaders.TRACEPARENT));
        assertEquals("HTTP+JSON", request.protocol().protocolBinding());
        assertEquals("task-1", new ObjectMapper().readTree(adapter.toHttpJson(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"task\":{\"id\":\"task-1\"}}}"))
                .at("/task/id").asText());
    }

    @Test
    void rejectsUnsupportedVersionAndRewritesStreamingIdentifiers() throws Exception {
        PrincipalContext principal = new PrincipalContext("tenant-a", "user-a", Set.of(), Map.of(), "fp-a");
        InboundExchange exchange = new InboundExchange(ProtocolDescriptor.httpJson(false), "{}",
                Map.of(GatewayHeaders.GATEWAY_OPERATION, "GET_TASK", GatewayHeaders.A2A_VERSION, "0.2.1"), "req-1",
                principal);
        assertThrows(IllegalArgumentException.class, () -> adapter.decode(exchange).block());
        String rewritten = adapter.rewriteTaskIdentifiers("{\"statusUpdate\":{\"taskId\":\"up-1\","
                + "\"contextId\":\"up-c\"}}", "up-1", "up-c", "gw-1", "gw-c");
        assertEquals("gw-1", new ObjectMapper().readTree(rewritten).at("/statusUpdate/taskId").asText());
        assertEquals("gw-c", new ObjectMapper().readTree(rewritten).at("/statusUpdate/contextId").asText());
    }

    private AgentInstance httpInstance() {
        return new AgentInstance("instance-1", "https://agent.example.test/card",
                List.of(new AgentInterface("http", "https://agent.example.test/a2a", "HTTP+JSON", "1.0", null)), 1,
                null, AgentInstance.HealthStatus.HEALTHY, "hash", Instant.now());
    }

}
