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

package io.github.a2ap.gateway.spring.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.a2ap.gateway.api.GatewayHeaders;
import io.github.a2ap.gateway.api.GatewayTraceContext;
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.GatewayEvent;
import io.github.a2ap.gateway.api.model.GatewayResult;
import io.github.a2ap.gateway.api.model.InboundExchange;
import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.api.model.ProtocolDescriptor;
import io.github.a2ap.gateway.api.model.RoutingContext;
import io.github.a2ap.gateway.api.spi.GatewayAuditSink;
import io.github.a2ap.gateway.api.spi.GatewayMetrics;
import io.github.a2ap.gateway.api.model.GatewayMetricEvent;
import io.github.a2ap.gateway.spring.autoconfigure.GatewayProperties;
import io.github.a2ap.gateway.core.forwarding.GatewayForwarder;
import io.github.a2ap.gateway.core.protocol.HttpJsonProtocolAdapter;
import io.github.a2ap.gateway.core.transport.GatewayEventSseEncoder;
import io.github.a2ap.gateway.core.transport.SseEvent;
import io.github.a2ap.gateway.spring.exception.GatewayHttpException;
import io.github.a2ap.gateway.spring.security.GatewayAuthenticationToken;
import io.github.a2ap.gateway.spring.support.GatewayRequestIdResolver;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A2A 1.0 HTTP+JSON data-plane endpoints backed by the protocol-neutral forwarder. */
@RestController
public final class GatewayHttpJsonController {

    private static final MediaType A2A_JSON = MediaType.parseMediaType("application/a2a+json");

    private final GatewayForwarder forwarder;

    private final HttpJsonProtocolAdapter adapter;

    private final GatewayProperties properties;

    private final ObjectMapper objectMapper;

    private final GatewayEventSseEncoder eventEncoder;

    private final GatewayAuditSink auditSink;

    private final GatewayMetrics metrics;

    private final GatewayAgentCatalogController catalog;

    /** Creates the HTTP+JSON controller. */
    public GatewayHttpJsonController(GatewayForwarder forwarder, HttpJsonProtocolAdapter adapter,
            GatewayProperties properties) {
        this(forwarder, adapter, properties, GatewayAuditSink.noop());
    }

    /** Creates the controller with a replaceable structured audit sink. */
    public GatewayHttpJsonController(GatewayForwarder forwarder, HttpJsonProtocolAdapter adapter,
            GatewayProperties properties, GatewayAuditSink auditSink) {
        this(forwarder, adapter, properties, auditSink, GatewayMetrics.noop());
    }

    /** Creates the controller with audit and protocol-error metric sinks. */
    public GatewayHttpJsonController(GatewayForwarder forwarder, HttpJsonProtocolAdapter adapter,
            GatewayProperties properties, GatewayAuditSink auditSink, GatewayMetrics metrics) {
        this(forwarder, adapter, properties, auditSink, metrics, null);
    }

    /** Creates the controller with the gateway Card projector used for extended Cards. */
    public GatewayHttpJsonController(GatewayForwarder forwarder, HttpJsonProtocolAdapter adapter,
            GatewayProperties properties, GatewayAuditSink auditSink, GatewayMetrics metrics,
            GatewayAgentCatalogController catalog) {
        this.forwarder = forwarder;
        this.adapter = adapter;
        this.properties = properties;
        this.auditSink = auditSink == null ? GatewayAuditSink.noop() : auditSink;
        this.metrics = metrics == null ? GatewayMetrics.noop() : metrics;
        this.objectMapper = new ObjectMapper();
        this.eventEncoder = new GatewayEventSseEncoder(objectMapper);
        this.catalog = catalog;
    }

    /** Sends one non-streaming A2A message. */
    @PostMapping(value = { "/message:send", "/gateway/v1/message:send",
            "/gateway/v1/agents/{agentId}/message:send" }, consumes = { "application/json", "application/a2a+json" },
            produces = { "application/json", "application/a2a+json" })
    public Mono<ResponseEntity<byte[]>> send(@PathVariable(required = false) String agentId,
            @RequestBody(required = false) Mono<String> body, ServerWebExchange exchange) {
        return bodyText(body).flatMap(payload -> dispatch(agentId, null, GatewayCommand.Operation.SEND_MESSAGE, payload,
                exchange));
    }

    /** Starts an A2A server-sent event stream. */
    @PostMapping(value = { "/message:stream", "/gateway/v1/message:stream",
            "/gateway/v1/agents/{agentId}/message:stream" }, consumes = { "application/json", "application/a2a+json" },
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@PathVariable(required = false) String agentId,
            @RequestBody(required = false) Mono<String> body, ServerWebExchange exchange) {
        return bodyText(body).flatMapMany(payload -> decode(agentId, null, GatewayCommand.Operation.SEND_STREAMING_MESSAGE,
                payload, exchange).flatMapMany(command -> forwarder.stream(command, routingContext(exchange))
                        .map(this::toSse)
                        .doOnSubscribe(ignored -> recordAudit(command, null, null, exchange, Instant.now()))
                        .doOnError(error -> recordAudit(command, null, error, exchange, Instant.now()))));
    }

    /** Retrieves one task through its gateway task identifier. */
    @GetMapping(value = { "/tasks/{taskId}", "/gateway/v1/tasks/{taskId}",
            "/gateway/v1/agents/{agentId}/tasks/{taskId}" }, produces = { "application/json", "application/a2a+json" })
    public Mono<ResponseEntity<byte[]>> getTask(@PathVariable(required = false) String agentId,
            @PathVariable String taskId, ServerWebExchange exchange) {
        return dispatch(agentId, taskId, GatewayCommand.Operation.GET_TASK, "", exchange);
    }

    /** Lists tasks visible to the authenticated tenant and principal. */
    @GetMapping(value = { "/tasks", "/gateway/v1/tasks", "/gateway/v1/agents/{agentId}/tasks" },
            produces = { "application/json", "application/a2a+json" })
    public Mono<ResponseEntity<byte[]>> listTasks(@PathVariable(required = false) String agentId,
            @RequestParam Map<String, String> query, ServerWebExchange exchange) {
        try {
            return dispatch(agentId, null, GatewayCommand.Operation.LIST_TASKS, objectMapper.writeValueAsString(query),
                    exchange);
        }
        catch (JsonProcessingException ex) {
            return Mono.error(new GatewayHttpException(400, "INVALID_ARGUMENT", "invalid task query"));
        }
    }

    /** Cancels one task. */
    @PostMapping(value = { "/tasks/{taskId}:cancel", "/gateway/v1/tasks/{taskId}:cancel",
            "/gateway/v1/agents/{agentId}/tasks/{taskId}:cancel" }, consumes = { "application/json",
                    "application/a2a+json" }, produces = { "application/json", "application/a2a+json" })
    public Mono<ResponseEntity<byte[]>> cancel(@PathVariable(required = false) String agentId,
            @PathVariable String taskId, @RequestBody(required = false) Mono<String> body, ServerWebExchange exchange) {
        return bodyText(body).flatMap(payload -> dispatch(agentId, taskId, GatewayCommand.Operation.CANCEL_TASK, payload,
                exchange));
    }

    /** Resubscribes to one task's upstream stream. */
    @PostMapping(value = { "/tasks/{taskId}:subscribe", "/gateway/v1/tasks/{taskId}:subscribe",
            "/gateway/v1/agents/{agentId}/tasks/{taskId}:subscribe" }, consumes = { "application/json",
                    "application/a2a+json" }, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> subscribe(@PathVariable(required = false) String agentId,
            @PathVariable String taskId, @RequestBody(required = false) Mono<String> body, ServerWebExchange exchange) {
        return bodyText(body).flatMapMany(payload -> decode(agentId, taskId, GatewayCommand.Operation.SUBSCRIBE_TO_TASK,
                payload, exchange).flatMapMany(command -> forwarder.stream(command, routingContext(exchange))
                        .map(this::toSse)
                        .doOnSubscribe(ignored -> recordAudit(command, null, null, exchange, Instant.now()))
                        .doOnError(error -> recordAudit(command, null, error, exchange, Instant.now()))));
    }

    /** Creates a push notification configuration for a gateway task. */
    @PostMapping(value = { "/tasks/{taskId}/pushNotificationConfigs",
            "/gateway/v1/tasks/{taskId}/pushNotificationConfigs",
            "/gateway/v1/agents/{agentId}/tasks/{taskId}/pushNotificationConfigs" },
            consumes = { "application/json", "application/a2a+json" },
            produces = { "application/json", "application/a2a+json" })
    public Mono<ResponseEntity<byte[]>> createPush(@PathVariable(required = false) String agentId,
            @PathVariable String taskId, @RequestBody(required = false) Mono<String> body,
            ServerWebExchange exchange) {
        return bodyText(body).flatMap(payload -> dispatch(agentId, taskId,
                GatewayCommand.Operation.CREATE_TASK_PUSH_NOTIFICATION_CONFIG, payload, exchange));
    }

    /** Retrieves one push notification configuration for a gateway task. */
    @GetMapping(value = { "/tasks/{taskId}/pushNotificationConfigs/{configId}",
            "/gateway/v1/tasks/{taskId}/pushNotificationConfigs/{configId}",
            "/gateway/v1/agents/{agentId}/tasks/{taskId}/pushNotificationConfigs/{configId}" },
            produces = { "application/json", "application/a2a+json" })
    public Mono<ResponseEntity<byte[]>> getPush(@PathVariable(required = false) String agentId,
            @PathVariable String taskId, @PathVariable String configId, ServerWebExchange exchange) {
        return dispatch(agentId, taskId, GatewayCommand.Operation.GET_TASK_PUSH_NOTIFICATION_CONFIG,
                configBody(configId), exchange);
    }

    /** Lists push notification configurations for a gateway task. */
    @GetMapping(value = { "/tasks/{taskId}/pushNotificationConfigs",
            "/gateway/v1/tasks/{taskId}/pushNotificationConfigs",
            "/gateway/v1/agents/{agentId}/tasks/{taskId}/pushNotificationConfigs" },
            produces = { "application/json", "application/a2a+json" })
    public Mono<ResponseEntity<byte[]>> listPush(@PathVariable(required = false) String agentId,
            @PathVariable String taskId, @RequestParam Map<String, String> query, ServerWebExchange exchange) {
        try {
            return dispatch(agentId, taskId, GatewayCommand.Operation.LIST_TASK_PUSH_NOTIFICATION_CONFIGS,
                    objectMapper.writeValueAsString(query), exchange);
        }
        catch (JsonProcessingException ex) {
            return Mono.error(new GatewayHttpException(400, "INVALID_ARGUMENT", "invalid push notification query"));
        }
    }

    /** Deletes one push notification configuration for a gateway task. */
    @DeleteMapping(value = { "/tasks/{taskId}/pushNotificationConfigs/{configId}",
            "/gateway/v1/tasks/{taskId}/pushNotificationConfigs/{configId}",
            "/gateway/v1/agents/{agentId}/tasks/{taskId}/pushNotificationConfigs/{configId}" },
            produces = { "application/json", "application/a2a+json" })
    public Mono<ResponseEntity<byte[]>> deletePush(@PathVariable(required = false) String agentId,
            @PathVariable String taskId, @PathVariable String configId, ServerWebExchange exchange) {
        return dispatch(agentId, taskId, GatewayCommand.Operation.DELETE_TASK_PUSH_NOTIFICATION_CONFIG,
                configBody(configId), exchange);
    }

    /** Retrieves the authenticated extended Agent Card through HTTP+JSON. */
    @GetMapping(value = { "/extendedAgentCard", "/gateway/v1/extendedAgentCard",
            "/gateway/v1/agents/{agentId}/extendedAgentCard" },
            produces = { "application/json", "application/a2a+json" })
    public Mono<ResponseEntity<byte[]>> extendedCard(@PathVariable(required = false) String agentId,
            ServerWebExchange exchange) {
        return dispatch(agentId, null, GatewayCommand.Operation.GET_EXTENDED_AGENT_CARD, "", exchange);
    }

    private Mono<ResponseEntity<byte[]>> dispatch(String agentId, String taskId, GatewayCommand.Operation operation,
            String body, ServerWebExchange exchange) {
        return decode(agentId, taskId, operation, body, exchange)
                .flatMap(command -> {
                    Instant started = Instant.now();
                    return forwarder.forward(command, routingContext(exchange))
                            .doOnSuccess(result -> recordAudit(command, result, null, exchange, started))
                            .doOnError(error -> recordAudit(command, null, error, exchange, started))
                            .flatMap(result -> projectResponse(result, command, exchange));
                });
    }

    private Mono<ResponseEntity<byte[]>> projectResponse(GatewayResult result, GatewayCommand command,
            ServerWebExchange exchange) {
        if (catalog == null || command.operation() != GatewayCommand.Operation.GET_EXTENDED_AGENT_CARD) {
            return Mono.just(toResponse(result));
        }
        if (!result.success()) {
            return Mono.just(toResponse(result));
        }
        return catalog.projectExtendedCard(command.targetHint().agentId(), exchange, result.payload())
                .map(projected -> toResponse(new GatewayResult(true, projected, result.errorCode(), result.metadata())))
                .onErrorResume(ignored -> Mono.just(toResponse(result)));
    }

    private Mono<GatewayCommand> decode(String agentId, String taskId, GatewayCommand.Operation operation, String body,
            ServerWebExchange exchange) {
        return principal(exchange).flatMap(principal -> {
            if (body.getBytes(StandardCharsets.UTF_8).length > properties.getMaxRequestBytes()) {
                return Mono.error(new GatewayHttpException(413, "REQUEST_TOO_LARGE",
                        "HTTP+JSON request exceeds configured size"));
            }
            String requestId = requestId(exchange);
            Map<String, String> headers = headers(exchange);
            String queryVersion = exchange.getRequest().getQueryParams().getFirst(GatewayHeaders.A2A_VERSION);
            if (queryVersion != null && headers.keySet().stream()
                    .noneMatch(name -> name.equalsIgnoreCase(GatewayHeaders.A2A_VERSION))) {
                headers.put(GatewayHeaders.A2A_VERSION, queryVersion);
            }
            headers.put(GatewayHeaders.GATEWAY_OPERATION, operation.name());
            if (agentId != null && !agentId.isBlank()) {
                headers.put(GatewayHeaders.TARGET_AGENT, agentId);
            }
            if (taskId != null && !taskId.isBlank()) {
                headers.put(GatewayHeaders.GATEWAY_TASK_ID, taskId);
            }
            ProtocolDescriptor protocol = operation == GatewayCommand.Operation.SEND_STREAMING_MESSAGE
                    || operation == GatewayCommand.Operation.SUBSCRIBE_TO_TASK
                            ? ProtocolDescriptor.httpJson(true) : ProtocolDescriptor.httpJson(false);
            return adapter.decode(new InboundExchange(protocol, body, headers, requestId, principal))
                    .doOnError(error -> metrics.record(GatewayMetricEvent.counter("gateway.protocol.errors",
                            Map.of("operation", operation.name(), "protocol", protocol.protocolBinding(),
                                    "error", protocolError(error)))));
        });
    }

    private ResponseEntity<byte[]> toResponse(GatewayResult result) {
        String body = result.payload() instanceof String value ? adapter.toHttpJson(value)
                : adapter.toHttpJson(result.payload());
        int status = result.metadata().get("statusCode") instanceof Number number ? number.intValue() : 200;
        return ResponseEntity.status(status).contentType(A2A_JSON).body(body.getBytes(StandardCharsets.UTF_8));
    }

    private ServerSentEvent<String> toSse(GatewayEvent event) {
        SseEvent encoded = eventEncoder.encode(event);
        String data = adapter.toHttpJson(event.payload());
        ServerSentEvent.Builder<String> builder = ServerSentEvent.builder(data).event(encoded.event());
        if (encoded.id() != null) {
            builder.id(encoded.id());
        }
        if (encoded.retry() != null) {
            builder.retry(Duration.ofMillis(encoded.retry()));
        }
        return builder.build();
    }

    private Mono<String> bodyText(Mono<String> body) {
        return body == null ? Mono.just("") : body.defaultIfEmpty("");
    }

    private String configBody(String configId) {
        try {
            return objectMapper.writeValueAsString(Map.of("id", configId));
        }
        catch (JsonProcessingException ex) {
            throw new GatewayHttpException(400, "INVALID_ARGUMENT", "invalid push notification config id");
        }
    }

    private Mono<PrincipalContext> principal(ServerWebExchange exchange) {
        return exchange.getPrincipal().flatMap(value -> value instanceof GatewayAuthenticationToken token
                ? Mono.just(token.principalContext()) : Mono.<PrincipalContext>empty())
                .switchIfEmpty(ReactiveSecurityContextHolder.getContext().map(context -> context.getAuthentication())
                        .filter(GatewayAuthenticationToken.class::isInstance)
                        .map(GatewayAuthenticationToken.class::cast).map(GatewayAuthenticationToken::principalContext))
                .switchIfEmpty(Mono.error(new GatewayHttpException(401, "UNAUTHENTICATED",
                        "authenticated principal is required")));
    }

    private RoutingContext routingContext(ServerWebExchange exchange) {
        String requestId = requestId(exchange);
        String traceparent = exchange.getRequest().getHeaders().getFirst(GatewayHeaders.TRACEPARENT);
        String traceId = GatewayTraceContext.traceIdOr(traceparent,
                exchange.getRequest().getHeaders().getFirst("X-Trace-Id"));
        if (traceId == null || traceId.isBlank()) {
            traceId = requestId;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (GatewayTraceContext.parse(traceparent).isPresent()) {
            metadata.put(GatewayHeaders.TRACEPARENT, traceparent);
            String tracestate = exchange.getRequest().getHeaders().getFirst(GatewayHeaders.TRACESTATE);
            if (tracestate != null && !tracestate.isBlank()) {
                metadata.put(GatewayHeaders.TRACESTATE, tracestate);
            }
        }
        return new RoutingContext(requestId, traceId, Instant.now().plus(properties.getResponseTimeout()), metadata);
    }

    private void recordAudit(GatewayCommand command, GatewayResult result, Throwable error,
            ServerWebExchange exchange, Instant started) {
        try {
            String errorCode = result == null ? errorCode(error) : result.errorCode();
            String outcome = error == null && (result == null || result.success()) ? "SUCCESS" : "ERROR";
            String agentId = result == null ? "" : text(result.metadata().get("agentId"));
            auditSink.record(new io.github.a2ap.gateway.api.model.GatewayAuditEvent(Instant.now(),
                    requestId(exchange), GatewayTraceContext.traceIdOr(
                            exchange.getRequest().getHeaders().getFirst(GatewayHeaders.TRACEPARENT), requestId(exchange)),
                    command.tenantId(), command.principal().subject(), command.operation().name(), agentId,
                    text(command.targetHint().skillId()), "", outcome, command.gatewayTaskId(),
                    latencyBucket(Duration.between(started, Instant.now()).toMillis()), errorCode, Map.of()));
        }
        catch (RuntimeException ignored) {
            // Observability must never change data-plane behavior.
        }
    }

    private static String errorCode(Throwable error) {
        if (error instanceof io.github.a2ap.gateway.core.exception.GatewayForwardingException forwarding) {
            return forwarding.code().name();
        }
        return error == null ? "" : error.getClass().getSimpleName();
    }

    private static String protocolError(Throwable error) {
        if (error instanceof GatewayHttpException http) {
            return http.code();
        }
        return error == null ? "UNKNOWN" : error.getClass().getSimpleName();
    }

    private static String latencyBucket(long millis) {
        return millis < 10 ? "<10ms" : millis < 100 ? "10-100ms" : millis < 1000 ? "100ms-1s" : ">=1s";
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private String requestId(ServerWebExchange exchange) {
        return GatewayRequestIdResolver.resolve(exchange);
    }

    private Map<String, String> headers(ServerWebExchange exchange) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequest().getHeaders().forEach((name, values) -> {
            if (values != null && !values.isEmpty() && values.get(0) != null) {
                headers.put(name, values.get(0));
            }
        });
        return headers;
    }

}
