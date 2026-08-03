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

import io.github.a2ap.gateway.api.model.GatewayResult;
import io.github.a2ap.gateway.api.model.IdempotencyRecord;
import io.github.a2ap.gateway.api.spi.IdempotencyStore;
import io.github.a2ap.gateway.core.exception.IdempotencyConflictException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;

/** Bounded in-memory idempotency store that prevents duplicate task creation in the MVP. */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private final int maxEntries;

    private final Clock clock;

    private final Duration retention;

    private final ConcurrentMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    private final AtomicLong evictionCount = new AtomicLong();

    private final AtomicLong expiryCount = new AtomicLong();

    /** Creates a store with a bounded capacity and system clock. */
    public InMemoryIdempotencyStore(int maxEntries) {
        this(maxEntries, Duration.ofHours(24), Clock.systemUTC());
    }

    /** Creates a store with an injectable clock for deterministic expiry tests. */
    public InMemoryIdempotencyStore(int maxEntries, Clock clock) {
        this(maxEntries, Duration.ofHours(24), clock);
    }

    /** Creates a store with explicit retention and an injectable clock. */
    public InMemoryIdempotencyStore(int maxEntries, Duration retention, Clock clock) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        this.maxEntries = maxEntries;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.retention = retention;
    }

    @Override
    public Mono<IdempotencyRecord> find(String tenantId, String key) {
        purgeExpired();
        return Mono.justOrEmpty(records.get(key(tenantId, key)));
    }

    @Override
    public synchronized Mono<IdempotencyRecord> begin(String tenantId, String key, String requestHash) {
        purgeExpired();
        String recordKey = key(tenantId, key);
        IdempotencyRecord existing = records.get(recordKey);
        if (existing != null) {
            if (!existing.requestHash().equals(requestHash)) {
                return Mono.error(new IdempotencyConflictException("idempotency key was reused with a different request"));
            }
            return Mono.just(existing);
        }
        if (records.size() >= maxEntries) {
            records.values().stream().min(Comparator.comparing(IdempotencyRecord::updatedAt)).ifPresent(oldest -> {
                if (records.remove(key(oldest.tenantId(), oldest.key()), oldest)) {
                    evictionCount.incrementAndGet();
                }
            });
        }
        Instant now = Instant.now(clock);
        IdempotencyRecord created = new IdempotencyRecord(tenantId, key, requestHash,
                IdempotencyRecord.State.IN_FLIGHT, null, now, now);
        records.put(recordKey, created);
        return Mono.just(created);
    }

    @Override
    public synchronized Mono<Void> complete(String tenantId, String key, GatewayResult result) {
        update(tenantId, key, existing -> new IdempotencyRecord(existing.tenantId(), existing.key(),
                existing.requestHash(), IdempotencyRecord.State.COMPLETED, result, existing.createdAt(),
                Instant.now(clock)));
        return Mono.empty();
    }

    @Override
    public synchronized Mono<Void> markOutcomeUnknown(String tenantId, String key) {
        update(tenantId, key, existing -> new IdempotencyRecord(existing.tenantId(), existing.key(),
                existing.requestHash(), IdempotencyRecord.State.OUTCOME_UNKNOWN, existing.result(),
                existing.createdAt(), Instant.now(clock)));
        return Mono.empty();
    }

    /** Returns the current number of retained idempotency records after cleanup. */
    public int size() {
        purgeExpired();
        return records.size();
    }

    /** Returns the configured maximum number of retained records. */
    public int maxEntries() {
        return maxEntries;
    }

    /** Returns the number of records evicted because the store was full. */
    public long evictionCount() {
        return evictionCount.get();
    }

    /** Returns the number of records removed by retention cleanup. */
    public long expiryCount() {
        purgeExpired();
        return expiryCount.get();
    }

    private void update(String tenantId, String key,
            java.util.function.Function<IdempotencyRecord, IdempotencyRecord> updater) {
        purgeExpired();
        String recordKey = key(tenantId, key);
        IdempotencyRecord existing = records.get(recordKey);
        if (existing == null) {
            throw new IllegalArgumentException("idempotency key is not reserved");
        }
        records.put(recordKey, updater.apply(existing));
    }

    private void purgeExpired() {
        Instant now = Instant.now(clock);
        records.forEach((recordKey, record) -> {
            if (!now.isBefore(record.updatedAt().plus(retention)) && records.remove(recordKey, record)) {
                expiryCount.incrementAndGet();
            }
        });
    }

    private static String key(String tenantId, String key) {
        if (tenantId == null || tenantId.isBlank() || key == null || key.isBlank()) {
            throw new IllegalArgumentException("tenantId and key must not be blank");
        }
        return tenantId + "\u0000" + key;
    }

}
