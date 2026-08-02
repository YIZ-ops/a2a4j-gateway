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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.GatewayEvent;
import io.github.a2ap.gateway.api.model.GatewayResult;
import io.github.a2ap.gateway.api.model.GatewayMetricEvent;
import io.github.a2ap.gateway.api.model.RouteDecision;
import io.github.a2ap.gateway.api.model.RoutingContext;
import io.github.a2ap.gateway.api.model.TaskRoute;
import io.github.a2ap.gateway.api.model.TaskRouteQuery;
import io.github.a2ap.gateway.api.model.OutboundCredentials;
import io.github.a2ap.gateway.api.model.OutboundRequest;
import io.github.a2ap.gateway.api.model.OutboundResponse;
import io.github.a2ap.gateway.api.spi.AgentLoadBalancer;
import io.github.a2ap.gateway.api.spi.AgentInterfaceSelector;
import io.github.a2ap.gateway.api.spi.AgentRegistry;
import io.github.a2ap.gateway.api.spi.AgentTransport;
import io.github.a2ap.gateway.api.spi.CredentialProvider;
import io.github.a2ap.gateway.api.spi.IdempotencyStore;
import io.github.a2ap.gateway.api.spi.GatewayMetrics;
import io.github.a2ap.gateway.api.spi.ProtocolAdapter;
import io.github.a2ap.gateway.api.spi.RouteResolver;
import io.github.a2ap.gateway.api.spi.TaskRouteStore;
import io.github.a2ap.gateway.core.protocol.HttpJsonProtocolAdapter;
import io.github.a2ap.gateway.core.protocol.JsonRpcProtocolAdapter;
import io.github.a2ap.gateway.core.protocol.JsonRpcTaskReference;
import io.github.a2ap.gateway.core.protocol.VersionNotSupportedException;
import io.github.a2ap.gateway.core.routing.DefaultAgentInterfaceSelector;
import io.github.a2ap.gateway.core.routing.WeightedLeastActiveLoadBalancer;
import io.github.a2ap.gateway.core.transport.AgentTransportException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/** Coordinates routing, instance selection, credentials, protocol conversion and upstream exchange. */
public final class GatewayForwarder {

    private final AgentRegistry agentRegistry;

    private final RouteResolver routeResolver;

    private final AgentLoadBalancer loadBalancer;

    private final Map<String, ProtocolAdapter> protocolAdapters;

    private final AgentInterfaceSelector interfaceSelector;

    private final AgentTransport transport;

    private final CredentialProvider credentialProvider;

    private final TaskRouteStore taskRouteStore;

    private final IdempotencyStore idempotencyStore;

    private final Duration routeTtl;

    private final ObjectMapper objectMapper;

    private final Duration streamIdleTimeout;

    private final TenantStreamLimiter streamLimiter;

    private final GatewayMetrics metrics;

    /** Creates a forwarder with the default twenty-four-hour task route retention. */
    public GatewayForwarder(AgentRegistry agentRegistry, RouteResolver routeResolver,
            AgentLoadBalancer loadBalancer, ProtocolAdapter protocolAdapter, AgentTransport transport,
            CredentialProvider credentialProvider, TaskRouteStore taskRouteStore,
            IdempotencyStore idempotencyStore) {
        this(agentRegistry, routeResolver, loadBalancer, protocolAdapter, transport, credentialProvider,
                taskRouteStore, idempotencyStore, Duration.ofHours(24), Duration.ofSeconds(30),
                new TenantStreamLimiter(200));
    }

    /** Creates a forwarder with explicit task-route retention. */
    public GatewayForwarder(AgentRegistry agentRegistry, RouteResolver routeResolver,
            AgentLoadBalancer loadBalancer, ProtocolAdapter protocolAdapter, AgentTransport transport,
            CredentialProvider credentialProvider, TaskRouteStore taskRouteStore,
            IdempotencyStore idempotencyStore, Duration routeTtl) {
        this(agentRegistry, routeResolver, loadBalancer, protocolAdapter, transport, credentialProvider,
                taskRouteStore, idempotencyStore, routeTtl, Duration.ofSeconds(30), new TenantStreamLimiter(200));
    }

    /** Creates a forwarder with explicit stream idle timeout and per-tenant quota. */
    public GatewayForwarder(AgentRegistry agentRegistry, RouteResolver routeResolver,
            AgentLoadBalancer loadBalancer, ProtocolAdapter protocolAdapter, AgentTransport transport,
            CredentialProvider credentialProvider, TaskRouteStore taskRouteStore,
            IdempotencyStore idempotencyStore, Duration routeTtl, Duration streamIdleTimeout,
            TenantStreamLimiter streamLimiter) {
        this(agentRegistry, routeResolver, loadBalancer, protocolAdapter, transport, credentialProvider,
                taskRouteStore, idempotencyStore, routeTtl, streamIdleTimeout, streamLimiter, GatewayMetrics.noop());
    }

    /** Creates a forwarder with a replaceable low-cardinality metrics sink. */
    public GatewayForwarder(AgentRegistry agentRegistry, RouteResolver routeResolver,
            AgentLoadBalancer loadBalancer, ProtocolAdapter protocolAdapter, AgentTransport transport,
            CredentialProvider credentialProvider, TaskRouteStore taskRouteStore,
            IdempotencyStore idempotencyStore, Duration routeTtl, Duration streamIdleTimeout,
            TenantStreamLimiter streamLimiter, GatewayMetrics metrics) {
        this(agentRegistry, routeResolver, loadBalancer, protocolAdapter, transport, credentialProvider, taskRouteStore,
                idempotencyStore, routeTtl, streamIdleTimeout, streamLimiter, metrics, singleAdapter(protocolAdapter),
                new DefaultAgentInterfaceSelector());
    }

    /** Creates a forwarder with multiple outbound bindings and a replaceable interface selector. */
    public GatewayForwarder(AgentRegistry agentRegistry, RouteResolver routeResolver,
            AgentLoadBalancer loadBalancer, ProtocolAdapter protocolAdapter, AgentTransport transport,
            CredentialProvider credentialProvider, TaskRouteStore taskRouteStore,
            IdempotencyStore idempotencyStore, Duration routeTtl, Duration streamIdleTimeout,
            TenantStreamLimiter streamLimiter, GatewayMetrics metrics, Map<String, ProtocolAdapter> protocolAdapters,
            AgentInterfaceSelector interfaceSelector) {
        this.agentRegistry = Objects.requireNonNull(agentRegistry, "agentRegistry");
        this.routeResolver = Objects.requireNonNull(routeResolver, "routeResolver");
        this.loadBalancer = Objects.requireNonNull(loadBalancer, "loadBalancer");
        Objects.requireNonNull(protocolAdapter, "protocolAdapter");
        if (protocolAdapters == null || protocolAdapters.isEmpty()) {
            throw new IllegalArgumentException("protocolAdapters must not be empty");
        }
        this.protocolAdapters = Map.copyOf(protocolAdapters);
        this.interfaceSelector = Objects.requireNonNull(interfaceSelector, "interfaceSelector");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.credentialProvider = Objects.requireNonNull(credentialProvider, "credentialProvider");
        this.taskRouteStore = Objects.requireNonNull(taskRouteStore, "taskRouteStore");
        this.idempotencyStore = idempotencyStore;
        if (routeTtl == null || routeTtl.isZero() || routeTtl.isNegative()) {
            throw new IllegalArgumentException("routeTtl must be positive");
        }
        this.routeTtl = routeTtl;
        if (streamIdleTimeout == null || streamIdleTimeout.isZero() || streamIdleTimeout.isNegative()) {
            throw new IllegalArgumentException("streamIdleTimeout must be positive");
        }
        this.streamIdleTimeout = streamIdleTimeout;
        this.streamLimiter = Objects.requireNonNull(streamLimiter, "streamLimiter");
        this.metrics = metrics == null ? GatewayMetrics.noop() : metrics;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    private static Map<String, ProtocolAdapter> singleAdapter(ProtocolAdapter adapter) {
        Objects.requireNonNull(adapter, "protocolAdapter");
        return Map.of(adapter.descriptor().protocolBinding(), adapter);
    }

    /** Forwards one decoded command and returns the protocol-ready response payload. */
    public Mono<GatewayResult> forward(GatewayCommand command, RoutingContext context) {
        Instant started = Instant.now();
        return forwardInternal(command, context)
                .doOnSuccess(result -> recordRequest(command, result == null ? "EMPTY" : "SUCCESS", null, started))
                .doOnError(error -> recordRequest(command, "ERROR", error, started));
    }

    private Mono<GatewayResult> forwardInternal(GatewayCommand command, RoutingContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        if (!io.github.a2ap.core.protocol.v1.A2AProtocolV1.VERSION.equals(command.requestedProtocolVersion())) {
            return Mono.error(new VersionNotSupportedException(command.requestedProtocolVersion()));
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            return execute(command, context);
        }
        if (idempotencyStore == null) {
            return Mono.error(new GatewayForwardingException(GatewayForwardingException.Code.INVALID_REQUEST,
                    "idempotency is not configured"));
        }
        String requestHash = requestHash(command);
        return idempotencyStore.find(command.tenantId(), command.idempotencyKey())
                .flatMap(existing -> replayOrReject(existing, requestHash))
                .switchIfEmpty(Mono.defer(() -> idempotencyStore
                        .begin(command.tenantId(), command.idempotencyKey(), requestHash)
                        .flatMap(record -> {
                            if (record.state() != io.github.a2ap.gateway.api.model.IdempotencyRecord.State.IN_FLIGHT) {
                                return replayOrReject(record, requestHash);
                            }
                            return execute(command, context)
                                    .flatMap(result -> idempotencyStore.complete(command.tenantId(),
                                            command.idempotencyKey(), result).thenReturn(result))
                                    .onErrorResume(error -> unknownOutcome(error)
                                            ? idempotencyStore.markOutcomeUnknown(command.tenantId(),
                                                    command.idempotencyKey()).then(Mono.error(error))
                                            : Mono.error(error));
                        })));
    }

    /**
     * Streams an A2A {@code SendStreamingMessage} or {@code SubscribeToTask} operation.
     * Events are decoded and normalized one at a time; the upstream response is never
     * aggregated by the forwarding layer.
     */
    public Flux<GatewayEvent> stream(GatewayCommand command, RoutingContext context) {
        Instant started = Instant.now();
        return streamInternal(command, context)
                .doOnSubscribe(ignored -> metrics.record(GatewayMetricEvent.counter("gateway.streams.started",
                        metricTags(command, "STARTED", null))))
                .doFinally(signal -> metrics.record(GatewayMetricEvent.timer("gateway.stream.duration",
                        Duration.between(started, Instant.now()).toMillis(),
                        metricTags(command, signal == SignalType.ON_ERROR ? "ERROR" : "COMPLETED", null))));
    }

    private Flux<GatewayEvent> streamInternal(GatewayCommand command, RoutingContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        if (!io.github.a2ap.core.protocol.v1.A2AProtocolV1.VERSION.equals(command.requestedProtocolVersion())) {
            return Flux.error(new VersionNotSupportedException(command.requestedProtocolVersion()));
        }
        if (command.operation() != GatewayCommand.Operation.SEND_STREAMING_MESSAGE
                && command.operation() != GatewayCommand.Operation.SUBSCRIBE_TO_TASK) {
            return Flux.error(new GatewayForwardingException(GatewayForwardingException.Code.INVALID_REQUEST,
                    "command operation is not stream-capable"));
        }
        return Flux.defer(() -> {
            if (!streamLimiter.tryAcquire(command.tenantId())) {
                return Flux.error(new GatewayForwardingException(GatewayForwardingException.Code.RATE_LIMITED,
                        "tenant stream quota has been exceeded"));
            }
            return routeResolver.resolve(command, context)
                    .flatMapMany(decision -> agentRegistry.get(command.tenantId(), decision.agentId())
                            .switchIfEmpty(Mono.error(new GatewayForwardingException(
                                    GatewayForwardingException.Code.INTERFACE_UNAVAILABLE,
                                    "selected Agent is no longer available")))
                            .flatMapMany(agent -> validateCapabilities(command, agent)
                                    .flatMapMany(ignored -> {
                                        Mono<TaskRoute> affinity = affinityRoute(command);
                                        if (command.operation() == GatewayCommand.Operation.SUBSCRIBE_TO_TASK) {
                                            return affinity.switchIfEmpty(Mono.error(new GatewayForwardingException(
                                                    GatewayForwardingException.Code.INTERFACE_UNAVAILABLE,
                                                    "task route was not found"))).flatMapMany(route -> {
                                                        if (route.terminal()) {
                                                            return Flux.error(new GatewayForwardingException(
                                                                    GatewayForwardingException.Code.UNSUPPORTED_OPERATION,
                                                                    "cannot subscribe to a task that is not active"));
                                                        }
                                                        return loadBalancer.choosePinned(agent, route.instanceId(), command,
                                                                context).flatMapMany(instance -> streamToInstance(command,
                                                                        context, decision, agent, instance, route));
                                                    });
                                        }
                                        return affinity.flatMapMany(route -> loadBalancer.choosePinned(agent,
                                                route.instanceId(), command, context).flatMapMany(instance ->
                                                        streamToInstance(command, context, decision, agent, instance, route)))
                                                .switchIfEmpty(Flux.defer(() -> loadBalancer.choose(agent, command, context)
                                                        .flatMapMany(instance -> streamToInstance(command, context, decision,
                                                                agent, instance, null))));
                                    })))
                    .doFinally(ignored -> streamLimiter.release(command.tenantId()));
        });
    }

    private Mono<TaskRoute> affinityRoute(GatewayCommand command) {
        if (command.gatewayTaskId() != null && !command.gatewayTaskId().isBlank()) {
            return taskRouteStore.find(command.tenantId(), command.gatewayTaskId());
        }
        if (command.gatewayContextId() == null || command.gatewayContextId().isBlank()
                || (command.operation() != GatewayCommand.Operation.SEND_MESSAGE
                        && command.operation() != GatewayCommand.Operation.SEND_STREAMING_MESSAGE)) {
            return Mono.empty();
        }
        String agentId = command.targetHint().agentId();
        return taskRouteStore.list(new TaskRouteQuery(command.tenantId(), null, agentId, java.util.Set.of(), 1000,
                null, command.principal().fingerprint(), command.gatewayContextId())).flatMapMany(page ->
                        reactor.core.publisher.Flux.fromIterable(page.routes())).next();
    }

    private Flux<GatewayEvent> streamToInstance(GatewayCommand command, RoutingContext context,
            RouteDecision decision, AgentDefinition agent, AgentInstance instance, TaskRoute affinityRoute) {
        return selectAdapter(instance, command, affinityRoute)
                .onErrorResume(error -> {
                    release(agent, instance, false);
                    return Mono.error(error);
                })
                .flatMapMany(selection -> streamToSelectedInstance(command, context, decision, agent, instance,
                        affinityRoute, selection));
    }

    private Flux<GatewayEvent> streamToSelectedInstance(GatewayCommand command, RoutingContext context,
            RouteDecision decision, AgentDefinition agent, AgentInstance instance, TaskRoute affinityRoute,
            AdapterSelection selection) {
        boolean createsTask = command.operation() == GatewayCommand.Operation.SEND_STREAMING_MESSAGE;
        GatewayCommand effective = command;
        if (createsTask && (command.gatewayTaskId() == null || command.gatewayTaskId().isBlank())) {
            effective = withTaskIdentifiers(command, UuidGenerator.next(),
                    command.gatewayContextId() == null || command.gatewayContextId().isBlank()
                            ? UuidGenerator.next() : command.gatewayContextId());
        }
        TaskRoute pending = createsTask && (command.gatewayTaskId() == null || command.gatewayTaskId().isBlank())
                && effective.gatewayTaskId() != null
                ? pendingRoute(effective, decision, instance, selection.agentInterface()) : null;
        boolean taskAffinity = command.gatewayTaskId() != null && !command.gatewayTaskId().isBlank();
        GatewayCommand forwarded = affinityRoute == null ? effective
                : taskAffinity ? withUpstreamIdentifiers(effective, affinityRoute)
                        : withUpstreamContextIdentifier(effective, affinityRoute);
        AtomicReference<TaskRoute> route = new AtomicReference<>(pending == null ? affinityRoute : pending);
        AtomicBoolean sawUpstreamIdentifier = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        Mono<OutboundCredentials> credentials = instance.credentialRef() == null
                || instance.credentialRef().isBlank() ? Mono.empty()
                        : credentialProvider.resolve(agent.tenantId(), instance.credentialRef(), instance)
                                .onErrorMap(error -> new GatewayForwardingException(
                                        GatewayForwardingException.Code.CREDENTIALS_UNAVAILABLE,
                                        "outbound credentials are unavailable", error));
        Flux<GatewayEvent> stream = selection.adapter().encode(forwarded, instance)
                .onErrorMap(error -> error instanceof GatewayForwardingException ? error
                        : new GatewayForwardingException(GatewayForwardingException.Code.INVALID_REQUEST,
                                "could not encode outbound request", error))
                .flatMapMany(outbound -> (pending == null ? Mono.empty() : taskRouteStore.save(pending))
                        .thenMany(credentials.flatMapMany(secret -> streamExchange(forwarded, decision, agent, instance,
                                selection.adapter(), outbound, secret, route, sawUpstreamIdentifier, failed))
                                .switchIfEmpty(Flux.defer(() -> streamExchange(forwarded, decision, agent, instance,
                                        selection.adapter(), outbound, null, route, sawUpstreamIdentifier, failed)))));
        return stream.timeout(streamIdleTimeout)
                .onErrorResume(error -> markStreamError(route.get(), sawUpstreamIdentifier.get(), error)
                        .thenMany(Flux.error(streamError(error))))
                .doFinally(signal -> release(agent, instance, signal == SignalType.ON_ERROR || failed.get()
                        ? false : true));
    }

    private Flux<GatewayEvent> streamExchange(GatewayCommand command, RouteDecision decision, AgentDefinition agent,
            AgentInstance instance, ProtocolAdapter adapter, OutboundRequest outbound, OutboundCredentials credentials,
            AtomicReference<TaskRoute> route, AtomicBoolean sawUpstreamIdentifier, AtomicBoolean failed) {
        return transport.exchangeStream(instance, outbound, credentials)
                .concatMap(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return Flux.error(upstreamStreamError(command, response));
                    }
                    return prepareStreamingResponse(adapter, response, route, sawUpstreamIdentifier)
                            .flatMapMany(prepared -> adapter.decodeResponse(prepared)
                                    .concatMap(event -> normalizeStreamingEvent(command, decision, event, response,
                                            route, failed)));
                })
                .takeUntil(event -> event.type() == GatewayEvent.Type.TASK_COMPLETED
                        || event.type() == GatewayEvent.Type.ERROR);
    }

    private GatewayUpstreamException upstreamStreamError(GatewayCommand command, OutboundResponse response) {
        StandardUpstreamError error = classifyUpstreamError(command, response);
        return new GatewayUpstreamException(error.httpStatus(), error.rpcCode(), error.reason(), error.message());
    }

    private Mono<OutboundResponse> prepareStreamingResponse(ProtocolAdapter adapter, OutboundResponse response,
            AtomicReference<TaskRoute> route, AtomicBoolean sawUpstreamIdentifier) {
        TaskRoute current = route.get();
        if (current == null) {
            return Mono.just(response);
        }
        JsonRpcTaskReference reference = extractTaskReference(adapter, response.body());
        if (reference == null) {
            return Mono.just(response);
        }
        if (reference.taskId() == null && reference.contextId() == null) {
            return Mono.just(response);
        }
        sawUpstreamIdentifier.set(true);
        String payload = rewriteTaskIdentifiers(adapter, response.body(), reference, current);
        Map<String, Object> snapshot = taskSnapshot(adapter, payload);
        TaskRoute.State state = stateFromSnapshot(snapshot, TaskRoute.State.ACTIVE);
        TaskRoute updated = routeWithSnapshot(current, reference, state,
                snapshot.isEmpty() ? current.taskSnapshot() : snapshot);
        route.set(updated);
        return taskRouteStore.save(updated).thenReturn(new OutboundResponse(response.protocol(), response.statusCode(),
                payload, response.headers(), response.terminal()));
    }

    private Flux<GatewayEvent> normalizeStreamingEvent(GatewayCommand command, RouteDecision decision,
            GatewayEvent event, OutboundResponse response, AtomicReference<TaskRoute> route, AtomicBoolean failed) {
        Map<String, Object> metadata = new LinkedHashMap<>(event.metadata());
        String sseId = response.headers().get("SSE-Id");
        String sseEvent = response.headers().get("SSE-Event");
        if (sseId != null) {
            metadata.put("sseEventId", sseId);
        }
        if (sseEvent != null) {
            metadata.put("sseEventType", sseEvent);
        }
        metadata.put("decisionId", decision.decisionId());
        metadata.put("upstreamBinding", response.protocol().protocolBinding());
        GatewayEvent normalized = new GatewayEvent(event.type(), command.tenantId(), command.gatewayTaskId(),
                event.payload(), event.occurredAt(), metadata);
        TaskRoute current = route.get();
        if (normalized.type() == GatewayEvent.Type.ERROR) {
            failed.set(true);
        }
        if (current == null) {
            return Flux.just(normalized);
        }
        Map<String, Object> snapshot = mergeStreamingSnapshot(current.taskSnapshot(), normalized.payload());
        TaskRoute.State state = normalized.type() == GatewayEvent.Type.ERROR ? TaskRoute.State.FAILED
                : stateFromSnapshot(snapshot, normalized.type() == GatewayEvent.Type.TASK_COMPLETED
                        ? TaskRoute.State.COMPLETED : current.state());
        TaskRoute updated = routeWithSnapshot(current,
                new JsonRpcTaskReference(current.upstreamTaskId(), current.upstreamContextId()), state, snapshot);
        route.set(updated);
        return taskRouteStore.save(updated).thenMany(Flux.just(normalized));
    }

    private TaskRoute routeWithReference(TaskRoute route, JsonRpcTaskReference reference, TaskRoute.State state) {
        return routeWithSnapshot(route, reference, state, route.taskSnapshot());
    }

    private TaskRoute routeWithSnapshot(TaskRoute route, JsonRpcTaskReference reference, TaskRoute.State state,
            Map<String, Object> snapshot) {
        Instant now = Instant.now();
        Instant statusTimestamp = statusTimestamp(route, snapshot, state, now);
        return new TaskRoute(route.tenantId(), route.gatewayTaskId(), route.gatewayContextId(), route.agentId(),
                route.instanceId(), route.interfaceKey(), reference.taskId() == null ? route.upstreamTaskId()
                        : reference.taskId(), reference.contextId() == null ? route.upstreamContextId()
                                : reference.contextId(), route.protocolBinding(), route.protocolVersion(),
                route.principalFingerprint(), route.idempotencyKey(), state, route.createdAt(), now,
                now.plus(routeTtl), snapshot, statusTimestamp);
    }

    private Map<String, Object> taskSnapshot(ProtocolAdapter adapter, String body) {
        JsonNode node = readJson(body);
        if (node == null) {
            return Map.of();
        }
        if (adapter instanceof HttpJsonProtocolAdapter httpJson) {
            node = readJson(httpJson.toHttpJson(body));
        }
        else if (node.has("result")) {
            node = node.get("result");
        }
        JsonNode task = node != null && node.has("task") ? node.get("task") : node;
        if (task == null || !task.isObject() || !task.has("id")) {
            return Map.of();
        }
        return objectMapper.convertValue(task, Map.class);
    }

    private Map<String, Object> mergeStreamingSnapshot(Map<String, Object> existing, Object payload) {
        Map<String, Object> snapshot = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        JsonNode node = payload instanceof JsonNode jsonNode ? jsonNode : objectMapper.valueToTree(payload);
        if (node == null || node.isNull()) {
            return snapshot;
        }
        if (node.has("result")) {
            node = node.get("result");
        }
        JsonNode task = node.has("task") ? node.get("task") : null;
        if (task != null && task.isObject()) {
            snapshot.putAll(objectMapper.convertValue(task, Map.class));
            return snapshot;
        }
        JsonNode statusUpdate = node.get("statusUpdate");
        if (statusUpdate != null && statusUpdate.isObject()) {
            JsonNode status = statusUpdate.get("status");
            if (status != null && status.isObject()) {
                snapshot.put("status", objectMapper.convertValue(status, Map.class));
            }
            copyTextField(statusUpdate, snapshot, "taskId", "id");
            copyTextField(statusUpdate, snapshot, "contextId", "contextId");
        }
        JsonNode artifactUpdate = node.get("artifactUpdate");
        if (artifactUpdate != null && artifactUpdate.isObject() && artifactUpdate.has("artifact")) {
            List<Object> artifacts = new ArrayList<>();
            Object current = snapshot.get("artifacts");
            if (current instanceof List<?> list) {
                artifacts.addAll(list);
            }
            Map<String, Object> incoming = objectMapper.convertValue(artifactUpdate.get("artifact"), Map.class);
            String artifactId = text(incoming.get("artifactId"));
            boolean append = artifactUpdate.path("append").asBoolean(false);
            int existingIndex = artifactIndex(artifacts, artifactId);
            if (existingIndex >= 0) {
                Object currentArtifact = artifacts.get(existingIndex);
                artifacts.set(existingIndex, append ? appendArtifact(currentArtifact, incoming) : incoming);
            }
            else {
                artifacts.add(incoming);
            }
            snapshot.put("artifacts", artifacts);
            copyTextField(artifactUpdate, snapshot, "taskId", "id");
            copyTextField(artifactUpdate, snapshot, "contextId", "contextId");
        }
        return snapshot;
    }

    private int artifactIndex(List<Object> artifacts, String artifactId) {
        if (artifactId == null) {
            return -1;
        }
        for (int index = 0; index < artifacts.size(); index++) {
            Object value = artifacts.get(index);
            if (value instanceof Map<?, ?> map && artifactId.equals(text(map.get("artifactId")))) {
                return index;
            }
        }
        return -1;
    }

    private Map<String, Object> appendArtifact(Object current, Map<String, Object> incoming) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (current instanceof Map<?, ?> map) {
            map.forEach((key, value) -> merged.put(String.valueOf(key), value));
        }
        Object currentParts = merged.get("parts");
        Object incomingParts = incoming.get("parts");
        incoming.forEach(merged::put);
        if (currentParts instanceof List<?> existing && incomingParts instanceof List<?> added) {
            List<Object> parts = new ArrayList<>(existing);
            parts.addAll(added);
            merged.put("parts", parts);
        }
        return merged;
    }

    private Instant statusTimestamp(TaskRoute route, Map<String, Object> snapshot, TaskRoute.State state,
            Instant fallback) {
        Instant upstream = statusTimestamp(snapshot);
        if (upstream != null) {
            return upstream;
        }
        return route.state() == state ? route.statusTimestamp() : fallback;
    }

    private Instant statusTimestamp(Map<String, Object> snapshot) {
        if (snapshot == null || !(snapshot.get("status") instanceof Map<?, ?> status)) {
            return null;
        }
        Object timestamp = status.get("timestamp");
        if (timestamp == null) {
            return null;
        }
        try {
            return Instant.parse(timestamp.toString());
        }
        catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    private void copyTextField(JsonNode node, Map<String, Object> target, String source, String destination) {
        JsonNode value = node.get(source);
        if (value != null && value.isTextual()) {
            target.put(destination, value.asText());
        }
    }

    private TaskRoute.State stateFromSnapshot(Map<String, Object> snapshot, TaskRoute.State fallback) {
        if (snapshot == null) {
            return fallback;
        }
        Object status = snapshot.get("status");
        if (status instanceof Map<?, ?> map) {
            Object state = map.get("state");
            TaskRoute.State parsed = stateFromName(state == null ? null : state.toString());
            return parsed == null ? fallback : parsed;
        }
        return fallback;
    }

    private TaskRoute.State stateFromName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace("TASK_STATE_", "")
                .replace('-', '_');
        return switch (normalized) {
            case "SUBMITTED", "PENDING" -> TaskRoute.State.PENDING;
            case "WORKING", "ACTIVE" -> TaskRoute.State.ACTIVE;
            case "COMPLETED" -> TaskRoute.State.COMPLETED;
            case "FAILED" -> TaskRoute.State.FAILED;
            case "CANCELED", "CANCELLED" -> TaskRoute.State.CANCELED;
            case "INPUT_REQUIRED" -> TaskRoute.State.INPUT_REQUIRED;
            case "AUTH_REQUIRED" -> TaskRoute.State.AUTH_REQUIRED;
            case "REJECTED" -> TaskRoute.State.REJECTED;
            case "UNKNOWN", "UNSPECIFIED" -> TaskRoute.State.OUTCOME_UNKNOWN;
            default -> null;
        };
    }

    private Mono<Void> markStreamError(TaskRoute route, boolean sawUpstreamIdentifier, Throwable error) {
        if (route == null) {
            return Mono.empty();
        }
        return taskRouteStore.save(routeWithReference(route,
                new JsonRpcTaskReference(route.upstreamTaskId(), route.upstreamContextId()),
                TaskRoute.State.OUTCOME_UNKNOWN)).onErrorResume(ignored -> Mono.empty());
    }

    private RuntimeException streamError(Throwable error) {
        if (error instanceof GatewayUpstreamException upstream) {
            return upstream;
        }
        if (error instanceof GatewayForwardingException forwarding) {
            return forwarding;
        }
        if (error instanceof AgentTransportException) {
            return new GatewayForwardingException(GatewayForwardingException.Code.TRANSPORT,
                    "upstream streaming transport failed", error);
        }
        return new GatewayForwardingException(GatewayForwardingException.Code.TRANSPORT,
                "upstream streaming exchange failed", error);
    }

    private Mono<GatewayResult> execute(GatewayCommand command, RoutingContext context) {
        boolean createsTask = command.operation() == GatewayCommand.Operation.SEND_MESSAGE
                || command.operation() == GatewayCommand.Operation.SEND_STREAMING_MESSAGE;
        return routeResolver.resolve(command, context)
                .flatMap(decision -> command.operation() == GatewayCommand.Operation.LIST_TASKS
                        ? listTasks(command, decision)
                        : agentRegistry.get(command.tenantId(), decision.agentId())
                        .switchIfEmpty(Mono.error(new GatewayForwardingException(
                                GatewayForwardingException.Code.INTERFACE_UNAVAILABLE,
                                "selected Agent is no longer available")))
                        .flatMap(agent -> validateCapabilities(command, agent))
                        .flatMap(agent -> affinityRoute(command)
                                .flatMap(route -> loadBalancer.choosePinned(agent, route.instanceId(), command, context)
                                        .flatMap(instance -> forwardToInstance(command.gatewayTaskId() != null
                                                && !command.gatewayTaskId().isBlank()
                                                        ? withUpstreamIdentifiers(command, route)
                                                        : withUpstreamContextIdentifier(command, route),
                                                context, createsTask, decision, agent, instance, route)))
                                .switchIfEmpty(loadBalancer.choose(agent, command, context)
                                        .flatMap(instance -> forwardToInstance(command, context, createsTask, decision,
                                                agent, instance, null)))));
    }

    private Mono<GatewayResult> listTasks(GatewayCommand command, RouteDecision decision) {
        Object requestedPageSize = command.message().get("pageSize");
        if (requestedPageSize == null) {
            requestedPageSize = command.message().get("limit");
        }
        int pageSize = integer(requestedPageSize, 50);
        String pageToken = text(command.message().get("pageToken"));
        String contextId = text(command.message().get("contextId"));
        int historyLength = integerNonNegative(command.message().get("historyLength"), 0, "historyLength");
        boolean includeArtifacts = booleanValue(command.message().get("includeArtifacts"), false);
        Instant statusTimestampAfter = timestamp(command.message().get("statusTimestampAfter"));
        TaskRouteQuery query = new TaskRouteQuery(command.tenantId(), null, decision.agentId(),
                states(command.message().get("status")), pageSize, pageToken,
                command.principal().fingerprint(), contextId, statusTimestampAfter);
        return taskRouteStore.list(query).map(page -> {
            List<Map<String, Object>> tasks = page.routes().stream()
                    .filter(route -> command.principal().fingerprint().equals(route.principalFingerprint()))
                    .filter(route -> contextId == null || contextId.equals(route.gatewayContextId()))
                    .map(route -> taskForList(route, historyLength, includeArtifacts))
                    .toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tasks", tasks);
            result.put("nextPageToken", page.nextPageToken() == null ? "" : page.nextPageToken());
            result.put("pageSize", pageSize);
            result.put("totalSize", page.totalSize());
            Object payload = result;
            if ("JSONRPC".equals(command.inboundProtocol().protocolBinding())) {
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("jsonrpc", "2.0");
                envelope.put("id", command.metadata().get("jsonRpcId"));
                envelope.put("result", result);
                payload = envelope;
            }
            return new GatewayResult(true, payload, null,
                    Map.of("statusCode", 200, "decisionId", decision.decisionId(), "agentId", decision.agentId()));
        });
    }

    private Map<String, Object> taskForList(TaskRoute route, int historyLength, boolean includeArtifacts) {
        Map<String, Object> task = new LinkedHashMap<>(route.taskSnapshot());
        task.put("id", route.gatewayTaskId());
        if (route.gatewayContextId() != null) {
            task.put("contextId", route.gatewayContextId());
        }
        Map<String, Object> status = new LinkedHashMap<>();
        Object storedStatus = task.get("status");
        if (storedStatus instanceof Map<?, ?> map) {
            map.forEach((key, value) -> status.put(String.valueOf(key), value));
        }
        status.put("state", taskState(route.state()));
        status.put("timestamp", route.statusTimestamp());
        task.put("status", status);
        if (historyLength > 0) {
            List<?> history = listValue(task.get("history"));
            int from = Math.max(0, history.size() - historyLength);
            task.put("history", history.subList(from, history.size()));
        }
        else {
            task.remove("history");
        }
        if (includeArtifacts) {
            task.put("artifacts", listValue(task.get("artifacts")));
        }
        else {
            task.remove("artifacts");
        }
        return task;
    }

    private List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private int integer(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.toString());
            if (parsed < 1 || parsed > 100) {
                throw new IllegalArgumentException("pageSize must be between 1 and 100");
            }
            return parsed;
        }
        catch (NumberFormatException ex) {
            throw new IllegalArgumentException("pageSize must be an integer", ex);
        }
    }

    private int integerNonNegative(Object value, int defaultValue, String field) {
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.toString());
            if (parsed < 0) {
                throw new IllegalArgumentException(field + " must not be negative");
            }
            return parsed;
        }
        catch (NumberFormatException ex) {
            throw new IllegalArgumentException(field + " must be an integer", ex);
        }
    }

    private boolean booleanValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if ("true".equalsIgnoreCase(value.toString()) || "false".equalsIgnoreCase(value.toString())) {
            return Boolean.parseBoolean(value.toString());
        }
        throw new IllegalArgumentException("boolean field has an invalid value");
    }

    private Instant timestamp(Object value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        }
        catch (java.time.format.DateTimeParseException ex) {
            throw new IllegalArgumentException("statusTimestampAfter must be an RFC 3339 timestamp", ex);
        }
    }

    private String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private java.util.Set<TaskRoute.State> states(Object value) {
        String status = text(value);
        if (status == null) {
            return java.util.Set.of();
        }
        try {
            String normalized = status.toUpperCase(Locale.ROOT).replace("TASK_STATE_", "").replace('-', '_');
            return switch (normalized) {
                case "SUBMITTED", "PENDING" -> java.util.Set.of(TaskRoute.State.PENDING);
                case "WORKING", "ACTIVE" -> java.util.Set.of(TaskRoute.State.ACTIVE);
                case "COMPLETED" -> java.util.Set.of(TaskRoute.State.COMPLETED);
                case "FAILED" -> java.util.Set.of(TaskRoute.State.FAILED);
                case "CANCELED", "CANCELLED" -> java.util.Set.of(TaskRoute.State.CANCELED);
                case "INPUT_REQUIRED" -> java.util.Set.of(TaskRoute.State.INPUT_REQUIRED);
                case "AUTH_REQUIRED" -> java.util.Set.of(TaskRoute.State.AUTH_REQUIRED);
                case "REJECTED" -> java.util.Set.of(TaskRoute.State.REJECTED);
                case "UNKNOWN", "UNSPECIFIED" -> java.util.Set.of(TaskRoute.State.OUTCOME_UNKNOWN);
                default -> throw new IllegalArgumentException("unsupported task status: " + status);
            };
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unsupported task status: " + status, ex);
        }
    }

    private String taskState(TaskRoute.State state) {
        return switch (state) {
            case PENDING -> "TASK_STATE_SUBMITTED";
            case ACTIVE -> "TASK_STATE_WORKING";
            case COMPLETED -> "TASK_STATE_COMPLETED";
            case FAILED -> "TASK_STATE_FAILED";
            case CANCELED -> "TASK_STATE_CANCELED";
            case INPUT_REQUIRED -> "TASK_STATE_INPUT_REQUIRED";
            case AUTH_REQUIRED -> "TASK_STATE_AUTH_REQUIRED";
            case REJECTED -> "TASK_STATE_REJECTED";
            case OUTCOME_UNKNOWN -> "TASK_STATE_UNSPECIFIED";
        };
    }

    private boolean supportsPush(GatewayCommand command) {
        return command.operation() == GatewayCommand.Operation.CREATE_TASK_PUSH_NOTIFICATION_CONFIG
                || command.operation() == GatewayCommand.Operation.GET_TASK_PUSH_NOTIFICATION_CONFIG
                || command.operation() == GatewayCommand.Operation.LIST_TASK_PUSH_NOTIFICATION_CONFIGS
                || command.operation() == GatewayCommand.Operation.DELETE_TASK_PUSH_NOTIFICATION_CONFIG;
    }

    private boolean pushSupported(AgentDefinition agent) {
        Object capabilities = agent.cardMetadata().get("capabilities");
        if (capabilities instanceof Map<?, ?> map) {
            return Boolean.TRUE.equals(map.get("pushNotifications"));
        }
        return false;
    }

    private Mono<AgentDefinition> validateCapabilities(GatewayCommand command, AgentDefinition agent) {
        if (supportsPush(command) && !capability(agent, "pushNotifications", false)) {
            return Mono.error(new GatewayForwardingException(
                    GatewayForwardingException.Code.PUSH_NOTIFICATION_NOT_SUPPORTED,
                    "upstream Agent does not support push notifications"));
        }
        if ((command.operation() == GatewayCommand.Operation.SEND_STREAMING_MESSAGE
                || command.operation() == GatewayCommand.Operation.SUBSCRIBE_TO_TASK)
                && !capability(agent, "streaming", false)) {
            return Mono.error(new GatewayForwardingException(GatewayForwardingException.Code.UNSUPPORTED_OPERATION,
                    "upstream Agent does not support streaming"));
        }
        if (command.operation() == GatewayCommand.Operation.SUBSCRIBE_TO_TASK
                && (capabilityExplicitlyFalse(agent, "subscriptions")
                        || capabilityExplicitlyFalse(agent, "subscription"))) {
            return Mono.error(new GatewayForwardingException(GatewayForwardingException.Code.UNSUPPORTED_OPERATION,
                    "upstream Agent does not support task subscriptions"));
        }
        if (command.operation() == GatewayCommand.Operation.GET_EXTENDED_AGENT_CARD
                && !capability(agent, "extendedAgentCard", false)) {
            return Mono.error(new GatewayForwardingException(
                    GatewayForwardingException.Code.UNSUPPORTED_OPERATION,
                    "upstream Agent does not support extended Agent Cards"));
        }
        return Mono.just(agent);
    }

    private boolean capability(AgentDefinition agent, String name, boolean allowMissing) {
        Object capabilities = agent.cardMetadata().get("capabilities");
        if (!(capabilities instanceof Map<?, ?> map) || !map.containsKey(name)) {
            return allowMissing;
        }
        return Boolean.TRUE.equals(map.get(name));
    }

    private boolean capabilityExplicitlyFalse(AgentDefinition agent, String name) {
        Object capabilities = agent.cardMetadata().get("capabilities");
        return capabilities instanceof Map<?, ?> map && map.containsKey(name) && !Boolean.TRUE.equals(map.get(name));
    }

    private Mono<GatewayResult> forwardToInstance(GatewayCommand command, RoutingContext context,
            boolean createsTask, RouteDecision decision, AgentDefinition agent, AgentInstance instance,
            TaskRoute affinityRoute) {
        return selectAdapter(instance, command, affinityRoute)
                .onErrorResume(error -> {
                    release(agent, instance, false);
                    return Mono.error(error);
                })
                .flatMap(selection -> forwardToSelectedInstance(command, context, createsTask, decision, agent,
                        instance, affinityRoute, selection));
    }

    private Mono<GatewayResult> forwardToSelectedInstance(GatewayCommand command, RoutingContext context,
            boolean createsTask, RouteDecision decision, AgentDefinition agent, AgentInstance instance,
            TaskRoute affinityRoute, AdapterSelection selection) {
        GatewayCommand effective = command;
        if (createsTask && (command.gatewayTaskId() == null || command.gatewayTaskId().isBlank())) {
            effective = withTaskIdentifiers(command, UuidGenerator.next(),
                    command.gatewayContextId() == null || command.gatewayContextId().isBlank()
                            ? UuidGenerator.next() : command.gatewayContextId());
        }
        boolean taskAffinity = command.gatewayTaskId() != null && !command.gatewayTaskId().isBlank();
        GatewayCommand forwarded = affinityRoute == null ? effective
                : taskAffinity ? withUpstreamIdentifiers(effective, affinityRoute)
                        : withUpstreamContextIdentifier(effective, affinityRoute);
        TaskRoute pending = createsTask && !taskAffinity && forwarded.gatewayTaskId() != null
                ? pendingRoute(forwarded, decision, instance, selection.agentInterface()) : null;
        Mono<OutboundCredentials> credentials = instance.credentialRef() == null
                || instance.credentialRef().isBlank() ? Mono.empty()
                        : credentialProvider.resolve(agent.tenantId(), instance.credentialRef(), instance)
                                .onErrorMap(error -> new GatewayForwardingException(
                                        GatewayForwardingException.Code.CREDENTIALS_UNAVAILABLE,
                                        "outbound credentials are unavailable", error));
        return selection.adapter().encode(forwarded, instance)
                .onErrorMap(error -> error instanceof GatewayForwardingException ? error
                        : new GatewayForwardingException(GatewayForwardingException.Code.INVALID_REQUEST,
                                "could not encode outbound request", error))
                .flatMap(outbound -> (pending == null ? Mono.empty() : taskRouteStore.save(pending))
                        .then(credentials.flatMap(secret -> exchange(forwarded, decision, agent, instance,
                                selection.adapter(), selection.agentInterface(), outbound, secret, pending,
                                affinityRoute))
                                .switchIfEmpty(Mono.defer(() -> exchange(forwarded, decision, agent, instance,
                                        selection.adapter(), selection.agentInterface(), outbound, null, pending,
                                        affinityRoute))))
                        .doOnError(ignored -> release(agent, instance, false))
                        .doOnSuccess(ignored -> release(agent, instance, true)));
    }

    private Mono<GatewayResult> exchange(GatewayCommand command, RouteDecision decision, AgentDefinition agent,
            AgentInstance instance, ProtocolAdapter adapter, AgentInterface selectedInterface, OutboundRequest outbound,
            OutboundCredentials credentials, TaskRoute pending, TaskRoute affinityRoute) {
        return transport.exchange(instance, outbound, credentials).next()
                .switchIfEmpty(Mono.error(new GatewayForwardingException(GatewayForwardingException.Code.TRANSPORT,
                        "upstream returned no response")))
                .onErrorMap(error -> error instanceof GatewayForwardingException ? error
                        : error instanceof AgentTransportException
                                ? new GatewayForwardingException(GatewayForwardingException.Code.TRANSPORT,
                                        "upstream transport failed", error)
                                : error)
                .flatMap(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return Mono.just(upstreamError(command, response, decision, selectedInterface));
                    }
                    return adapter.decodeResponse(response).collectList()
                            .onErrorMap(error -> error instanceof GatewayForwardingException ? error
                                    : new GatewayForwardingException(
                                            GatewayForwardingException.Code.UPSTREAM_PROTOCOL,
                                            "upstream returned an invalid protocol payload", error))
                            .flatMap(events -> {
                                if (events.stream().anyMatch(event -> event.type()
                                        == io.github.a2ap.gateway.api.model.GatewayEvent.Type.ERROR)) {
                                    return Mono.just(upstreamError(command, response, decision, selectedInterface));
                                }
                                return completeRoute(command, adapter, pending, affinityRoute, response)
                                        .map(payload -> result(payload, response, decision, selectedInterface));
                            });
                });
    }

    private GatewayResult upstreamError(GatewayCommand command, OutboundResponse response, RouteDecision decision,
            AgentInterface selectedInterface) {
        StandardUpstreamError error = classifyUpstreamError(command, response);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("statusCode", error.httpStatus());
        metadata.put("decisionId", decision.decisionId());
        metadata.put("interfaceKey", selectedInterface.interfaceKey());
        metadata.put("agentId", decision.agentId());
        metadata.put("upstreamBinding", selectedInterface.protocolBinding());
        return new GatewayResult(false, normalizeUpstreamError(command, error), "UPSTREAM_ERROR", metadata);
    }

    /** Converts either upstream binding's error envelope into the inbound binding's envelope. */
    private String normalizeUpstreamError(GatewayCommand command, StandardUpstreamError error) {
        boolean inboundJsonRpc = "JSONRPC".equals(command.inboundProtocol().protocolBinding());
        try {
            if (inboundJsonRpc) {
                ObjectNode envelope = objectMapper.createObjectNode();
                envelope.put("jsonrpc", "2.0");
                JsonNode requestId = objectMapper.valueToTree(command.metadata().get("jsonRpcId"));
                envelope.set("id", requestId == null ? objectMapper.nullNode() : requestId);
                ObjectNode rpcError = envelope.putObject("error");
                rpcError.put("code", error.rpcCode());
                rpcError.put("message", error.message());
                if (error.details() != null && !error.details().isNull()) {
                    rpcError.set("data", error.details());
                }
                else if (error.reason() != null) {
                    rpcError.set("data", errorInfo(error.reason()));
                }
                return objectMapper.writeValueAsString(envelope);
            }
            ObjectNode envelope = objectMapper.createObjectNode();
            ObjectNode httpError = envelope.putObject("error");
            httpError.put("code", error.httpStatus());
            httpError.put("status", statusName(error.httpStatus()));
            httpError.put("message", error.message());
            if (error.details() != null && error.details().isArray()) {
                httpError.set("details", error.details());
            }
            else if (error.details() != null && !error.details().isNull()) {
                httpError.set("details", objectMapper.createArrayNode().add(error.details()));
            }
            else if (error.reason() != null) {
                httpError.set("details", objectMapper.createArrayNode().add(errorInfo(error.reason())));
            }
            else {
                httpError.set("details", objectMapper.createArrayNode());
            }
            return objectMapper.writeValueAsString(envelope);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return "{\"error\":{\"code\":" + error.httpStatus()
                    + ",\"status\":\"" + statusName(error.httpStatus())
                    + ",\"message\":\"upstream request failed\"}}";
        }
    }

    private JsonNode errorInfo(String reason) {
        ObjectNode info = objectMapper.createObjectNode();
        info.put("@type", "type.googleapis.com/google.rpc.ErrorInfo");
        info.put("reason", reason);
        info.put("domain", "a2a-protocol.org");
        return info;
    }

    private StandardUpstreamError classifyUpstreamError(GatewayCommand command, OutboundResponse response) {
        JsonNode root = readJson(response.body());
        JsonNode error = root == null ? null : root.get("error");
        JsonNode source = error == null || error.isNull() ? root : error;
        int upstreamRpcCode = source != null && source.get("code") != null && source.get("code").isNumber()
                ? source.get("code").intValue() : 0;
        String reason = firstReason(root, source);
        String canonical = canonicalErrorReason(reason);
        if (upstreamRpcCode == -32001) {
            canonical = "TASK_NOT_FOUND";
        }
        if (command.operation() == GatewayCommand.Operation.GET_EXTENDED_AGENT_CARD
                && response.statusCode() == 404 && upstreamRpcCode != -32001) {
            canonical = "EXTENDED_AGENT_CARD_NOT_CONFIGURED";
        }
        else if (canonical == null && response.statusCode() == 404) {
            canonical = "TASK_NOT_FOUND";
        }
        ErrorMapping mapping = errorMapping(upstreamRpcCode);
        if (mapping != null) {
            canonical = mapping.reason();
        }
        else {
            mapping = errorMapping(canonical);
            if (mapping != null) {
                canonical = mapping.reason();
            }
        }
        int status = mapping == null ? (response.statusCode() >= 200 && response.statusCode() < 300 ? 502
                : response.statusCode()) : mapping.httpStatus();
        int rpcCode = mapping == null ? -32000 : mapping.rpcCode();
        String message = textNode(source, "message");
        if (message == null) {
            message = response.body() == null || response.body().isBlank() ? "upstream request failed"
                    : response.body();
        }
        JsonNode details = source == null ? null : source.get("details");
        if ((details == null || details.isNull()) && source != null) {
            details = source.get("data");
        }
        return new StandardUpstreamError(rpcCode, status, message, details, canonical);
    }

    private String firstReason(JsonNode root, JsonNode source) {
        String reason = textNode(source, "reason");
        if (reason != null) {
            return reason;
        }
        reason = textNode(source, "code");
        if (reason != null && !reason.chars().allMatch(Character::isDigit)) {
            return reason;
        }
        reason = textNode(source, "status");
        if (reason != null && !"OK".equalsIgnoreCase(reason)) {
            return reason;
        }
        return findReason(root);
    }

    private String findReason(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            String reason = textNode(node, "reason");
            if (reason != null) {
                return reason;
            }
            java.util.Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                String nested = findReason(values.next());
                if (nested != null) {
                    return nested;
                }
            }
        }
        else if (node.isArray()) {
            for (JsonNode item : node) {
                String nested = findReason(item);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private String canonicalErrorReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String normalized = reason.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (normalized.startsWith("A2A_")) {
            normalized = normalized.substring(4);
        }
        if (normalized.startsWith("GATEWAY_")) {
            normalized = normalized.substring(8);
        }
        return errorMapping(normalized) == null ? null : normalized;
    }

    private ErrorMapping errorMapping(String reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case "TASK_NOT_FOUND", "NOT_FOUND" -> new ErrorMapping(-32001, 404, "TASK_NOT_FOUND");
            case "TASK_NOT_CANCELABLE" -> new ErrorMapping(-32002, 400, "TASK_NOT_CANCELABLE");
            case "PUSH_NOTIFICATION_NOT_SUPPORTED" ->
                new ErrorMapping(-32003, 400, "PUSH_NOTIFICATION_NOT_SUPPORTED");
            case "UNSUPPORTED_OPERATION" -> new ErrorMapping(-32004, 400, "UNSUPPORTED_OPERATION");
            case "CONTENT_TYPE_NOT_SUPPORTED" -> new ErrorMapping(-32005, 400, "CONTENT_TYPE_NOT_SUPPORTED");
            case "INVALID_AGENT_RESPONSE" -> new ErrorMapping(-32006, 500, "INVALID_AGENT_RESPONSE");
            case "UPSTREAM_PROTOCOL_ERROR" -> new ErrorMapping(-32006, 502, "INVALID_AGENT_RESPONSE");
            case "EXTENDED_AGENT_CARD_NOT_CONFIGURED" ->
                new ErrorMapping(-32007, 400, "EXTENDED_AGENT_CARD_NOT_CONFIGURED");
            case "EXTENSION_SUPPORT_REQUIRED" -> new ErrorMapping(-32008, 400, "EXTENSION_SUPPORT_REQUIRED");
            case "VERSION_NOT_SUPPORTED" -> new ErrorMapping(-32009, 400, "VERSION_NOT_SUPPORTED");
            default -> null;
        };
    }

    private ErrorMapping errorMapping(int rpcCode) {
        return switch (rpcCode) {
            case -32001 -> errorMapping("TASK_NOT_FOUND");
            case -32002 -> errorMapping("TASK_NOT_CANCELABLE");
            case -32003 -> errorMapping("PUSH_NOTIFICATION_NOT_SUPPORTED");
            case -32004 -> errorMapping("UNSUPPORTED_OPERATION");
            case -32005 -> errorMapping("CONTENT_TYPE_NOT_SUPPORTED");
            case -32006 -> errorMapping("INVALID_AGENT_RESPONSE");
            case -32007 -> errorMapping("EXTENDED_AGENT_CARD_NOT_CONFIGURED");
            case -32008 -> errorMapping("EXTENSION_SUPPORT_REQUIRED");
            case -32009 -> errorMapping("VERSION_NOT_SUPPORTED");
            default -> null;
        };
    }

    private record ErrorMapping(int rpcCode, int httpStatus, String reason) {
    }

    private record StandardUpstreamError(int rpcCode, int httpStatus, String message, JsonNode details,
            String reason) {
    }

    private JsonNode readJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }

    private String textNode(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }

    private String statusName(int status) {
        return switch (status) {
            case 400 -> "INVALID_ARGUMENT";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "PERMISSION_DENIED";
            case 404 -> "NOT_FOUND";
            case 409 -> "ABORTED";
            case 429 -> "RESOURCE_EXHAUSTED";
            case 413 -> "RESOURCE_EXHAUSTED";
            case 500 -> "INTERNAL";
            case 501 -> "NOT_IMPLEMENTED";
            case 502 -> "BAD_GATEWAY";
            case 503 -> "UNAVAILABLE";
            case 504 -> "DEADLINE_EXCEEDED";
            default -> "UNKNOWN";
        };
    }

    private Mono<String> completeRoute(GatewayCommand command, ProtocolAdapter adapter, TaskRoute pending,
            TaskRoute affinityRoute, OutboundResponse response) {
        TaskRoute route = pending == null ? affinityRoute : pending;
        if (route == null) {
            return Mono.just(command.operation() == GatewayCommand.Operation.GET_EXTENDED_AGENT_CARD
                    ? projectExtendedCard(command, response.body()) : response.body());
        }
        String payload = response.body();
        JsonRpcTaskReference reference = extractTaskReference(adapter, response.body());
        if (reference == null) {
            reference = new JsonRpcTaskReference(null, null);
        }
        if (reference.taskId() != null || reference.contextId() != null) {
            payload = rewriteTaskIdentifiers(adapter, response.body(), reference, route);
        }
        Map<String, Object> snapshot = taskSnapshot(adapter, payload);
        TaskRoute.State state = route.state();
        if (command.operation() == GatewayCommand.Operation.CANCEL_TASK) {
            state = TaskRoute.State.CANCELED;
        }
        else if (!snapshot.isEmpty()) {
            state = stateFromSnapshot(snapshot, state);
        }
        else if (pending != null) {
            state = reference.taskId() == null ? TaskRoute.State.COMPLETED : TaskRoute.State.ACTIVE;
        }
        Instant now = Instant.now();
        Instant statusTimestamp = statusTimestamp(route, snapshot, state, now);
        TaskRoute updated = new TaskRoute(route.tenantId(), route.gatewayTaskId(), route.gatewayContextId(),
                route.agentId(), route.instanceId(), route.interfaceKey(),
                reference.taskId() == null ? route.upstreamTaskId() : reference.taskId(),
                reference.contextId() == null ? route.upstreamContextId() : reference.contextId(),
                route.protocolBinding(), route.protocolVersion(), route.principalFingerprint(), route.idempotencyKey(),
                state, route.createdAt(), now, now.plus(routeTtl), snapshot.isEmpty() ? route.taskSnapshot() : snapshot,
                statusTimestamp);
        return taskRouteStore.save(updated).thenReturn(payload);
    }

    private String projectExtendedCard(GatewayCommand command, String body) {
        JsonNode root = readJson(body);
        if (root == null) {
            return body;
        }
        JsonNode card = root.has("result") ? root.get("result") : root;
        if (card == null || !card.isObject()) {
            return body;
        }
        ObjectNode projected = (ObjectNode) card;
        projected.remove(List.of("signatures", "securitySchemes", "securityRequirements"));
        JsonNode interfaces = projected.get("supportedInterfaces");
        if (interfaces != null && interfaces.isArray()) {
            String agentId = command.targetHint().agentId();
            if (agentId == null || agentId.isBlank()) {
                agentId = "default";
            }
            String encodedAgentId = URLEncoder.encode(agentId, StandardCharsets.UTF_8).replace("+", "%20");
            for (JsonNode item : interfaces) {
                if (item instanceof ObjectNode interfaceNode) {
                    String binding = textNode(interfaceNode, "protocolBinding");
                    String path = "JSONRPC".equals(binding) ? "/gateway/v1/agents/" + encodedAgentId + "/a2a"
                            : "/gateway/v1/agents/" + encodedAgentId;
                    interfaceNode.put("url", path);
                }
            }
        }
        try {
            return objectMapper.writeValueAsString(root);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return body;
        }
    }

    private GatewayResult result(String payload, OutboundResponse response, RouteDecision decision,
            AgentInterface selectedInterface) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("statusCode", response.statusCode());
        metadata.put("decisionId", decision.decisionId());
        metadata.put("interfaceKey", selectedInterface.interfaceKey());
        metadata.put("agentId", decision.agentId());
        metadata.put("upstreamBinding", selectedInterface.protocolBinding());
        return new GatewayResult(true, payload, null, metadata);
    }

    private Mono<AdapterSelection> selectAdapter(AgentInstance instance, GatewayCommand command,
            TaskRoute affinityRoute) {
        Mono<AgentInterface> selected = affinityRoute == null
                ? interfaceSelector.choose(instance, command.inboundProtocol(), command)
                : Mono.justOrEmpty(instance.interfaces().stream()
                        .filter(candidate -> candidate.interfaceKey().equals(affinityRoute.interfaceKey()))
                        .filter(candidate -> candidate.protocolBinding().equals(affinityRoute.protocolBinding()))
                        .filter(candidate -> candidate.protocolVersion().equals(affinityRoute.protocolVersion()))
                        .findFirst());
        return selected.switchIfEmpty(Mono.error(new GatewayForwardingException(
                GatewayForwardingException.Code.INTERFACE_UNAVAILABLE,
                "selected Agent instance has no compatible protocol interface")))
                .flatMap(agentInterface -> Mono.justOrEmpty(protocolAdapters.get(agentInterface.protocolBinding()))
                        .switchIfEmpty(Mono.error(new GatewayForwardingException(
                                GatewayForwardingException.Code.INTERFACE_UNAVAILABLE,
                                "selected Agent interface has no registered protocol adapter")))
                        .map(adapter -> new AdapterSelection(agentInterface, adapter)));
    }

    private JsonRpcTaskReference extractTaskReference(ProtocolAdapter adapter, String body) {
        if (adapter instanceof JsonRpcProtocolAdapter jsonRpc) {
            return jsonRpc.extractTaskReference(body);
        }
        if (adapter instanceof HttpJsonProtocolAdapter httpJson) {
            return httpJson.extractTaskReference(body);
        }
        return null;
    }

    private String rewriteTaskIdentifiers(ProtocolAdapter adapter, String body, JsonRpcTaskReference reference,
            TaskRoute route) {
        if (adapter instanceof JsonRpcProtocolAdapter jsonRpc) {
            return jsonRpc.rewriteTaskIdentifiers(body, reference.taskId(), reference.contextId(),
                    route.gatewayTaskId(), route.gatewayContextId());
        }
        if (adapter instanceof HttpJsonProtocolAdapter httpJson) {
            return httpJson.rewriteTaskIdentifiers(body, reference.taskId(), reference.contextId(),
                    route.gatewayTaskId(), route.gatewayContextId());
        }
        return body;
    }

    private void recordRequest(GatewayCommand command, String outcome, Throwable error, Instant started) {
        Map<String, String> tags = metricTags(command, outcome, error);
        metrics.record(GatewayMetricEvent.counter("gateway.requests.total", tags));
        metrics.record(GatewayMetricEvent.timer("gateway.request.duration",
                Duration.between(started, Instant.now()).toMillis(), tags));
    }

    private Map<String, String> metricTags(GatewayCommand command, String outcome, Throwable error) {
        Map<String, String> tags = new HashMap<>();
        tags.put("operation", command.operation().name());
        tags.put("protocol", command.inboundProtocol().protocolBinding());
        tags.put("status", outcome);
        if (error instanceof GatewayForwardingException forwarding) {
            tags.put("error", forwarding.code().name());
        }
        else if (error != null) {
            tags.put("error", "UNEXPECTED");
        }
        else {
            tags.put("error", "NONE");
        }
        return tags;
    }

    private TaskRoute pendingRoute(GatewayCommand command, RouteDecision decision, AgentInstance instance,
            AgentInterface selectedInterface) {
        Instant now = Instant.now();
        return new TaskRoute(command.tenantId(), command.gatewayTaskId(), command.gatewayContextId(),
                decision.agentId(), instance.instanceId(), selectedInterface.interfaceKey(), null, null,
                selectedInterface.protocolBinding(), selectedInterface.protocolVersion(),
                command.principal().fingerprint(), command.idempotencyKey(), TaskRoute.State.PENDING, now, now,
                now.plus(routeTtl), Map.of(), now);
    }

    private GatewayCommand withTaskIdentifiers(GatewayCommand command, String taskId, String contextId) {
        return new GatewayCommand(command.operation(), command.tenantId(), command.principal(), command.targetHint(),
                taskId, contextId, command.message(), command.configuration(), command.metadata(),
                command.idempotencyKey(), command.inboundProtocol(), command.requestedProtocolVersion(),
                command.extensions());
    }

    private GatewayCommand withUpstreamIdentifiers(GatewayCommand command, TaskRoute route) {
        Map<String, Object> metadata = new LinkedHashMap<>(command.metadata());
        if (route.upstreamTaskId() != null) {
            metadata.put("upstreamTaskId", route.upstreamTaskId());
        }
        if (route.upstreamContextId() != null) {
            metadata.put("upstreamContextId", route.upstreamContextId());
        }
        return new GatewayCommand(command.operation(), command.tenantId(), command.principal(), command.targetHint(),
                command.gatewayTaskId(), command.gatewayContextId(), command.message(), command.configuration(),
                metadata, command.idempotencyKey(), command.inboundProtocol(), command.requestedProtocolVersion(),
                command.extensions());
    }

    private GatewayCommand withUpstreamContextIdentifier(GatewayCommand command, TaskRoute route) {
        Map<String, Object> metadata = new LinkedHashMap<>(command.metadata());
        // A context-only continuation must not accidentally send the gateway task id upstream.
        metadata.put("upstreamTaskId", "");
        if (route.upstreamContextId() != null) {
            metadata.put("upstreamContextId", route.upstreamContextId());
        }
        return new GatewayCommand(command.operation(), command.tenantId(), command.principal(), command.targetHint(),
                command.gatewayTaskId(), command.gatewayContextId(), command.message(), command.configuration(),
                metadata, command.idempotencyKey(), command.inboundProtocol(), command.requestedProtocolVersion(),
                command.extensions());
    }

    private Mono<GatewayResult> replayOrReject(io.github.a2ap.gateway.api.model.IdempotencyRecord record,
            String requestHash) {
        if (!record.requestHash().equals(requestHash)) {
            return Mono.error(new GatewayForwardingException(GatewayForwardingException.Code.INVALID_REQUEST,
                    "idempotency key was reused with a different request"));
        }
        return switch (record.state()) {
            case COMPLETED -> Mono.just(Objects.requireNonNull(record.result(), "completed idempotency result"));
            case OUTCOME_UNKNOWN -> Mono.error(new GatewayForwardingException(
                    GatewayForwardingException.Code.OUTCOME_UNKNOWN,
                    "idempotent upstream outcome is unknown"));
            case IN_FLIGHT -> Mono.error(new GatewayForwardingException(
                    GatewayForwardingException.Code.DUPLICATE_IN_FLIGHT,
                    "idempotency key is already in flight"));
        };
    }

    private boolean unknownOutcome(Throwable error) {
        return error instanceof GatewayForwardingException forwarding
                && forwarding.code() == GatewayForwardingException.Code.TRANSPORT;
    }

    private void release(AgentDefinition agent, AgentInstance instance, boolean success) {
        loadBalancer.release(agent, instance);
        if (loadBalancer instanceof WeightedLeastActiveLoadBalancer weighted) {
            if (success) {
                weighted.recordSuccess(agent, instance);
            }
            else {
                weighted.recordFailure(agent, instance);
            }
        }
    }

    private String requestHash(GatewayCommand command) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("operation", command.operation().name());
            canonical.put("tenantId", command.tenantId());
            canonical.put("principal", command.principal().fingerprint());
            canonical.put("targetHint", command.targetHint());
            canonical.put("gatewayTaskId", command.gatewayTaskId());
            canonical.put("gatewayContextId", command.gatewayContextId());
            canonical.put("message", command.message());
            canonical.put("configuration", command.configuration());
            canonical.put("protocol", command.inboundProtocol());
            byte[] bytes = objectMapper.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("could not hash gateway request", ex);
        }
    }

    private static final class UuidGenerator {

        private UuidGenerator() {
        }

        private static String next() {
            return java.util.UUID.randomUUID().toString();
        }

    }

    private record AdapterSelection(AgentInterface agentInterface, ProtocolAdapter adapter) {
    }

}
