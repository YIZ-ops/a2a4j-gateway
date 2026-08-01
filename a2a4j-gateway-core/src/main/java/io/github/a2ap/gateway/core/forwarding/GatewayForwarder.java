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
import io.github.a2ap.gateway.core.routing.DefaultAgentInterfaceSelector;
import io.github.a2ap.gateway.core.routing.WeightedLeastActiveLoadBalancer;
import io.github.a2ap.gateway.core.transport.AgentTransportException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
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
                            .flatMapMany(agent -> affinityRoute(command)
                                    .flatMapMany(route -> loadBalancer.choosePinned(agent, route.instanceId(), command,
                                            context).flatMapMany(instance -> streamToInstance(command, context, decision,
                                                    agent, instance, route)))
                                    .switchIfEmpty(Flux.defer(() -> loadBalancer.choose(agent, command, context)
                                            .flatMapMany(instance -> streamToInstance(command, context, decision, agent,
                                                    instance, null))))))
                    .doFinally(ignored -> streamLimiter.release(command.tenantId()));
        });
    }

    private Mono<TaskRoute> affinityRoute(GatewayCommand command) {
        if (command.gatewayTaskId() == null || command.gatewayTaskId().isBlank()) {
            return Mono.empty();
        }
        return taskRouteStore.find(command.tenantId(), command.gatewayTaskId());
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
            effective = withTaskIdentifiers(command, UuidGenerator.next(), UuidGenerator.next());
        }
        TaskRoute pending = createsTask && affinityRoute == null && effective.gatewayTaskId() != null
                ? pendingRoute(effective, decision, instance, selection.agentInterface()) : null;
        GatewayCommand forwarded = affinityRoute == null ? effective : withUpstreamIdentifiers(effective, affinityRoute);
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
                .concatMap(response -> prepareStreamingResponse(adapter, response, route, sawUpstreamIdentifier)
                        .flatMapMany(prepared -> adapter.decodeResponse(prepared)
                                .concatMap(event -> normalizeStreamingEvent(command, decision, event, response, route,
                                        failed))))
                .takeUntil(event -> event.type() == GatewayEvent.Type.TASK_COMPLETED
                        || event.type() == GatewayEvent.Type.ERROR);
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
        TaskRoute updated = routeWithReference(current, reference, TaskRoute.State.ACTIVE);
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
        GatewayEvent normalized = new GatewayEvent(event.type(), command.tenantId(), command.gatewayTaskId(),
                event.payload(), event.occurredAt(), metadata);
        TaskRoute current = route.get();
        if (normalized.type() == GatewayEvent.Type.ERROR) {
            failed.set(true);
        }
        if (current == null || (normalized.type() != GatewayEvent.Type.ERROR
                && normalized.type() != GatewayEvent.Type.TASK_COMPLETED)) {
            return Flux.just(normalized);
        }
        TaskRoute.State state = normalized.type() == GatewayEvent.Type.ERROR ? TaskRoute.State.FAILED
                : TaskRoute.State.COMPLETED;
        TaskRoute updated = routeWithReference(current,
                new JsonRpcTaskReference(current.upstreamTaskId(), current.upstreamContextId()), state);
        route.set(updated);
        return taskRouteStore.save(updated).thenMany(Flux.just(normalized));
    }

    private TaskRoute routeWithReference(TaskRoute route, JsonRpcTaskReference reference, TaskRoute.State state) {
        return new TaskRoute(route.tenantId(), route.gatewayTaskId(), route.gatewayContextId(), route.agentId(),
                route.instanceId(), route.interfaceKey(), reference.taskId() == null ? route.upstreamTaskId()
                        : reference.taskId(), reference.contextId() == null ? route.upstreamContextId()
                                : reference.contextId(), route.protocolBinding(), route.protocolVersion(),
                route.principalFingerprint(), route.idempotencyKey(), state, route.createdAt(), Instant.now(),
                route.expiresAt());
    }

    private Mono<Void> markStreamError(TaskRoute route, boolean sawUpstreamIdentifier, Throwable error) {
        if (route == null || sawUpstreamIdentifier) {
            return Mono.empty();
        }
        return taskRouteStore.save(routeWithReference(route,
                new JsonRpcTaskReference(route.upstreamTaskId(), route.upstreamContextId()),
                TaskRoute.State.OUTCOME_UNKNOWN)).onErrorResume(ignored -> Mono.empty());
    }

    private GatewayForwardingException streamError(Throwable error) {
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
                .flatMap(decision -> agentRegistry.get(command.tenantId(), decision.agentId())
                        .switchIfEmpty(Mono.error(new GatewayForwardingException(
                                GatewayForwardingException.Code.INTERFACE_UNAVAILABLE,
                                "selected Agent is no longer available")))
                        .flatMap(agent -> Mono.justOrEmpty(command.gatewayTaskId())
                                .filter(taskId -> !taskId.isBlank())
                                .flatMap(taskId -> taskRouteStore.find(command.tenantId(), taskId))
                                .flatMap(route -> loadBalancer.choosePinned(agent, route.instanceId(), command, context)
                                        .flatMap(instance -> forwardToInstance(withUpstreamIdentifiers(command, route),
                                                context, createsTask, decision, agent, instance, route)))
                                .switchIfEmpty(loadBalancer.choose(agent, command, context)
                                        .flatMap(instance -> forwardToInstance(command, context, createsTask, decision,
                                                agent, instance, null)))));
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
            effective = withTaskIdentifiers(command, UuidGenerator.next(), UuidGenerator.next());
        }
        GatewayCommand forwarded = effective;
        TaskRoute pending = createsTask && affinityRoute == null && forwarded.gatewayTaskId() != null
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
                .flatMap(response -> adapter.decodeResponse(response).collectList()
                        .onErrorMap(error -> error instanceof GatewayForwardingException ? error
                                : new GatewayForwardingException(
                                        GatewayForwardingException.Code.UPSTREAM_PROTOCOL,
                                        "upstream returned an invalid protocol payload", error))
                        .flatMap(events -> {
                            if (events.stream().anyMatch(event -> event.type()
                                    == io.github.a2ap.gateway.api.model.GatewayEvent.Type.ERROR)) {
                                return Mono.error(new GatewayForwardingException(
                                        GatewayForwardingException.Code.UPSTREAM_PROTOCOL,
                                        "upstream returned a protocol error"));
                            }
                            return completeRoute(command, adapter, pending, affinityRoute, response)
                                    .map(payload -> result(payload, response, decision, selectedInterface));
                        }));
    }

    private Mono<String> completeRoute(GatewayCommand command, ProtocolAdapter adapter, TaskRoute pending,
            TaskRoute affinityRoute, OutboundResponse response) {
        TaskRoute route = pending == null ? affinityRoute : pending;
        if (route == null) {
            return Mono.just(response.body());
        }
        String payload = response.body();
        JsonRpcTaskReference reference = extractTaskReference(adapter, response.body());
        if (reference == null) {
            reference = new JsonRpcTaskReference(null, null);
        }
        if (reference.taskId() != null || reference.contextId() != null) {
            payload = rewriteTaskIdentifiers(adapter, response.body(), reference, route);
        }
        TaskRoute.State state = route.state();
        if (command.operation() == GatewayCommand.Operation.CANCEL_TASK) {
            state = TaskRoute.State.CANCELED;
        }
        else if (pending != null) {
            state = reference.taskId() == null ? TaskRoute.State.COMPLETED : TaskRoute.State.ACTIVE;
        }
        TaskRoute updated = new TaskRoute(route.tenantId(), route.gatewayTaskId(), route.gatewayContextId(),
                route.agentId(), route.instanceId(), route.interfaceKey(), reference.taskId(), reference.contextId(),
                route.protocolBinding(), route.protocolVersion(), route.principalFingerprint(), route.idempotencyKey(),
                state, route.createdAt(), Instant.now(), route.expiresAt());
        return taskRouteStore.save(updated).thenReturn(payload);
    }

    private GatewayResult result(String payload, OutboundResponse response, RouteDecision decision,
            AgentInterface selectedInterface) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("statusCode", response.statusCode());
        metadata.put("decisionId", decision.decisionId());
        metadata.put("interfaceKey", selectedInterface.interfaceKey());
        metadata.put("agentId", decision.agentId());
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
                now.plus(routeTtl));
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
