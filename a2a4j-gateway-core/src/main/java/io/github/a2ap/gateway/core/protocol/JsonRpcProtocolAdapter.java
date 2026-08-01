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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.a2ap.core.protocol.v1.A2AProtocolV1;
import io.github.a2ap.core.protocol.v1.A2AProtocolV1Validator;
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
import io.github.a2ap.gateway.api.model.TargetHint;
import io.github.a2ap.gateway.api.spi.ProtocolAdapter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A2A 1.0 JSON-RPC adapter for synchronous and SSE streaming paths. */
public final class JsonRpcProtocolAdapter implements ProtocolAdapter {

    private final ObjectMapper objectMapper;

    /** Creates an adapter using a fresh Jackson mapper. */
    public JsonRpcProtocolAdapter() {
        this(new ObjectMapper());
    }

    /** Creates an adapter using the supplied thread-safe Jackson mapper. */
    public JsonRpcProtocolAdapter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ProtocolDescriptor descriptor() {
        return ProtocolDescriptor.jsonRpc();
    }

    @Override
    public Mono<GatewayCommand> decode(InboundExchange exchange) {
        Objects.requireNonNull(exchange, "exchange");
        if (!A2AProtocolV1.JSON_RPC_BINDING.equals(exchange.protocol().protocolBinding())) {
            return Mono.error(new IllegalArgumentException("JSON-RPC adapter received another binding"));
        }
        PrincipalContext principal = exchange.principal();
        if (principal == null) {
            return Mono.error(new IllegalArgumentException("authenticated principal is required"));
        }
        try {
            JsonNode request = objectMapper.readTree(exchange.body());
            A2AProtocolV1Validator.validateJsonRpcRequest(request);
            String requestedVersion = header(exchange, GatewayHeaders.A2A_VERSION);
            if (requestedVersion != null && !A2AProtocolV1.VERSION.equals(requestedVersion)) {
                throw new IllegalArgumentException("unsupported A2A-Version: " + requestedVersion);
            }
            JsonNode params = request.has("params") ? request.get("params") : objectMapper.createObjectNode();
            Map<String, Object> paramsMap = objectMapper.convertValue(params, Map.class);
            GatewayCommand.Operation operation = operation(request.get("method").asText());
            String taskId = header(exchange, GatewayHeaders.GATEWAY_TASK_ID);
            if (taskId == null && (operation == GatewayCommand.Operation.GET_TASK
                    || operation == GatewayCommand.Operation.CANCEL_TASK
                    || operation == GatewayCommand.Operation.SUBSCRIBE_TO_TASK)) {
                taskId = text(params, "id");
            }
            Map<String, Object> message = mapValue(params, "message");
            Map<String, Object> configuration = mapValue(params, "configuration");
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("jsonRpcId", objectMapper.convertValue(request.get("id"), Object.class));
            metadata.put("jsonRpcMethod", request.get("method").asText());
            metadata.put("inboundRequestId", exchange.requestId());
            copyTraceHeaders(exchange, metadata);
            String lastEventId = header(exchange, GatewayHeaders.LAST_EVENT_ID);
            if (lastEventId != null) {
                metadata.put("lastEventId", lastEventId);
            }
            TargetHint targetHint = new TargetHint(header(exchange, GatewayHeaders.TARGET_AGENT),
                    header(exchange, GatewayHeaders.TARGET_SKILL), Map.of());
            Set<String> extensions = splitExtensions(header(exchange, GatewayHeaders.A2A_EXTENSIONS));
            String idempotencyKey = header(exchange, GatewayHeaders.IDEMPOTENCY_KEY);
            return Mono.just(new GatewayCommand(operation, principal.tenantId(), principal, targetHint, taskId,
                    text(params, "contextId"), message.isEmpty() ? paramsMap : message, configuration, metadata,
                    idempotencyKey, exchange.protocol(), requestedVersion == null
                            ? exchange.protocol().protocolVersion() : requestedVersion, extensions));
        }
        catch (Exception ex) {
            return Mono.error(ex instanceof IllegalArgumentException ? ex
                    : new IllegalArgumentException("invalid A2A JSON-RPC request", ex));
        }
    }

    @Override
    public Mono<OutboundRequest> encode(GatewayCommand command, AgentInstance target) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(target, "target");
        AgentInterface agentInterface = target.interfaces().stream()
                .filter(candidate -> A2AProtocolV1.JSON_RPC_BINDING.equals(candidate.protocolBinding()))
                .filter(candidate -> A2AProtocolV1.VERSION.equals(candidate.protocolVersion()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("target Agent has no A2A 1.0 JSON-RPC interface"));
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("jsonrpc", A2AProtocolV1.JSON_RPC_VERSION);
            request.put("id", command.metadata().getOrDefault("jsonRpcId", UUID.randomUUID().toString()));
            request.put("method", method(command.operation()));
            Map<String, Object> params = new LinkedHashMap<>();
            if (command.operation() == GatewayCommand.Operation.SEND_MESSAGE
                    || command.operation() == GatewayCommand.Operation.SEND_STREAMING_MESSAGE) {
                params.put("message", command.message());
            }
            else {
                params.putAll(command.message());
            }
            if (!command.configuration().isEmpty()) {
                params.put("configuration", command.configuration());
            }
            if (isTaskOperation(command.operation())) {
                Object upstreamTaskId = command.metadata().getOrDefault("upstreamTaskId", command.gatewayTaskId());
                Object upstreamContextId = command.metadata().getOrDefault("upstreamContextId",
                        command.gatewayContextId());
                if (upstreamTaskId != null) {
                    params.put("id", upstreamTaskId);
                }
                if (upstreamContextId != null) {
                    params.put("contextId", upstreamContextId);
                }
            }
            if (command.gatewayTaskId() != null && !command.gatewayTaskId().isBlank()) {
                params.putIfAbsent("gatewayTaskId", command.gatewayTaskId());
            }
            request.put("params", params);
            boolean streaming = isStreamingOperation(command.operation());
            ProtocolDescriptor outboundProtocol = streaming ? ProtocolDescriptor.jsonRpcStreaming() : descriptor();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Accept", streaming ? "text/event-stream" : "application/json");
            headers.put(A2AProtocolV1.VERSION_HEADER, A2AProtocolV1.VERSION);
            Object lastEventId = command.metadata().get("lastEventId");
            if (streaming && lastEventId != null && !lastEventId.toString().isBlank()) {
                headers.put(GatewayHeaders.LAST_EVENT_ID, lastEventId.toString());
            }
            if (!command.extensions().isEmpty()) {
                headers.put(A2AProtocolV1.EXTENSIONS_HEADER, String.join(",", command.extensions()));
            }
            copyTraceHeaders(command, headers);
            return Mono.just(new OutboundRequest(outboundProtocol, agentInterface.endpointUrl(),
                    objectMapper.writeValueAsString(request), headers, command.gatewayTaskId()));
        }
        catch (Exception ex) {
            return Mono.error(new IllegalArgumentException("could not encode A2A JSON-RPC request", ex));
        }
    }

    private void copyTraceHeaders(InboundExchange exchange, Map<String, Object> metadata) {
        String traceparent = header(exchange, GatewayHeaders.TRACEPARENT);
        String tracestate = header(exchange, GatewayHeaders.TRACESTATE);
        if (io.github.a2ap.gateway.api.GatewayTraceContext.parse(traceparent).isPresent()) {
            metadata.put(GatewayHeaders.TRACEPARENT, traceparent);
            if (tracestate != null && !tracestate.isBlank()) {
                metadata.put(GatewayHeaders.TRACESTATE, tracestate);
            }
        }
    }

    private static void copyTraceHeaders(GatewayCommand command, Map<String, String> headers) {
        Object traceparent = command.metadata().get(GatewayHeaders.TRACEPARENT);
        if (traceparent != null && io.github.a2ap.gateway.api.GatewayTraceContext.parse(traceparent.toString()).isPresent()) {
            headers.put(GatewayHeaders.TRACEPARENT, traceparent.toString());
            Object tracestate = command.metadata().get(GatewayHeaders.TRACESTATE);
            if (tracestate != null && !tracestate.toString().isBlank()) {
                headers.put(GatewayHeaders.TRACESTATE, tracestate.toString());
            }
        }
    }

    @Override
    public Flux<GatewayEvent> decodeResponse(OutboundResponse response) {
        Objects.requireNonNull(response, "response");
        try {
            JsonNode body = objectMapper.readTree(response.body());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("statusCode", response.statusCode());
            metadata.put("terminal", response.terminal());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || body.has("error")) {
                metadata.put("error", body.has("error") ? body.get("error") : response.body());
                return Flux.just(new GatewayEvent(GatewayEvent.Type.ERROR, "unknown", null, body, Instant.now(),
                        metadata));
            }
            JsonRpcTaskReference reference = extractTaskReference(body);
            if (reference.taskId() != null) {
                metadata.put("upstreamTaskId", reference.taskId());
            }
            if (reference.contextId() != null) {
                metadata.put("upstreamContextId", reference.contextId());
            }
            GatewayEvent.Type type = eventType(body, response.terminal(), reference.taskId());
            return Flux.just(new GatewayEvent(type, "unknown", null, body, Instant.now(), metadata));
        }
        catch (Exception ex) {
            return Flux.error(new IllegalArgumentException("invalid A2A JSON-RPC response", ex));
        }
    }

    /** Extracts task and context identifiers from a JSON-RPC result envelope. */
    public JsonRpcTaskReference extractTaskReference(String body) {
        try {
            return extractTaskReference(objectMapper.readTree(body));
        }
        catch (Exception ex) {
            throw new IllegalArgumentException("invalid A2A JSON-RPC response", ex);
        }
    }

    /** Rewrites upstream task and context identifiers in a JSON-RPC response body. */
    public String rewriteTaskIdentifiers(String body, String upstreamTaskId, String upstreamContextId,
            String gatewayTaskId, String gatewayContextId) {
        try {
            JsonNode node = objectMapper.readTree(body);
            rewrite(node, upstreamTaskId, upstreamContextId, gatewayTaskId, gatewayContextId);
            return objectMapper.writeValueAsString(node);
        }
        catch (Exception ex) {
            throw new IllegalArgumentException("invalid A2A JSON-RPC response", ex);
        }
    }

    private JsonRpcTaskReference extractTaskReference(JsonNode response) {
        JsonNode result = response == null ? null : response.get("result");
        JsonNode task = result != null && result.has("task") ? result.get("task") : result;
        String taskId = text(task, "id");
        String contextId = text(task, "contextId");
        JsonNode update = streamingUpdate(result);
        if (taskId == null) {
            taskId = text(update, "taskId");
        }
        if (contextId == null) {
            contextId = text(update, "contextId");
        }
        if (contextId == null && result != null) {
            contextId = text(result, "contextId");
        }
        return new JsonRpcTaskReference(taskId, contextId);
    }

    private void rewrite(JsonNode node, String upstreamTaskId, String upstreamContextId,
            String gatewayTaskId, String gatewayContextId) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getKey().equals("id") && upstreamTaskId != null && entry.getValue().isTextual()
                        && entry.getValue().asText().equals(upstreamTaskId)) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("id", gatewayTaskId);
                }
                if (entry.getKey().equals("taskId") && upstreamTaskId != null && entry.getValue().isTextual()
                        && entry.getValue().asText().equals(upstreamTaskId)) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("taskId", gatewayTaskId);
                }
                if (entry.getKey().equals("contextId") && upstreamContextId != null && entry.getValue().isTextual()
                        && entry.getValue().asText().equals(upstreamContextId)) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("contextId", gatewayContextId);
                }
                rewrite(entry.getValue(), upstreamTaskId, upstreamContextId, gatewayTaskId, gatewayContextId);
            });
        }
        else if (node.isArray()) {
            node.forEach(child -> rewrite(child, upstreamTaskId, upstreamContextId, gatewayTaskId,
                    gatewayContextId));
        }
    }

    private GatewayCommand.Operation operation(String method) {
        return switch (method) {
            case "SendMessage" -> GatewayCommand.Operation.SEND_MESSAGE;
            case "SendStreamingMessage" -> GatewayCommand.Operation.SEND_STREAMING_MESSAGE;
            case "GetTask" -> GatewayCommand.Operation.GET_TASK;
            case "ListTasks" -> GatewayCommand.Operation.LIST_TASKS;
            case "CancelTask" -> GatewayCommand.Operation.CANCEL_TASK;
            case "SubscribeToTask" -> GatewayCommand.Operation.SUBSCRIBE_TO_TASK;
            case "CreateTaskPushNotificationConfig" -> GatewayCommand.Operation.CREATE_TASK_PUSH_NOTIFICATION_CONFIG;
            case "GetTaskPushNotificationConfig" -> GatewayCommand.Operation.GET_TASK_PUSH_NOTIFICATION_CONFIG;
            case "ListTaskPushNotificationConfigs" -> GatewayCommand.Operation.LIST_TASK_PUSH_NOTIFICATION_CONFIGS;
            case "DeleteTaskPushNotificationConfig" -> GatewayCommand.Operation.DELETE_TASK_PUSH_NOTIFICATION_CONFIG;
            case "GetExtendedAgentCard" -> GatewayCommand.Operation.GET_EXTENDED_AGENT_CARD;
            default -> throw new IllegalArgumentException("unsupported A2A JSON-RPC method: " + method);
        };
    }

    private String method(GatewayCommand.Operation operation) {
        return switch (operation) {
            case SEND_MESSAGE -> "SendMessage";
            case SEND_STREAMING_MESSAGE -> "SendStreamingMessage";
            case GET_TASK -> "GetTask";
            case LIST_TASKS -> "ListTasks";
            case CANCEL_TASK -> "CancelTask";
            case SUBSCRIBE_TO_TASK -> "SubscribeToTask";
            case CREATE_TASK_PUSH_NOTIFICATION_CONFIG -> "CreateTaskPushNotificationConfig";
            case GET_TASK_PUSH_NOTIFICATION_CONFIG -> "GetTaskPushNotificationConfig";
            case LIST_TASK_PUSH_NOTIFICATION_CONFIGS -> "ListTaskPushNotificationConfigs";
            case DELETE_TASK_PUSH_NOTIFICATION_CONFIG -> "DeleteTaskPushNotificationConfig";
            case GET_EXTENDED_AGENT_CARD -> "GetExtendedAgentCard";
        };
    }

    private boolean isTaskOperation(GatewayCommand.Operation operation) {
        return operation == GatewayCommand.Operation.GET_TASK || operation == GatewayCommand.Operation.CANCEL_TASK
                || operation == GatewayCommand.Operation.SUBSCRIBE_TO_TASK;
    }

    private boolean isStreamingOperation(GatewayCommand.Operation operation) {
        return operation == GatewayCommand.Operation.SEND_STREAMING_MESSAGE
                || operation == GatewayCommand.Operation.SUBSCRIBE_TO_TASK;
    }

    private GatewayEvent.Type eventType(JsonNode body, boolean terminal, String taskId) {
        JsonNode result = body == null ? null : body.get("result");
        JsonNode update = streamingUpdate(result);
        if (isArtifactUpdate(result, update)) {
            return GatewayEvent.Type.TASK_ARTIFACT;
        }
        if (isStatusUpdate(result, update)) {
            if (isTerminalStatus(update.get("status"))) {
                return GatewayEvent.Type.TASK_COMPLETED;
            }
            return GatewayEvent.Type.TASK_STATUS;
        }
        if (terminal || taskId == null) {
            return taskId == null ? GatewayEvent.Type.TASK_STATUS : GatewayEvent.Type.TASK_COMPLETED;
        }
        return GatewayEvent.Type.TASK_STATUS;
    }

    /** Returns the event payload from an A2A 1.0 stream wrapper. */
    private JsonNode streamingUpdate(JsonNode result) {
        if (result == null || !result.isObject()) {
            return null;
        }
        if (result.has("statusUpdate")) {
            return result.get("statusUpdate");
        }
        if (result.has("artifactUpdate")) {
            return result.get("artifactUpdate");
        }
        return null;
    }

    private boolean isArtifactUpdate(JsonNode result, JsonNode update) {
        return result != null && result.has("artifactUpdate");
    }

    private boolean isStatusUpdate(JsonNode result, JsonNode update) {
        return result != null && result.has("statusUpdate");
    }

    private boolean isTerminalStatus(JsonNode status) {
        if (status == null || !status.isObject()) {
            return false;
        }
        String state = text(status, "state");
        return state != null && (state.endsWith("COMPLETED") || state.endsWith("FAILED")
                || state.endsWith("CANCELED") || state.endsWith("REJECTED"));
    }

    private Map<String, Object> mapValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isObject() ? Map.of() : objectMapper.convertValue(value, Map.class);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
    }

    private String header(InboundExchange exchange, String name) {
        return exchange.headers().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
    }

    private Set<String> splitExtensions(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        return List.of(header.split(",")).stream().map(String::trim).filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

}
