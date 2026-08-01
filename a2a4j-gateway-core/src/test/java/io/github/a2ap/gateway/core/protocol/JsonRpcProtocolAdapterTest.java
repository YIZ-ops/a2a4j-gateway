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

class JsonRpcProtocolAdapterTest {

    private final JsonRpcProtocolAdapter adapter = new JsonRpcProtocolAdapter();

    @Test
    void decodesAuthenticatedSendMessageAndEncodesGatewayTaskId() throws Exception {
        PrincipalContext principal = new PrincipalContext("tenant-a", "user-a", Set.of(), Map.of(), "fp-a");
        InboundExchange exchange = new InboundExchange(ProtocolDescriptor.jsonRpc(),
                "{\"jsonrpc\":\"2.0\",\"id\":\"r-1\",\"method\":\"SendMessage\","
                        + "\"params\":{\"message\":{\"messageId\":\"m-1\",\"role\":\"ROLE_USER\","
                        + "\"parts\":[{\"text\":\"hello\"}]}}}",
                Map.of(GatewayHeaders.TARGET_AGENT, "agent-a", GatewayHeaders.IDEMPOTENCY_KEY, "key-1"), "req-1",
                principal);

        GatewayCommand command = adapter.decode(exchange).block();
        assertEquals(GatewayCommand.Operation.SEND_MESSAGE, command.operation());
        assertEquals("agent-a", command.targetHint().agentId());
        assertEquals("key-1", command.idempotencyKey());
        assertEquals("m-1", command.message().get("messageId"));

        OutboundRequest outbound = adapter.encode(command, instance()).block();
        assertEquals("https://agent.example.test/a2a", outbound.endpointUrl());
        com.fasterxml.jackson.databind.JsonNode encoded = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(outbound.body());
        assertEquals("SendMessage", encoded.get("method").asText());
        assertEquals("m-1", encoded.at("/params/message/messageId").asText());
        assertEquals("text", encoded.at("/params/message/parts/0/kind").asText());
    }

    @Test
    void extractsAndRewritesTaskIdentifiersWithoutLeakingUpstreamIds() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"task\":{"
                + "\"id\":\"up-1\",\"contextId\":\"up-c\"}}}";
        JsonRpcTaskReference reference = adapter.extractTaskReference(body);
        assertEquals("up-1", reference.taskId());
        String rewritten = adapter.rewriteTaskIdentifiers(body, "up-1", "up-c", "gw-1", "gw-c");
        assertEquals("gw-1", new com.fasterxml.jackson.databind.ObjectMapper().readTree(rewritten)
                .at("/result/task/id").asText());
        assertEquals("gw-c", new com.fasterxml.jackson.databind.ObjectMapper().readTree(rewritten)
                .at("/result/task/contextId").asText());
        String update = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"statusUpdate\":{"
                + "\"taskId\":\"up-1\",\"contextId\":\"up-c\"}}}";
        String rewrittenUpdate = adapter.rewriteTaskIdentifiers(update, "up-1", "up-c", "gw-1", "gw-c");
        assertEquals("gw-1", new com.fasterxml.jackson.databind.ObjectMapper().readTree(rewrittenUpdate)
                .at("/result/statusUpdate/taskId").asText());
        assertEquals("gw-c", new com.fasterxml.jackson.databind.ObjectMapper().readTree(rewrittenUpdate)
                .at("/result/statusUpdate/contextId").asText());

        String directUpdate = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"kind\":\"status-update\","
                + "\"taskId\":\"up-1\",\"contextId\":\"up-c\",\"final\":false}}";
        JsonRpcTaskReference directReference = adapter.extractTaskReference(directUpdate);
        assertEquals("up-1", directReference.taskId());
        assertEquals("up-c", directReference.contextId());
        String rewrittenDirectUpdate = adapter.rewriteTaskIdentifiers(directUpdate, "up-1", "up-c", "gw-1", "gw-c");
        assertEquals("gw-1", new com.fasterxml.jackson.databind.ObjectMapper().readTree(rewrittenDirectUpdate)
                .at("/result/taskId").asText());
        assertEquals("gw-c", new com.fasterxml.jackson.databind.ObjectMapper().readTree(rewrittenDirectUpdate)
                .at("/result/contextId").asText());
    }

    @Test
    void requiresAuthenticationContext() {
        InboundExchange exchange = new InboundExchange(ProtocolDescriptor.jsonRpc(),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ListTasks\",\"params\":{}}", Map.of(),
                "req-1");
        assertThrows(IllegalArgumentException.class, () -> adapter.decode(exchange).block());
    }

    @Test
    void carriesLastEventIdIntoStreamingOutboundHeaders() {
        PrincipalContext principal = new PrincipalContext("tenant-a", "user-a", Set.of(), Map.of(), "fp-a");
        InboundExchange exchange = new InboundExchange(ProtocolDescriptor.jsonRpcStreaming(),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"SubscribeToTask\","
                        + "\"params\":{\"id\":\"gw-task\"}}",
                Map.of(GatewayHeaders.LAST_EVENT_ID, "event-7"), "req-stream", principal);
        GatewayCommand command = adapter.decode(exchange).block();
        OutboundRequest outbound = adapter.encode(command, instance()).block();
        assertEquals("event-7", outbound.headers().get(GatewayHeaders.LAST_EVENT_ID));
        assertEquals("text/event-stream", outbound.headers().get("Accept"));
        assertEquals("1.0", outbound.protocol().protocolVersion());
    }

    private AgentInstance instance() {
        return new AgentInstance("instance-1", "https://agent.example.test/card",
                List.of(new AgentInterface("jsonrpc", "https://agent.example.test/a2a", "JSONRPC", "1.0", null)), 1,
                null, AgentInstance.HealthStatus.HEALTHY, "hash", Instant.now());
    }

}
