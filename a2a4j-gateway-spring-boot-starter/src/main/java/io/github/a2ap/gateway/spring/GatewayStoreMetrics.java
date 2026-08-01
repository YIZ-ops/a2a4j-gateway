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

package io.github.a2ap.gateway.spring;

import io.github.a2ap.gateway.api.spi.IdempotencyStore;
import io.github.a2ap.gateway.api.spi.TaskRouteStore;
import io.github.a2ap.gateway.core.store.InMemoryIdempotencyStore;
import io.github.a2ap.gateway.core.store.InMemoryTaskRouteStore;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/** Registers bounded in-memory Store occupancy gauges for the MVP. */
public final class GatewayStoreMetrics {

    /** Registers gauges for the supplied default stores when a registry is available. */
    public GatewayStoreMetrics(MeterRegistry registry, TaskRouteStore taskRouteStore,
            IdempotencyStore idempotencyStore) {
        if (registry == null) {
            return;
        }
        if (taskRouteStore instanceof InMemoryTaskRouteStore store) {
            registerTaskRouteMetrics(registry, store);
        }
        if (idempotencyStore instanceof InMemoryIdempotencyStore store) {
            registerIdempotencyMetrics(registry, store);
        }
    }

    private void registerTaskRouteMetrics(MeterRegistry registry, InMemoryTaskRouteStore store) {
        Tags tags = Tags.of("store", "task-route");
        registry.gauge("gateway.store.entries", tags, store, InMemoryTaskRouteStore::size);
        registry.gauge("gateway.store.capacity", tags, store, InMemoryTaskRouteStore::maxEntries);
        FunctionCounter.builder("gateway.store.evictions", store, InMemoryTaskRouteStore::evictionCount)
                .tags(tags).register(registry);
        FunctionCounter.builder("gateway.store.expired", store, InMemoryTaskRouteStore::expiryCount)
                .tags(tags).register(registry);
    }

    private void registerIdempotencyMetrics(MeterRegistry registry, InMemoryIdempotencyStore store) {
        Tags tags = Tags.of("store", "idempotency");
        registry.gauge("gateway.store.entries", tags, store, InMemoryIdempotencyStore::size);
        registry.gauge("gateway.store.capacity", tags, store, InMemoryIdempotencyStore::maxEntries);
        FunctionCounter.builder("gateway.store.evictions", store, InMemoryIdempotencyStore::evictionCount)
                .tags(tags).register(registry);
        FunctionCounter.builder("gateway.store.expired", store, InMemoryIdempotencyStore::expiryCount)
                .tags(tags).register(registry);
    }

}
