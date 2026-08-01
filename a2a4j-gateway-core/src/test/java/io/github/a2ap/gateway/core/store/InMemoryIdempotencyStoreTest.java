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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.a2ap.gateway.api.model.GatewayResult;
import io.github.a2ap.gateway.api.model.IdempotencyRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InMemoryIdempotencyStoreTest {

    @Test
    void reservesCompletesAndRejectsHashReuse() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(10);
        IdempotencyRecord first = store.begin("tenant-a", "key-1", "hash-1").block();
        assertEquals(IdempotencyRecord.State.IN_FLIGHT, first.state());
        store.complete("tenant-a", "key-1", GatewayResult.success("payload")).block();
        assertEquals("payload", store.find("tenant-a", "key-1").block().result().payload());
        assertThrows(IdempotencyConflictException.class,
                () -> store.begin("tenant-a", "key-1", "hash-2").block());
    }

    @Test
    void marksUnknownOutcomeAndExpiresByRetention() {
        Instant initial = Instant.parse("2026-01-01T00:00:00Z");
        MutableClock clock = new MutableClock(initial);
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(10, Duration.ofSeconds(5), clock);
        store.begin("tenant-a", "key-1", "hash-1").block();
        store.markOutcomeUnknown("tenant-a", "key-1").block();
        assertEquals(IdempotencyRecord.State.OUTCOME_UNKNOWN, store.find("tenant-a", "key-1").block().state());
        clock.advance(Duration.ofSeconds(6));
        assertEquals(0, store.size());
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

    }

}
