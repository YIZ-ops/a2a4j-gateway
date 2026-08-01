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

package io.github.a2ap.gateway.core.routing;

import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.RoutingContext;
import io.github.a2ap.gateway.api.spi.AgentLoadBalancer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;

/** Weighted least-active selector with bounded per-instance bulkheads and a small circuit breaker. */
public final class WeightedLeastActiveLoadBalancer implements AgentLoadBalancer {

    private static final int DEFAULT_MAX_IN_FLIGHT = 100;

    private static final int DEFAULT_FAILURE_THRESHOLD = 3;

    private static final Duration DEFAULT_OPEN_DURATION = Duration.ofSeconds(10);

    private final int maxInFlight;

    private final int failureThreshold;

    private final Duration openDuration;

    private final ConcurrentMap<String, AtomicInteger> activeRequests = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Circuit> circuits = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, AtomicLong> roundRobin = new ConcurrentHashMap<>();

    /** Creates the default selector with a 100-request instance bulkhead. */
    public WeightedLeastActiveLoadBalancer() {
        this(DEFAULT_MAX_IN_FLIGHT, DEFAULT_FAILURE_THRESHOLD, DEFAULT_OPEN_DURATION);
    }

    /** Creates a selector with explicit bulkhead and circuit settings. */
    public WeightedLeastActiveLoadBalancer(int maxInFlight, int failureThreshold, Duration openDuration) {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }
        if (openDuration == null || openDuration.isZero() || openDuration.isNegative()) {
            throw new IllegalArgumentException("openDuration must be positive");
        }
        this.maxInFlight = maxInFlight;
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    @Override
    public Mono<AgentInstance> choose(AgentDefinition agent, GatewayCommand command, RoutingContext context) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        if (context.expired()) {
            return Mono.error(new RouteResolutionException(RouteResolutionException.Code.DEADLINE_EXCEEDED,
                    "routing deadline has elapsed"));
        }
        if (!agent.enabled()) {
            return unavailable("Agent is disabled");
        }
        Instant now = Instant.now();
        List<AgentInstance> candidates = agent.instances().stream()
                .filter(AgentInstance::eligibleForNewWork)
                .filter(instance -> circuit(instanceKey(agent, instance)).canAttempt(now, openDuration))
                .sorted(Comparator.comparingDouble(instance -> score(agent, instance)))
                .toList();
        if (candidates.isEmpty()) {
            return unavailable("no healthy Agent instance is eligible");
        }
        List<AgentInstance> ordered = tieBreak(agent, candidates);
        for (AgentInstance instance : ordered) {
            String key = instanceKey(agent, instance);
            AtomicInteger active = activeRequests.computeIfAbsent(key, ignored -> new AtomicInteger());
            if (!tryAcquire(active)) {
                continue;
            }
            Circuit circuit = circuit(key);
            if (!circuit.tryPermit(Instant.now(), openDuration)) {
                active.decrementAndGet();
                continue;
            }
            return Mono.just(instance);
        }
        return unavailable("all eligible Agent instances are at their bulkhead limit");
    }

    @Override
    public Mono<AgentInstance> choosePinned(AgentDefinition agent, String instanceId,
            GatewayCommand command, RoutingContext context) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        if (context.expired()) {
            return Mono.error(new RouteResolutionException(RouteResolutionException.Code.DEADLINE_EXCEEDED,
                    "routing deadline has elapsed"));
        }
        if (!agent.enabled()) {
            return unavailable("Agent is disabled");
        }
        AgentInstance pinned = agent.instances().stream()
                .filter(instance -> instance.instanceId().equals(instanceId))
                .findFirst().orElse(null);
        if (pinned == null) {
            return unavailable("pinned Agent instance is unavailable");
        }
        AtomicInteger active = activeRequests.computeIfAbsent(instanceKey(agent, pinned),
                ignored -> new AtomicInteger());
        if (!tryAcquire(active)) {
            return unavailable("pinned Agent instance is at its bulkhead limit");
        }
        return Mono.just(pinned);
    }

    @Override
    public void release(AgentDefinition agent, AgentInstance instance) {
        if (agent == null || instance == null) {
            return;
        }
        AtomicInteger active = activeRequests.get(instanceKey(agent, instance));
        if (active == null) {
            return;
        }
        active.updateAndGet(value -> value > 0 ? value - 1 : 0);
    }

    /** Records a successful upstream outcome and closes any half-open circuit. */
    public void recordSuccess(AgentDefinition agent, AgentInstance instance) {
        if (agent != null && instance != null) {
            circuit(instanceKey(agent, instance)).success();
        }
    }

    /** Records a failed upstream outcome and opens the circuit after the configured threshold. */
    public void recordFailure(AgentDefinition agent, AgentInstance instance) {
        if (agent != null && instance != null) {
            circuit(instanceKey(agent, instance)).failure(failureThreshold);
        }
    }

    /** Returns the current number of reserved requests for an instance. */
    public int activeRequests(AgentDefinition agent, AgentInstance instance) {
        if (agent == null || instance == null) {
            return 0;
        }
        AtomicInteger active = activeRequests.get(instanceKey(agent, instance));
        return active == null ? 0 : active.get();
    }

    /** Returns a diagnostic circuit state without exposing upstream details. */
    public CircuitState circuitState(AgentDefinition agent, AgentInstance instance) {
        if (agent == null || instance == null) {
            return CircuitState.CLOSED;
        }
        return circuit(instanceKey(agent, instance)).state(Instant.now(), openDuration);
    }

    private List<AgentInstance> tieBreak(AgentDefinition agent, List<AgentInstance> sorted) {
        double best = score(agent, sorted.get(0));
        List<AgentInstance> tied = new ArrayList<>();
        List<AgentInstance> rest = new ArrayList<>();
        for (AgentInstance instance : sorted) {
            if (Math.abs(score(agent, instance) - best) < 0.000000001d) {
                tied.add(instance);
            }
            else {
                rest.add(instance);
            }
        }
        if (tied.size() < 2) {
            return sorted;
        }
        tied.sort(Comparator.comparing(AgentInstance::instanceId));
        AtomicLong cursor = roundRobin.computeIfAbsent(agentKey(agent), ignored -> new AtomicLong());
        int offset = (int) Math.floorMod(cursor.getAndIncrement(), tied.size());
        List<AgentInstance> ordered = new ArrayList<>(sorted.size());
        ordered.addAll(tied.subList(offset, tied.size()));
        ordered.addAll(tied.subList(0, offset));
        ordered.addAll(rest);
        return ordered;
    }

    private double score(AgentDefinition agent, AgentInstance instance) {
        return (double) activeRequests(agent, instance) / instance.weight();
    }

    private Circuit circuit(String key) {
        return circuits.computeIfAbsent(key, ignored -> new Circuit());
    }

    private boolean tryAcquire(AtomicInteger active) {
        while (true) {
            int current = active.get();
            if (current >= maxInFlight) {
                return false;
            }
            if (active.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private Mono<AgentInstance> unavailable(String message) {
        return Mono.error(new RouteResolutionException(RouteResolutionException.Code.AGENT_UNAVAILABLE, message));
    }

    private String instanceKey(AgentDefinition agent, AgentInstance instance) {
        return agentKey(agent) + "\u0000" + instance.instanceId();
    }

    private String agentKey(AgentDefinition agent) {
        return agent.tenantId() + "\u0000" + agent.agentId();
    }

    /** Circuit states exposed for metrics and tests. */
    public enum CircuitState {
        /** Requests are allowed normally. */
        CLOSED,
        /** Requests are blocked until the open interval ends. */
        OPEN,
        /** One probe is allowed while other requests remain blocked. */
        HALF_OPEN
    }

    private static final class Circuit {

        private final AtomicInteger failures = new AtomicInteger();

        private final AtomicBoolean halfOpenProbe = new AtomicBoolean();

        private volatile Instant openedAt;

        private boolean canAttempt(Instant now, Duration openDuration) {
            Instant opened = openedAt;
            return opened == null || !now.isBefore(opened.plus(openDuration));
        }

        private boolean tryPermit(Instant now, Duration openDuration) {
            Instant opened = openedAt;
            if (opened == null) {
                return true;
            }
            if (now.isBefore(opened.plus(openDuration))) {
                return false;
            }
            return halfOpenProbe.compareAndSet(false, true);
        }

        private void success() {
            failures.set(0);
            openedAt = null;
            halfOpenProbe.set(false);
        }

        private void failure(int threshold) {
            halfOpenProbe.set(false);
            if (failures.incrementAndGet() >= threshold) {
                openedAt = Instant.now();
            }
        }

        private CircuitState state(Instant now, Duration openDuration) {
            Instant opened = openedAt;
            if (opened == null) {
                return CircuitState.CLOSED;
            }
            if (now.isBefore(opened.plus(openDuration))) {
                return CircuitState.OPEN;
            }
            return CircuitState.HALF_OPEN;
        }

    }

}
