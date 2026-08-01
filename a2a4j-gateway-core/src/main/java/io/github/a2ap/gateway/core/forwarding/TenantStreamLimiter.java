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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded per-tenant stream quota that never blocks a Reactor event-loop thread. */
public final class TenantStreamLimiter {

    private final int maxPerTenant;

    private final ConcurrentHashMap<String, AtomicInteger> active = new ConcurrentHashMap<>();

    /** Creates a limiter with a positive per-tenant stream limit. */
    public TenantStreamLimiter(int maxPerTenant) {
        if (maxPerTenant < 1) {
            throw new IllegalArgumentException("maxPerTenant must be positive");
        }
        this.maxPerTenant = maxPerTenant;
    }

    /** Attempts to reserve one stream for a tenant. */
    public boolean tryAcquire(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        AtomicInteger count = active.computeIfAbsent(tenantId, ignored -> new AtomicInteger());
        while (true) {
            int current = count.get();
            if (current >= maxPerTenant) {
                return false;
            }
            if (!count.compareAndSet(current, current + 1)) {
                continue;
            }
            return true;
        }
    }

    /** Releases one previously acquired stream reservation. */
    public void release(String tenantId) {
        AtomicInteger count = active.get(tenantId);
        if (count == null) {
            return;
        }
        int remaining = count.decrementAndGet();
        if (remaining <= 0) {
            active.remove(tenantId, count);
        }
    }

    /** Returns the current active count for diagnostics and tests. */
    public int active(String tenantId) {
        AtomicInteger count = active.get(tenantId);
        return count == null ? 0 : count.get();
    }

}
