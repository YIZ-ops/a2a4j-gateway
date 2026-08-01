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
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.a2ap.core.protocol.v1.A2AProtocolV1;
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
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A2A 1.0 HTTP+JSON adapter and JSON-RPC response un-wrapper. */
public final class HttpJsonProtocolAdapter implements ProtocolAdapter {

    private final ObjectMapper objectMapper;

    /** Creates an adapter using a fresh Jackson mapper. */
    public HttpJsonProtocolAdapter() {
        this(new ObjectMapper());
    }

    /** Creates an adapter using the supplied thread-safe Jackson mapper. */
    public HttpJsonProtocolAdapter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ProtocolDescriptor descriptor() {
        return ProtocolDescriptor.httpJson(false);
    }

    @Override
    public Mono<GatewayCommand> decode(InboundExchange exchange) {
        Objects.requireNonNull(exchange, "exchange");
        if (!A2AProtocolV1.HTTP_JSON_BINDING.equals(exchange.protocol().protocolBinding())) {
            return Mono.error(new IllegalArgumentException("HTTP+JSON adapter received another binding"));
        }
        PrincipalContext principal = exchange.principal();
        if (principal == null) {
            return Mono.error(new IllegalArgumentException("authenticated principal is required"));
        }
        try {
            String requestedVersion = header(exchange, GatewayHeaders.A2A_VERSION);
            if (requestedVersion != null && !A2AProtocolV1.VERSION.equals(requestedVersion)) {
                throw new IllegalArgumentException("unsupported A2A-Version: " + requestedVersion);
            }
            JsonNode request = exchange.body().isBlank() ? objectMapper.createObjectNode()
                    : objectMapper.readTree(exchange.body());
            if (request == null || !request.isObject()) {
                throw new IllegalArgumentException("HTTP+JSON request body must be an object");
            }
            GatewayCommand.Operation operation = operation(header(exchange, GatewayHeaders.GATEWAY_OPERATION), request);
            String taskId = firstText(header(exchange, GatewayHeaders.GATEWAY_TASK_ID), text(request, "id"),
                    text(request, "taskId"), text(request.get("message"), "taskId"));
            String contextId = firstText(text(request, "contextId"), text(request.get("message"), "contextId"));
            Map<String, Object> message = mapValue(request, "message");
            Map<String, Object> configuration = mapValue(request, "configuration");
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("httpOperation", operation.name());
            metadata.put("inboundRequestId", exchange.requestId());
            copyTraceHeaders(exchange, metadata);
            Map<String, Object> requestMetadata = mapValue(request, "metadata");
            if (!requestMetadata.isEmpty()) {
                metadata.put("requestMetadata", requestMetadata);
            }
            if (message.isEmpty() && !isMessageOperation(operation)) {
                message = objectMapper.convertValue(request, Map.class);
            }
            String lastEventId = header(exchange, GatewayHeaders.LAST_EVENT_ID);
            if (lastEventId != null) {
                metadata.put("lastEventId", lastEventId);
            }
            TargetHint targetHint = new TargetHint(header(exchange, GatewayHeaders.TARGET_AGENT),
                    header(exchange, GatewayHeaders.TARGET_SKILL), Map.of());
            return Mono.just(new GatewayCommand(operation, principal.tenantId(), principal, targetHint, taskId,
                    contextId, message, configuration, metadata, header(exchange, GatewayHeaders.IDEMPOTENCY_KEY),
                    exchange.protocol(), requestedVersion == null ? A2AProtocolV1.VERSION : requestedVersion,
                    splitExtensions(header(exchange, GatewayHeaders.A2A_EXTENSIONS))));
        }
        catch (Exception ex) {
            return Mono.error(ex instanceof IllegalArgumentException ? ex
                    : new IllegalArgumentException("invalid A2A HTTP+JSON request: " + ex.getMessage(), ex));
        }
    }

    @Override
    public Mono<OutboundRequest> encode(GatewayCommand command, AgentInstance target) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(target, "target");
        AgentInterface agentInterface = target.interfaces().stream()
                .filter(candidate -> A2AProtocolV1.HTTP_JSON_BINDING.equals(candidate.protocolBinding()))
                .filter(candidate -> A2AProtocolV1.VERSION.equals(candidate.protocolVersion()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("target Agent has no A2A 1.0 HTTP+JSON interface"));
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            if (isMessageOperation(command.operation())) {
                request.put("message", command.message());
            }
            else {
                request.putAll(command.message());
            }
            if (!command.configuration().isEmpty()) {
                request.put("configuration", command.configuration());
            }
            if (command.gatewayContextId() != null && !command.gatewayContextId().isBlank()) {
                request.put("contextId", command.gatewayContextId());
            }
            if (isTaskOperation(command.operation())) {
                Object upstreamTaskId = command.metadata().getOrDefault("upstreamTaskId", command.gatewayTaskId());
                if (upstreamTaskId != null) {
                    request.put("id", upstreamTaskId);
                }
            }
            boolean streaming = command.operation() == GatewayCommand.Operation.SEND_STREAMING_MESSAGE
                    || command.operation() == GatewayCommand.Operation.SUBSCRIBE_TO_TASK;
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/a2a+json");
            headers.put("Accept", streaming ? "text/event-stream" : "application/a2a+json");
            headers.put(A2AProtocolV1.VERSION_HEADER, A2AProtocolV1.VERSION);
            Object lastEventId = command.metadata().get("lastEventId");
            if (streaming && lastEventId != null && !lastEventId.toString().isBlank()) {
                headers.put(GatewayHeaders.LAST_EVENT_ID, lastEventId.toString());
            }
            if (!command.extensions().isEmpty()) {
                headers.put(A2AProtocolV1.EXTENSIONS_HEADER, String.join(",", command.extensions()));
            }
            copyTraceHeaders(command, headers);
            return Mono.just(new OutboundRequest(streaming ? ProtocolDescriptor.httpJson(true) : descriptor(),
                    agentInterface.endpointUrl(), objectMapper.writeValueAsString(request), headers,
                    command.gatewayTaskId()));
        }
        catch (Exception ex) {
            return Mono.error(new IllegalArgumentException("could not encode A2A HTTP+JSON request", ex));
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
            return Flux.error(new IllegalArgumentException("invalid A2A HTTP+JSON response", ex));
        }
    }

    /** Converts a JSON-RPC response envelope to an HTTP+JSON response body. */
    public String toHttpJson(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            return objectMapper.writeValueAsString(toHttpNode(node));
        }
        catch (Exception ex) {
            throw new IllegalArgumentException("invalid response JSON", ex);
        }
    }

    /** Converts a normalized event payload to an HTTP+JSON event body. */
    public String toHttpJson(Object payload) {
        try {
            JsonNode node = payload instanceof JsonNode jsonNode ? jsonNode : objectMapper.valueToTree(payload);
            return objectMapper.writeValueAsString(toHttpNode(node));
        }
        catch (Exception ex) {
            throw new IllegalArgumentException("invalid event payload", ex);
        }
    }

    /** Extracts task and context identifiers from an HTTP+JSON or JSON-RPC body. */
    public JsonRpcTaskReference extractTaskReference(String body) {
        try {
            return extractTaskReference(objectMapper.readTree(body));
        }
        catch (Exception ex) {
            throw new IllegalArgumentException("invalid response JSON", ex);
        }
    }

    /** Rewrites task and context identifiers in a response body. */
    public String rewriteTaskIdentifiers(String body, String upstreamTaskId, String upstreamContextId,
            String gatewayTaskId, String gatewayContextId) {
        try {
            JsonNode node = objectMapper.readTree(body);
            rewrite(node, upstreamTaskId, upstreamContextId, gatewayTaskId, gatewayContextId);
            return objectMapper.writeValueAsString(node);
        }
        catch (Exception ex) {
            throw new IllegalArgumentException("invalid response JSON", ex);
        }
    }

    private JsonNode toHttpNode(JsonNode node) {
        if (node != null && node.isObject() && node.has("jsonrpc")) {
            if (node.has("error")) {
                ObjectNode error = objectMapper.createObjectNode();
                error.set("error", node.get("error"));
                return error;
            }
            if (node.has("result")) {
                return node.get("result");
            }
        }
        return node;
    }

    private GatewayCommand.Operation operation(String value, JsonNode request) {
        if (value == null || value.isBlank()) {
            return request.has("message") ? GatewayCommand.Operation.SEND_MESSAGE
                    : GatewayCommand.Operation.GET_TASK;
        }
        return switch (value) {
            case "SEND_MESSAGE", "SendMessage", "message:send" -> GatewayCommand.Operation.SEND_MESSAGE;
            case "SEND_STREAMING_MESSAGE", "SendStreamingMessage", "message:stream" ->
                    GatewayCommand.Operation.SEND_STREAMING_MESSAGE;
            case "GET_TASK", "GetTask", "tasks:get" -> GatewayCommand.Operation.GET_TASK;
            case "LIST_TASKS", "ListTasks", "tasks:list" -> GatewayCommand.Operation.LIST_TASKS;
            case "CANCEL_TASK", "CancelTask", "tasks:cancel" -> GatewayCommand.Operation.CANCEL_TASK;
            case "SUBSCRIBE_TO_TASK", "SubscribeToTask", "tasks:subscribe" ->
                    GatewayCommand.Operation.SUBSCRIBE_TO_TASK;
            case "CREATE_TASK_PUSH_NOTIFICATION_CONFIG" ->
                    GatewayCommand.Operation.CREATE_TASK_PUSH_NOTIFICATION_CONFIG;
            case "GET_TASK_PUSH_NOTIFICATION_CONFIG" -> GatewayCommand.Operation.GET_TASK_PUSH_NOTIFICATION_CONFIG;
            case "LIST_TASK_PUSH_NOTIFICATION_CONFIGS" ->
                    GatewayCommand.Operation.LIST_TASK_PUSH_NOTIFICATION_CONFIGS;
            case "DELETE_TASK_PUSH_NOTIFICATION_CONFIG" ->
                    GatewayCommand.Operation.DELETE_TASK_PUSH_NOTIFICATION_CONFIG;
            case "GET_EXTENDED_AGENT_CARD" -> GatewayCommand.Operation.GET_EXTENDED_AGENT_CARD;
            default -> throw new IllegalArgumentException("unsupported HTTP+JSON operation: " + value);
        };
    }

    private JsonRpcTaskReference extractTaskReference(JsonNode body) {
        JsonNode node = toHttpNode(body);
        JsonNode task = node != null && node.has("task") ? node.get("task") : node;
        String taskId = text(task, "id");
        String contextId = text(task, "contextId");
        JsonNode update = node == null ? null : node.has("statusUpdate") ? node.get("statusUpdate")
                : node.get("artifactUpdate");
        if (taskId == null) {
            taskId = text(update, "taskId");
        }
        if (contextId == null) {
            contextId = text(update, "contextId");
        }
        return new JsonRpcTaskReference(taskId, contextId);
    }

    private GatewayEvent.Type eventType(JsonNode body, boolean terminal, String taskId) {
        JsonNode node = toHttpNode(body);
        if (node != null && (node.has("artifactUpdate") || node.has("artifact"))) {
            return GatewayEvent.Type.TASK_ARTIFACT;
        }
        if (node != null && node.has("statusUpdate")) {
            JsonNode statusUpdate = node.get("statusUpdate");
            if ((statusUpdate.has("final") && statusUpdate.get("final").asBoolean())
                    || isTerminalStatus(statusUpdate.get("status"))) {
                return GatewayEvent.Type.TASK_COMPLETED;
            }
            return GatewayEvent.Type.TASK_STATUS;
        }
        return terminal || taskId == null ? (taskId == null ? GatewayEvent.Type.TASK_STATUS
                : GatewayEvent.Type.TASK_COMPLETED) : GatewayEvent.Type.TASK_STATUS;
    }

    private boolean isMessageOperation(GatewayCommand.Operation operation) {
        return operation == GatewayCommand.Operation.SEND_MESSAGE
                || operation == GatewayCommand.Operation.SEND_STREAMING_MESSAGE;
    }

    private boolean isTaskOperation(GatewayCommand.Operation operation) {
        return operation == GatewayCommand.Operation.GET_TASK || operation == GatewayCommand.Operation.CANCEL_TASK
                || operation == GatewayCommand.Operation.SUBSCRIBE_TO_TASK;
    }

    private boolean isTerminalStatus(JsonNode status) {
        if (status == null || !status.isObject()) {
            return false;
        }
        String state = text(status, "state");
        return state != null && (state.endsWith("COMPLETED") || state.endsWith("FAILED")
                || state.endsWith("CANCELED") || state.endsWith("REJECTED"));
    }

    private void rewrite(JsonNode node, String upstreamTaskId, String upstreamContextId,
            String gatewayTaskId, String gatewayContextId) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if ((entry.getKey().equals("id") || entry.getKey().equals("taskId")) && upstreamTaskId != null
                        && entry.getValue().isTextual()
                        && entry.getValue().asText().equals(upstreamTaskId)) {
                    ((ObjectNode) node).put(entry.getKey(), gatewayTaskId);
                }
                if (entry.getKey().equals("contextId") && upstreamContextId != null && entry.getValue().isTextual()
                        && entry.getValue().asText().equals(upstreamContextId)) {
                    ((ObjectNode) node).put("contextId", gatewayContextId);
                }
                rewrite(entry.getValue(), upstreamTaskId, upstreamContextId, gatewayTaskId, gatewayContextId);
            });
        }
        else if (node.isArray()) {
            node.forEach(child -> rewrite(child, upstreamTaskId, upstreamContextId, gatewayTaskId,
                    gatewayContextId));
        }
    }

    private Map<String, Object> mapValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isObject() ? Map.of() : objectMapper.convertValue(value, Map.class);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
