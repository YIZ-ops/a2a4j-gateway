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

package io.github.a2ap.gateway.core.store;

import io.github.a2ap.gateway.api.model.TaskRoute;
import io.github.a2ap.gateway.api.model.TaskRoutePage;
import io.github.a2ap.gateway.api.model.TaskRouteQuery;
import io.github.a2ap.gateway.api.spi.TaskRouteStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;

/** Bounded in-memory Task affinity store for the single-instance MVP. */
public final class InMemoryTaskRouteStore implements TaskRouteStore {

    private final int maxEntries;

    private final Clock clock;

    private final ConcurrentMap<String, TaskRoute> routes = new ConcurrentHashMap<>();

    private final AtomicLong evictionCount = new AtomicLong();

    private final AtomicLong expiryCount = new AtomicLong();

    /** Creates a store with a bounded capacity and system clock. */
    public InMemoryTaskRouteStore(int maxEntries) {
        this(maxEntries, Clock.systemUTC());
    }

    /** Creates a store with an injectable clock for deterministic expiry tests. */
    public InMemoryTaskRouteStore(int maxEntries, Clock clock) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public Mono<TaskRoute> find(String tenantId, String gatewayTaskId) {
        purgeExpired();
        return Mono.justOrEmpty(routes.get(key(tenantId, gatewayTaskId)));
    }

    @Override
    public Mono<TaskRoutePage> list(TaskRouteQuery query) {
        purgeExpired();
        List<TaskRoute> filtered = routes.values().stream()
                .filter(route -> route.tenantId().equals(query.tenantId()))
                .filter(route -> query.gatewayTaskId() == null || route.gatewayTaskId().equals(query.gatewayTaskId()))
                .filter(route -> query.agentId() == null || route.agentId().equals(query.agentId()))
                .filter(route -> query.states().isEmpty() || query.states().contains(route.state()))
                .sorted(Comparator.comparing(TaskRoute::updatedAt).reversed()
                        .thenComparing(TaskRoute::gatewayTaskId))
                .toList();
        int offset = parsePageToken(query.pageToken());
        int pageSize = query.pageSize() == 0 ? 100 : query.pageSize();
        if (offset >= filtered.size()) {
            return Mono.just(new TaskRoutePage(List.of(), null));
        }
        int end = Math.min(filtered.size(), offset + pageSize);
        String next = end < filtered.size() ? Integer.toString(end) : null;
        return Mono.just(new TaskRoutePage(filtered.subList(offset, end), next));
    }

    @Override
    public synchronized Mono<Void> save(TaskRoute route) {
        purgeExpired();
        String routeKey = key(route.tenantId(), route.gatewayTaskId());
        if (!routes.containsKey(routeKey) && routes.size() >= maxEntries) {
            routes.values().stream().min(Comparator.comparing(TaskRoute::updatedAt)).ifPresent(oldest -> {
                if (routes.remove(key(oldest.tenantId(), oldest.gatewayTaskId()), oldest)) {
                    evictionCount.incrementAndGet();
                }
            });
        }
        routes.put(routeKey, route);
        return Mono.empty();
    }

    @Override
    public synchronized Mono<Void> touch(String tenantId, String gatewayTaskId, Instant expiresAt) {
        purgeExpired();
        TaskRoute route = routes.get(key(tenantId, gatewayTaskId));
        if (route != null) {
            routes.put(key(tenantId, gatewayTaskId), copy(route, route.state(), Instant.now(clock), expiresAt));
        }
        return Mono.empty();
    }

    /** Returns the current number of retained routes after expiry cleanup. */
    public int size() {
        purgeExpired();
        return routes.size();
    }

    /** Returns the configured maximum number of retained routes. */
    public int maxEntries() {
        return maxEntries;
    }

    /** Returns the number of routes evicted because the store was full. */
    public long evictionCount() {
        return evictionCount.get();
    }

    /** Returns the number of routes removed by expiry cleanup. */
    public long expiryCount() {
        purgeExpired();
        return expiryCount.get();
    }

    private TaskRoute copy(TaskRoute route, TaskRoute.State state, Instant updatedAt, Instant expiresAt) {
        return new TaskRoute(route.tenantId(), route.gatewayTaskId(), route.gatewayContextId(), route.agentId(),
                route.instanceId(), route.interfaceKey(), route.upstreamTaskId(), route.upstreamContextId(),
                route.protocolBinding(), route.protocolVersion(), route.principalFingerprint(),
                route.idempotencyKey(), state, route.createdAt(), updatedAt, expiresAt);
    }

    private void purgeExpired() {
        Instant now = Instant.now(clock);
        routes.forEach((routeKey, route) -> {
            if (route.expiresAt() != null && !now.isBefore(route.expiresAt())
                    && routes.remove(routeKey, route)) {
                expiryCount.incrementAndGet();
            }
        });
    }

    private int parsePageToken(String pageToken) {
        if (pageToken == null || pageToken.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(pageToken);
            if (offset < 0) {
                throw new NumberFormatException();
            }
            return offset;
        }
        catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid task route page token");
        }
    }

    private static String key(String tenantId, String gatewayTaskId) {
        if (tenantId == null || tenantId.isBlank() || gatewayTaskId == null || gatewayTaskId.isBlank()) {
            throw new IllegalArgumentException("tenantId and gatewayTaskId must not be blank");
        }
        return tenantId + "\u0000" + gatewayTaskId;
    }

}
