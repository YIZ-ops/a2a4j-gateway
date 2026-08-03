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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import io.github.a2ap.gateway.core.protocol.JsonRpcProtocolAdapter;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A2A 1.0 JSON-RPC HTTP entry point backed by the protocol-neutral forwarder. */
@RestController
public final class GatewayJsonRpcController {

    private final GatewayForwarder forwarder;

    private final JsonRpcProtocolAdapter adapter;

    private final GatewayProperties properties;

    private final ObjectMapper objectMapper;

    private final GatewayEventSseEncoder eventEncoder;

    private final GatewayAuditSink auditSink;

    private final GatewayMetrics metrics;

    private final GatewayAgentCatalogController catalog;

    /** Creates a JSON-RPC controller with no-op observability sinks. */
    public GatewayJsonRpcController(GatewayForwarder forwarder, JsonRpcProtocolAdapter adapter,
            GatewayProperties properties) {
        this(forwarder, adapter, properties, GatewayAuditSink.noop(), GatewayMetrics.noop());
    }

    /** Creates a JSON-RPC controller with replaceable observability sinks. */
    public GatewayJsonRpcController(GatewayForwarder forwarder, JsonRpcProtocolAdapter adapter,
            GatewayProperties properties, GatewayAuditSink auditSink, GatewayMetrics metrics) {
        this(forwarder, adapter, properties, auditSink, metrics, null);
    }

    /** Creates the controller with the gateway Card projector used for extended Cards. */
    public GatewayJsonRpcController(GatewayForwarder forwarder, JsonRpcProtocolAdapter adapter,
            GatewayProperties properties, GatewayAuditSink auditSink, GatewayMetrics metrics,
            GatewayAgentCatalogController catalog) {
        this.forwarder = forwarder;
        this.adapter = adapter;
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        this.eventEncoder = new GatewayEventSseEncoder(objectMapper);
        this.catalog = catalog;
        this.auditSink = auditSink == null ? GatewayAuditSink.noop() : auditSink;
        this.metrics = metrics == null ? GatewayMetrics.noop() : metrics;
    }

    /** Handles one non-streaming JSON-RPC request. */
    @PostMapping(value = { "/a2a", "/gateway/v1/a2a", "/gateway/v1/agents/{agentId}/a2a" },
            consumes = { "application/json", "application/a2a+json" }, produces = "application/json")
    public Mono<ResponseEntity<byte[]>> invoke(@PathVariable(required = false) String agentId,
            @RequestBody Mono<String> body, ServerWebExchange exchange) {
        return body.defaultIfEmpty("").flatMap(payload -> decode(agentId, payload, false, exchange)
                .flatMap(command -> {
                    if (isStreaming(command.operation())) {
                        return Mono.error(new GatewayHttpException(400, "INVALID_ARGUMENT",
                                "streaming JSON-RPC method requires an SSE Accept header"));
                    }
                    Instant started = Instant.now();
                    return forwarder.forward(command, routingContext(exchange))
                            .doOnSuccess(result -> recordAudit(command, result, null, exchange, started))
                            .doOnError(error -> recordAudit(command, null, error, exchange, started))
                            .flatMap(result -> projectResponse(result, command, exchange));
                }));
    }

    private Mono<ResponseEntity<byte[]>> projectResponse(GatewayResult result, GatewayCommand command,
            ServerWebExchange exchange) {
        if (catalog == null || command.operation() != GatewayCommand.Operation.GET_EXTENDED_AGENT_CARD
                || !result.success()) {
            return Mono.just(toResponse(toJsonRpcBinding(result, command)));
        }
        return catalog.projectExtendedCard(command.targetHint().agentId(), exchange, result.payload())
                .map(projected -> toResponse(toJsonRpcBinding(
                        new GatewayResult(true, projected, result.errorCode(), result.metadata()), command)))
                .onErrorResume(ignored -> Mono.just(toResponse(toJsonRpcBinding(result, command))));
    }

    /** Handles a streaming JSON-RPC request as server-sent events. */
    @PostMapping(value = { "/a2a", "/gateway/v1/a2a", "/gateway/v1/agents/{agentId}/a2a" },
            consumes = { "application/json", "application/a2a+json" }, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@PathVariable(required = false) String agentId,
            @RequestBody Mono<String> body, ServerWebExchange exchange) {
        return body.defaultIfEmpty("").flatMapMany(payload -> decode(agentId, payload, true, exchange)
                .flatMapMany(command -> {
                    if (!isStreaming(command.operation())) {
                        return Flux.error(new GatewayHttpException(400, "INVALID_ARGUMENT",
                                "JSON-RPC method is not stream-capable"));
                    }
                    return forwarder.stream(command, routingContext(exchange))
                            .map(event -> toSse(event, command))
                            .doOnSubscribe(ignored -> recordAudit(command, null, null, exchange, Instant.now()))
                            .doOnError(error -> recordAudit(command, null, error, exchange, Instant.now()));
                }));
    }

    private Mono<GatewayCommand> decode(String agentId, String payload, boolean streaming,
            ServerWebExchange exchange) {
        return principal(exchange).flatMap(principal -> {
            if (payload.getBytes(StandardCharsets.UTF_8).length > properties.getMaxRequestBytes()) {
                return Mono.error(new GatewayHttpException(413, "REQUEST_TOO_LARGE",
                        "JSON-RPC request exceeds configured size"));
            }
            Map<String, String> headers = headers(exchange);
            String queryVersion = exchange.getRequest().getQueryParams().getFirst(GatewayHeaders.A2A_VERSION);
            if (queryVersion != null && headers.keySet().stream()
                    .noneMatch(name -> name.equalsIgnoreCase(GatewayHeaders.A2A_VERSION))) {
                headers.put(GatewayHeaders.A2A_VERSION, queryVersion);
            }
            if (agentId != null && !agentId.isBlank()) {
                headers.put(GatewayHeaders.TARGET_AGENT, agentId);
            }
            try {
                com.fasterxml.jackson.databind.JsonNode request = objectMapper.readTree(payload);
                if (request != null && request.has("id") && !request.get("id").isNull()) {
                    exchange.getAttributes().put("a2a.jsonrpc.id",
                            objectMapper.convertValue(request.get("id"), Object.class));
                }
            }
            catch (JsonProcessingException | RuntimeException ignored) {
                // The adapter will produce the protocol error for malformed JSON.
            }
            ProtocolDescriptor protocol = streaming ? ProtocolDescriptor.jsonRpcStreaming()
                    : ProtocolDescriptor.jsonRpc();
            return adapter.decode(new InboundExchange(protocol, payload, headers, requestId(exchange), principal))
                    .doOnError(error -> metrics.record(GatewayMetricEvent.counter("gateway.protocol.errors",
                            Map.of("operation", "JSONRPC", "protocol", protocol.protocolBinding(),
                                    "error", error.getClass().getSimpleName()))));
        });
    }

    private ResponseEntity<byte[]> toResponse(GatewayResult result) {
        try {
            String body = result.payload() instanceof String value ? value
                    : objectMapper.writeValueAsString(result.payload());
            int status = result.metadata().get("statusCode") instanceof Number number ? number.intValue() : 200;
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                    .body(body.getBytes(StandardCharsets.UTF_8));
        }
        catch (JsonProcessingException ex) {
            throw new GatewayHttpException(502, "GATEWAY_SERIALIZATION_ERROR", "could not serialize JSON-RPC response");
        }
    }

    private ServerSentEvent<String> toSse(GatewayEvent event, GatewayCommand command) {
        SseEvent encoded = eventEncoder.encode(event);
        try {
            String body = "HTTP+JSON".equals(event.metadata().get("upstreamBinding"))
                    ? jsonRpcEnvelope(event.payload(), command.metadata().get("jsonRpcId"))
                    : objectMapper.writeValueAsString(event.payload());
            ServerSentEvent.Builder<String> builder = ServerSentEvent.builder(body).event(encoded.event());
            if (encoded.id() != null) {
                builder.id(encoded.id());
            }
            if (encoded.retry() != null) {
                builder.retry(Duration.ofMillis(encoded.retry()));
            }
            return builder.build();
        }
        catch (JsonProcessingException ex) {
            throw new GatewayHttpException(502, "GATEWAY_SERIALIZATION_ERROR", "could not serialize JSON-RPC event");
        }
    }

    private GatewayResult toJsonRpcBinding(GatewayResult result, GatewayCommand command) {
        if (!result.success() || !"HTTP+JSON".equals(result.metadata().get("upstreamBinding"))) {
            return result;
        }
        try {
            JsonNode payload = result.payload() instanceof String value ? objectMapper.readTree(value)
                    : objectMapper.valueToTree(result.payload());
            if (payload != null && payload.isObject() && payload.has("jsonrpc")) {
                return result;
            }
            return new GatewayResult(true, jsonRpcEnvelope(payload, command.metadata().get("jsonRpcId")),
                    result.errorCode(), result.metadata());
        }
        catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new GatewayHttpException(502, "GATEWAY_SERIALIZATION_ERROR",
                    "could not convert HTTP+JSON response to JSON-RPC");
        }
    }

    private String jsonRpcEnvelope(Object payload, Object id) throws JsonProcessingException {
        JsonNode node = payload instanceof JsonNode jsonNode ? jsonNode
                : payload instanceof String value ? objectMapper.readTree(value) : objectMapper.valueToTree(payload);
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.set("id", objectMapper.valueToTree(id));
        if (node != null && node.isObject() && node.has("error")) {
            envelope.set("error", node.get("error"));
        }
        else {
            envelope.set("result", node == null ? objectMapper.nullNode() : node);
        }
        return objectMapper.writeValueAsString(envelope);
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
            auditSink.record(new io.github.a2ap.gateway.api.model.GatewayAuditEvent(Instant.now(), requestId(exchange),
                    GatewayTraceContext.traceIdOr(exchange.getRequest().getHeaders().getFirst(GatewayHeaders.TRACEPARENT),
                            requestId(exchange)), command.tenantId(), command.principal().subject(),
                    command.operation().name(), agentId, text(command.targetHint().skillId()), "", outcome,
                    command.gatewayTaskId(), "", errorCode, Map.of()));
        }
        catch (RuntimeException ignored) {
            // Observability must never change data-plane behavior.
        }
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

    private String requestId(ServerWebExchange exchange) {
        return GatewayRequestIdResolver.resolve(exchange);
    }

    private boolean isStreaming(GatewayCommand.Operation operation) {
        return operation == GatewayCommand.Operation.SEND_STREAMING_MESSAGE
                || operation == GatewayCommand.Operation.SUBSCRIBE_TO_TASK;
    }

    private static String errorCode(Throwable error) {
        if (error instanceof io.github.a2ap.gateway.core.exception.GatewayForwardingException forwarding) {
            return forwarding.code().name();
        }
        return error == null ? "" : error.getClass().getSimpleName();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

}
