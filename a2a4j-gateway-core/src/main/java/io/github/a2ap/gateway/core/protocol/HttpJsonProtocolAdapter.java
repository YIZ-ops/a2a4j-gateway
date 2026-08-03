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
import io.github.a2ap.gateway.core.exception.VersionNotSupportedException;
import java.lang.reflect.Array;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
            String negotiatedVersion = requestedVersion == null ? "0.3" : requestedVersion;
            if (!A2AProtocolV1.VERSION.equals(negotiatedVersion)) {
                if (requestedVersion != null) {
                    throw new VersionNotSupportedException(negotiatedVersion);
                }
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
            validateRequest(operation, request, taskId);
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
                    exchange.protocol(), negotiatedVersion,
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
            String method;
            String path;
            Map<String, Object> query = Map.of();
            boolean streaming = false;
            switch (command.operation()) {
                case SEND_MESSAGE, SEND_STREAMING_MESSAGE -> {
                    method = "POST";
                    path = command.operation() == GatewayCommand.Operation.SEND_MESSAGE
                            ? "message:send" : "message:stream";
                    streaming = command.operation() == GatewayCommand.Operation.SEND_STREAMING_MESSAGE;
                    Map<String, Object> message = new LinkedHashMap<>(command.message());
                    String upstreamTaskId = command.metadata().containsKey("upstreamTaskId")
                            ? textValue(command.metadata().get("upstreamTaskId"))
                            : textValue(command.gatewayTaskId());
                    String upstreamContextId = command.metadata().containsKey("upstreamContextId")
                            ? textValue(command.metadata().get("upstreamContextId"))
                            : textValue(command.gatewayContextId());
                    if (upstreamTaskId != null) {
                        message.put("taskId", upstreamTaskId);
                    }
                    request.put("message", message);
                    if (!command.configuration().isEmpty()) {
                        request.put("configuration", command.configuration());
                    }
                    if (upstreamContextId != null) {
                        message.put("contextId", upstreamContextId);
                    }
                }
                case GET_TASK -> {
                    method = "GET";
                    path = "tasks/" + pathSegment(taskId(command));
                    query = queryFields(command.message(), Set.of("id", "taskId", "tenant"));
                }
                case LIST_TASKS -> {
                    method = "GET";
                    path = "tasks";
                    query = queryFields(command.message(), Set.of("id", "taskId", "tenant"));
                }
                case CANCEL_TASK -> {
                    method = "POST";
                    path = "tasks/" + pathSegment(taskId(command)) + ":cancel";
                    request.putAll(bodyFields(command.message(), Set.of("id", "taskId", "tenant")));
                }
                case SUBSCRIBE_TO_TASK -> {
                    method = "POST";
                    path = "tasks/" + pathSegment(taskId(command)) + ":subscribe";
                    streaming = true;
                    request.putAll(bodyFields(command.message(), Set.of("id", "taskId", "tenant")));
                }
                case CREATE_TASK_PUSH_NOTIFICATION_CONFIG -> {
                    method = "POST";
                    path = "tasks/" + pathSegment(taskId(command)) + "/pushNotificationConfigs";
                    request.putAll(bodyFields(command.message(), Set.of("taskId", "tenant")));
                }
                case GET_TASK_PUSH_NOTIFICATION_CONFIG -> {
                    method = "GET";
                    path = "tasks/" + pathSegment(taskId(command)) + "/pushNotificationConfigs/"
                            + pathSegment(configId(command));
                }
                case LIST_TASK_PUSH_NOTIFICATION_CONFIGS -> {
                    method = "GET";
                    path = "tasks/" + pathSegment(taskId(command)) + "/pushNotificationConfigs";
                    query = queryFields(command.message(), Set.of("id", "taskId", "tenant"));
                }
                case DELETE_TASK_PUSH_NOTIFICATION_CONFIG -> {
                    method = "DELETE";
                    path = "tasks/" + pathSegment(taskId(command)) + "/pushNotificationConfigs/"
                            + pathSegment(configId(command));
                }
                case GET_EXTENDED_AGENT_CARD -> {
                    method = "GET";
                    path = "extendedAgentCard";
                }
                default -> throw new IllegalArgumentException("unsupported HTTP+JSON operation: "
                        + command.operation());
            }
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
            if (agentInterface.upstreamTenant() != null && !agentInterface.upstreamTenant().isBlank()) {
                path = pathSegment(agentInterface.upstreamTenant()) + "/" + path;
            }
            return Mono.just(new OutboundRequest(streaming ? ProtocolDescriptor.httpJson(true) : descriptor(),
                    endpoint(agentInterface.endpointUrl(), path, query),
                    "GET".equals(method) || "DELETE".equals(method) ? "" : objectMapper.writeValueAsString(request),
                    headers, command.gatewayTaskId(), method));
        }
        catch (IllegalArgumentException ex) {
            return Mono.error(ex);
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
            if (body == null) {
                return Flux.just(new GatewayEvent(GatewayEvent.Type.TASK_COMPLETED, "unknown", null, null,
                        Instant.now(), metadata));
            }
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
        if (body == null || body.isBlank()) {
            return "{}";
        }
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
        if (body == null || body.isBlank()) {
            return new JsonRpcTaskReference(null, null);
        }
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
            case "CREATE_TASK_PUSH_NOTIFICATION_CONFIG", "CreateTaskPushNotificationConfig",
                    "tasks:pushNotificationConfigs:create" ->
                    GatewayCommand.Operation.CREATE_TASK_PUSH_NOTIFICATION_CONFIG;
            case "GET_TASK_PUSH_NOTIFICATION_CONFIG", "GetTaskPushNotificationConfig" ->
                GatewayCommand.Operation.GET_TASK_PUSH_NOTIFICATION_CONFIG;
            case "LIST_TASK_PUSH_NOTIFICATION_CONFIGS", "ListTaskPushNotificationConfigs" ->
                    GatewayCommand.Operation.LIST_TASK_PUSH_NOTIFICATION_CONFIGS;
            case "DELETE_TASK_PUSH_NOTIFICATION_CONFIG", "DeleteTaskPushNotificationConfig" ->
                    GatewayCommand.Operation.DELETE_TASK_PUSH_NOTIFICATION_CONFIG;
            case "GET_EXTENDED_AGENT_CARD", "GetExtendedAgentCard", "extendedAgentCard" ->
                GatewayCommand.Operation.GET_EXTENDED_AGENT_CARD;
            default -> throw new IllegalArgumentException("unsupported HTTP+JSON operation: " + value);
        };
    }

    private String taskId(GatewayCommand command) {
        String taskId = textValue(command.metadata().get("upstreamTaskId"));
        if (taskId == null) {
            taskId = textValue(command.gatewayTaskId());
        }
        if (taskId == null) {
            taskId = textValue(command.message().get("taskId"));
        }
        if (taskId == null) {
            taskId = textValue(command.message().get("id"));
        }
        if (taskId == null) {
            throw new IllegalArgumentException("HTTP+JSON " + command.operation() + " requires a task id");
        }
        return taskId;
    }

    private String configId(GatewayCommand command) {
        String configId = textValue(command.metadata().get("upstreamConfigId"));
        if (configId == null) {
            configId = textValue(command.metadata().get("configId"));
        }
        if (configId == null) {
            configId = textValue(command.message().get("configId"));
        }
        if (configId == null) {
            configId = textValue(command.message().get("id"));
        }
        if (configId == null) {
            throw new IllegalArgumentException("HTTP+JSON " + command.operation()
                    + " requires a push notification config id");
        }
        return configId;
    }

    private Map<String, Object> bodyFields(Map<String, Object> fields, Set<String> excluded) {
        Map<String, Object> body = new LinkedHashMap<>(fields);
        excluded.forEach(body::remove);
        return body;
    }

    private Map<String, Object> queryFields(Map<String, Object> fields, Set<String> excluded) {
        return bodyFields(fields, excluded);
    }

    private String endpoint(String endpointUrl, String path, Map<String, Object> query) {
        URI base = URI.create(endpointUrl);
        if (base.getFragment() != null) {
            throw new IllegalArgumentException("HTTP+JSON endpoint must not contain a URI fragment");
        }
        int queryIndex = endpointUrl.indexOf('?');
        String baseWithoutQuery = queryIndex < 0 ? endpointUrl : endpointUrl.substring(0, queryIndex);
        while (baseWithoutQuery.endsWith("/") && baseWithoutQuery.length() > base.getScheme().length() + 3) {
            baseWithoutQuery = baseWithoutQuery.substring(0, baseWithoutQuery.length() - 1);
        }
        StringBuilder result = new StringBuilder(baseWithoutQuery).append('/').append(path);
        String existingQuery = base.getRawQuery();
        String encodedQuery = encodeQuery(query);
        if (existingQuery != null && !existingQuery.isBlank()) {
            result.append('?').append(existingQuery);
            if (!encodedQuery.isBlank()) {
                result.append('&').append(encodedQuery);
            }
        }
        else if (!encodedQuery.isBlank()) {
            result.append('?').append(encodedQuery);
        }
        return result.toString();
    }

    private String encodeQuery(Map<String, Object> query) {
        StringBuilder encoded = new StringBuilder();
        query.forEach((name, value) -> appendQueryValue(encoded, name, value));
        return encoded.toString();
    }

    private void appendQueryValue(StringBuilder encoded, String name, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> appendQueryValue(encoded, name, item));
            return;
        }
        if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                appendQueryValue(encoded, name, Array.get(value, i));
            }
            return;
        }
        if (encoded.length() > 0) {
            encoded.append('&');
        }
        encoded.append(urlEncode(name)).append('=').append(urlEncode(String.valueOf(value)));
    }

    private String pathSegment(String value) {
        return urlEncode(value);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String textValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private void validateRequest(GatewayCommand.Operation operation, JsonNode request, String taskId) {
        if (isMessageOperation(operation) && (request == null || !request.has("message")
                || !request.get("message").isObject())) {
            throw new IllegalArgumentException("HTTP+JSON " + operation + " requires a message object");
        }
        if (requiresTask(operation) && (taskId == null || taskId.isBlank())) {
            throw new IllegalArgumentException("HTTP+JSON " + operation + " requires a task id");
        }
        if ((operation == GatewayCommand.Operation.GET_TASK_PUSH_NOTIFICATION_CONFIG
                || operation == GatewayCommand.Operation.DELETE_TASK_PUSH_NOTIFICATION_CONFIG)
                && firstText(text(request, "id"), text(request, "configId")) == null) {
            throw new IllegalArgumentException("HTTP+JSON " + operation + " requires a push notification config id");
        }
    }

    private boolean requiresTask(GatewayCommand.Operation operation) {
        return operation == GatewayCommand.Operation.GET_TASK || operation == GatewayCommand.Operation.CANCEL_TASK
                || operation == GatewayCommand.Operation.SUBSCRIBE_TO_TASK
                || operation == GatewayCommand.Operation.CREATE_TASK_PUSH_NOTIFICATION_CONFIG
                || operation == GatewayCommand.Operation.GET_TASK_PUSH_NOTIFICATION_CONFIG
                || operation == GatewayCommand.Operation.LIST_TASK_PUSH_NOTIFICATION_CONFIGS
                || operation == GatewayCommand.Operation.DELETE_TASK_PUSH_NOTIFICATION_CONFIG;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private JsonRpcTaskReference extractTaskReference(JsonNode body) {
        JsonNode node = toHttpNode(body);
        JsonNode task = node != null && node.has("task") ? node.get("task") : node;
        String taskId = text(task, "id");
        String contextId = text(task, "contextId");
        JsonNode update = streamingUpdate(node);
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
        JsonNode update = streamingUpdate(node);
        if (isArtifactUpdate(node, update)) {
            return GatewayEvent.Type.TASK_ARTIFACT;
        }
        if (isStatusUpdate(node, update)) {
            if (isTerminalStatus(update.get("status"))) {
                return GatewayEvent.Type.TASK_COMPLETED;
            }
            return GatewayEvent.Type.TASK_STATUS;
        }
        JsonNode task = node != null && node.has("task") ? node.get("task") : null;
        if (task != null && task.isObject() && task.has("status")) {
            return isTerminalStatus(task.get("status")) ? GatewayEvent.Type.TASK_COMPLETED
                    : GatewayEvent.Type.TASK_STATUS;
        }
        return terminal || taskId == null ? (taskId == null ? GatewayEvent.Type.TASK_STATUS
                : GatewayEvent.Type.TASK_COMPLETED) : GatewayEvent.Type.TASK_STATUS;
    }

    /** Returns the event payload from an A2A 1.0 stream wrapper. */
    private JsonNode streamingUpdate(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        if (node.has("statusUpdate")) {
            return node.get("statusUpdate");
        }
        if (node.has("artifactUpdate")) {
            return node.get("artifactUpdate");
        }
        return null;
    }

    private boolean isArtifactUpdate(JsonNode node, JsonNode update) {
        return node != null && node.has("artifactUpdate");
    }

    private boolean isStatusUpdate(JsonNode node, JsonNode update) {
        return node != null && node.has("statusUpdate");
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
