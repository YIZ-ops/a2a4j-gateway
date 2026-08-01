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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.a2ap.gateway.api.GatewayHeaders;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.GatewayEvent;
import io.github.a2ap.gateway.api.model.InboundExchange;
import io.github.a2ap.gateway.api.model.OutboundRequest;
import io.github.a2ap.gateway.api.model.OutboundResponse;
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
                "{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"hello\"}]}}",
                Map.of(GatewayHeaders.GATEWAY_OPERATION, "SEND_MESSAGE", GatewayHeaders.TRACEPARENT,
                        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"), "req-1", principal);
        GatewayCommand command = adapter.decode(exchange).block();
        OutboundRequest request = adapter.encode(command, httpInstance()).block();
        assertEquals("application/a2a+json", request.headers().get("Content-Type"));
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                request.headers().get(GatewayHeaders.TRACEPARENT));
        assertEquals("HTTP+JSON", request.protocol().protocolBinding());
        assertFalse(new ObjectMapper().readTree(request.body()).at("/message/parts/0").has("kind"));
        assertEquals("task-1", new ObjectMapper().readTree(adapter.toHttpJson(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"task\":{\"id\":\"task-1\"}}}"))
                .at("/task/id").asText());
    }

    @Test
    void encodesEveryStandardHttpJsonOperationWithItsHttpTarget() throws Exception {
        OutboundRequest send = encode(GatewayCommand.Operation.SEND_MESSAGE, null,
                Map.of("messageId", "m-1", "role", "ROLE_USER"));
        assertEquals("POST", send.httpMethod());
        assertEquals("https://agent.example.test/a2a/message:send", send.endpointUrl());
        assertEquals("m-1", new ObjectMapper().readTree(send.body()).at("/message/messageId").asText());

        OutboundRequest stream = encode(GatewayCommand.Operation.SEND_STREAMING_MESSAGE, null,
                Map.of("messageId", "m-2"));
        assertEquals("POST", stream.httpMethod());
        assertEquals("https://agent.example.test/a2a/message:stream", stream.endpointUrl());
        assertEquals("m-2", new ObjectMapper().readTree(stream.body()).at("/message/messageId").asText());

        OutboundRequest get = encode(GatewayCommand.Operation.GET_TASK, "task-1",
                Map.of("id", "task-1", "historyLength", 10));
        assertEquals("GET", get.httpMethod());
        assertEquals("https://agent.example.test/a2a/tasks/task-1?historyLength=10", get.endpointUrl());
        assertEquals("", get.body());

        OutboundRequest list = encode(GatewayCommand.Operation.LIST_TASKS, null,
                Map.of("contextId", "context-1", "pageSize", 25, "includeArtifacts", true));
        assertEquals("GET", list.httpMethod());
        assertTrue(list.endpointUrl().startsWith("https://agent.example.test/a2a/tasks?"));
        assertTrue(list.endpointUrl().contains("contextId=context-1"));
        assertTrue(list.endpointUrl().contains("pageSize=25"));
        assertTrue(list.endpointUrl().contains("includeArtifacts=true"));
        assertEquals("", list.body());

        OutboundRequest cancel = encode(GatewayCommand.Operation.CANCEL_TASK, "task-2",
                Map.of("id", "task-2", "metadata", Map.of("reason", "user")));
        assertEquals("POST", cancel.httpMethod());
        assertEquals("https://agent.example.test/a2a/tasks/task-2:cancel", cancel.endpointUrl());
        assertEquals("user", new ObjectMapper().readTree(cancel.body()).at("/metadata/reason").asText());
        assertTrue(new ObjectMapper().readTree(cancel.body()).get("id") == null);

        OutboundRequest subscribe = encode(GatewayCommand.Operation.SUBSCRIBE_TO_TASK, "task-3",
                Map.of("id", "task-3"));
        assertEquals("POST", subscribe.httpMethod());
        assertEquals("https://agent.example.test/a2a/tasks/task-3:subscribe", subscribe.endpointUrl());
        assertEquals("{}", subscribe.body());

        OutboundRequest createPush = encode(GatewayCommand.Operation.CREATE_TASK_PUSH_NOTIFICATION_CONFIG, "task-4",
                Map.of("url", "https://callback.example.test/a2a"));
        assertEquals("POST", createPush.httpMethod());
        assertEquals("https://agent.example.test/a2a/tasks/task-4/pushNotificationConfigs",
                createPush.endpointUrl());
        assertEquals("https://callback.example.test/a2a",
                new ObjectMapper().readTree(createPush.body()).at("/url").asText());

        OutboundRequest getPush = encode(GatewayCommand.Operation.GET_TASK_PUSH_NOTIFICATION_CONFIG, "task-4",
                Map.of("id", "config-1"));
        assertEquals("GET", getPush.httpMethod());
        assertEquals("https://agent.example.test/a2a/tasks/task-4/pushNotificationConfigs/config-1",
                getPush.endpointUrl());
        assertEquals("", getPush.body());

        OutboundRequest listPush = encode(GatewayCommand.Operation.LIST_TASK_PUSH_NOTIFICATION_CONFIGS, "task-4",
                Map.of("pageSize", 5, "pageToken", "next"));
        assertEquals("GET", listPush.httpMethod());
        assertTrue(listPush.endpointUrl().contains("/tasks/task-4/pushNotificationConfigs?"));
        assertTrue(listPush.endpointUrl().contains("pageSize=5"));
        assertTrue(listPush.endpointUrl().contains("pageToken=next"));
        assertEquals("", listPush.body());

        OutboundRequest deletePush = encode(GatewayCommand.Operation.DELETE_TASK_PUSH_NOTIFICATION_CONFIG, "task-4",
                Map.of("id", "config-1"));
        assertEquals("DELETE", deletePush.httpMethod());
        assertEquals("https://agent.example.test/a2a/tasks/task-4/pushNotificationConfigs/config-1",
                deletePush.endpointUrl());
        assertEquals("", deletePush.body());

        OutboundRequest card = encode(GatewayCommand.Operation.GET_EXTENDED_AGENT_CARD, null, Map.of());
        assertEquals("GET", card.httpMethod());
        assertEquals("https://agent.example.test/a2a/extendedAgentCard", card.endpointUrl());
        assertEquals("", card.body());
    }

    @Test
    void rejectsTaskBoundHttpJsonOperationsWithoutRequiredIdentifiers() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> encode(GatewayCommand.Operation.GET_TASK, null, Map.of()));
        assertTrue(error.getMessage().contains("requires a task id"));
    }

    @Test
    void decodesV1StreamingWrappers() {
        String v1Status = "{\"statusUpdate\":{\"taskId\":\"up-1\",\"contextId\":\"up-c\","
                + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}}";
        GatewayEvent status = adapter.decodeResponse(new OutboundResponse(ProtocolDescriptor.httpJson(true), 200,
                v1Status, Map.of(), false)).blockFirst();
        assertEquals(GatewayEvent.Type.TASK_COMPLETED, status.type());
        assertEquals("up-1", adapter.extractTaskReference(v1Status).taskId());

        String v1Artifact = "{\"artifactUpdate\":{\"taskId\":\"up-1\",\"contextId\":\"up-c\","
                + "\"artifact\":{\"artifactId\":\"a-1\",\"parts\":[{\"text\":\"chunk\"}]},"
                + "\"append\":false,\"lastChunk\":true}}";
        GatewayEvent artifact = adapter.decodeResponse(new OutboundResponse(ProtocolDescriptor.httpJson(true), 200,
                v1Artifact, Map.of(), false)).blockFirst();
        assertEquals(GatewayEvent.Type.TASK_ARTIFACT, artifact.type());

        String finalStatus = "{\"statusUpdate\":{\"taskId\":\"up-1\",\"contextId\":\"up-c\","
                + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}}";
        GatewayEvent completed = adapter.decodeResponse(new OutboundResponse(ProtocolDescriptor.httpJson(true), 200,
                finalStatus, Map.of(), false)).blockFirst();
        assertEquals(GatewayEvent.Type.TASK_COMPLETED, completed.type());
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

    private OutboundRequest encode(GatewayCommand.Operation operation, String taskId,
            Map<String, Object> message) {
        GatewayCommand command = new GatewayCommand(operation, "tenant-a", principal(),
                io.github.a2ap.gateway.api.model.TargetHint.empty(), taskId, null, message, Map.of(), Map.of(), null,
                ProtocolDescriptor.httpJson(operation == GatewayCommand.Operation.SEND_STREAMING_MESSAGE
                        || operation == GatewayCommand.Operation.SUBSCRIBE_TO_TASK), "1.0", Set.of());
        return adapter.encode(command, httpInstance()).block();
    }

    private PrincipalContext principal() {
        return new PrincipalContext("tenant-a", "user-a", Set.of(), Map.of(), "fp-a");
    }

    private AgentInstance httpInstance() {
        return new AgentInstance("instance-1", "https://agent.example.test/card",
                List.of(new AgentInterface("http", "https://agent.example.test/a2a", "HTTP+JSON", "1.0", null)), 1,
                null, AgentInstance.HealthStatus.HEALTHY, "hash", Instant.now());
    }

}
