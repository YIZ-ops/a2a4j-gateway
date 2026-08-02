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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.a2ap.gateway.api.model.TaskRoute;
import io.github.a2ap.gateway.api.model.TaskRoutePage;
import io.github.a2ap.gateway.api.model.TaskRouteQuery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryTaskRouteStoreTest {

    @Test
    void retainsTenantScopedRoutesAndEvictsOldestWhenBounded() {
        InMemoryTaskRouteStore store = new InMemoryTaskRouteStore(1);
        store.save(route("tenant-a", "task-1", Instant.now())).block();
        store.save(route("tenant-a", "task-2", Instant.now().plusSeconds(1))).block();

        assertTrue(store.find("tenant-a", "task-1").blockOptional().isEmpty());
        assertEquals("task-2", store.find("tenant-a", "task-2").block().gatewayTaskId());
        assertEquals(1, store.list(new TaskRouteQuery("tenant-a", null, null, java.util.Set.of(), 10, null))
                .block().routes().size());
    }

    @Test
    void filtersByAgentAndTimestampAndReportsTheUnpagedTotal() {
        InMemoryTaskRouteStore store = new InMemoryTaskRouteStore(8);
        Instant now = Instant.now();
        store.save(routeWithAgent("task-a", "agent-a", now)).block();
        store.save(routeWithAgent("task-b", "agent-b", now.plusSeconds(1))).block();

        TaskRouteQuery query = new TaskRouteQuery("tenant-a", null, "agent-a", Set.of(), 10, null, "fp-a", null,
                now);
        TaskRoutePage page = store.list(query).block();
        assertEquals(1, page.totalSize());
        assertEquals("task-a", page.routes().get(0).gatewayTaskId());
    }

    @Test
    void filtersSortsAndCursorsByStatusTimestampInsteadOfRouteTouchTime() {
        InMemoryTaskRouteStore store = new InMemoryTaskRouteStore(8);
        Instant statusOne = Instant.parse("2026-01-01T00:00:00Z");
        Instant statusTwo = statusOne.plusSeconds(10);
        store.save(routeWithStatus("task-a", statusOne, statusTwo.plusSeconds(100))).block();
        store.save(routeWithStatus("task-b", statusTwo, statusOne.plusSeconds(100))).block();

        TaskRouteQuery query = new TaskRouteQuery("tenant-a", null, null, Set.of(), 1, null);
        TaskRoutePage first = store.list(query).block();
        assertEquals("task-b", first.routes().get(0).gatewayTaskId());
        TaskRoutePage after = store.list(new TaskRouteQuery("tenant-a", null, null, Set.of(), 1,
                first.nextPageToken())).block();
        assertEquals("task-a", after.routes().get(0).gatewayTaskId());

        TaskRoutePage filtered = store.list(new TaskRouteQuery("tenant-a", null, null, Set.of(), 10, null,
                null, null, statusOne.plusSeconds(5))).block();
        assertEquals(List.of("task-b"), filtered.routes().stream().map(TaskRoute::gatewayTaskId).toList());
    }

    @Test
    void usesAnOpaqueCursorThatDoesNotRepeatRowsWhenRoutesAreAdded() {
        InMemoryTaskRouteStore store = new InMemoryTaskRouteStore(8);
        Instant now = Instant.now();
        store.save(route("tenant-a", "task-a", now)).block();
        store.save(route("tenant-a", "task-b", now.plusSeconds(1))).block();

        TaskRoutePage first = store.list(new TaskRouteQuery("tenant-a", null, null, Set.of(), 1, null)).block();
        assertEquals("task-b", first.routes().get(0).gatewayTaskId());
        assertTrue(first.nextPageToken() != null && !first.nextPageToken().matches("\\d+"));

        store.save(route("tenant-a", "task-c", now.plusSeconds(2))).block();
        TaskRoutePage second = store.list(new TaskRouteQuery("tenant-a", null, null, Set.of(), 1,
                first.nextPageToken())).block();
        assertEquals("task-a", second.routes().get(0).gatewayTaskId());
        assertEquals(3, second.totalSize());
    }

    private TaskRoute routeWithAgent(String taskId, String agentId, Instant updatedAt) {
        return new TaskRoute("tenant-a", taskId, "context-1", agentId, "instance-1", "jsonrpc", null, null,
                "JSONRPC", "1.0", "fp-a", null, TaskRoute.State.ACTIVE, updatedAt, updatedAt, null);
    }

    private TaskRoute routeWithStatus(String taskId, Instant statusTimestamp, Instant updatedAt) {
        return new TaskRoute("tenant-a", taskId, "context-1", "agent-a", "instance-1", "jsonrpc", null, null,
                "JSONRPC", "1.0", "fp-a", null, TaskRoute.State.ACTIVE, statusTimestamp, updatedAt, null,
                Map.of(), statusTimestamp);
    }

    @Test
    void expiresRoutesAndTouchUpdatesExpiryAndTimestamp() {
        Instant initial = Instant.parse("2026-01-01T00:00:00Z");
        MutableClock clock = new MutableClock(initial);
        InMemoryTaskRouteStore store = new InMemoryTaskRouteStore(10, clock);
        store.save(route("tenant-a", "task-1", initial.plusSeconds(5))).block();
        clock.advance(Duration.ofSeconds(1));
        store.touch("tenant-a", "task-1", initial.plusSeconds(30)).block();
        assertEquals(initial.plusSeconds(1), store.find("tenant-a", "task-1").block().updatedAt());
        clock.advance(Duration.ofSeconds(30));
        assertTrue(store.find("tenant-a", "task-1").blockOptional().isEmpty());
    }

    private TaskRoute route(String tenant, String task, Instant createdAt) {
        return new TaskRoute(tenant, task, "context-1", "agent-a", "instance-1", "jsonrpc", null, null,
                "JSONRPC", "1.0", "fingerprint", null, TaskRoute.State.ACTIVE, createdAt, createdAt,
                createdAt.plusSeconds(60));
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
