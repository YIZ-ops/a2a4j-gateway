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

package io.github.a2ap.gateway.api.model;

import java.time.Instant;

/** Immutable idempotency state used to prevent duplicate task creation. */
public record IdempotencyRecord(String tenantId, String key, String requestHash,
        State state, GatewayResult result, Instant createdAt, Instant updatedAt) {

    /** Idempotency lifecycle states. */
    public enum State {
        /** Key has reserved an in-flight request. */
        IN_FLIGHT,
        /** Key has a replayable result. */
        COMPLETED,
        /** Upstream outcome is unknown and must not be retried automatically. */
        OUTCOME_UNKNOWN
    }

    /** Creates a validated immutable idempotency record. */
    public IdempotencyRecord {
        requireText(tenantId, "tenantId");
        requireText(key, "key");
        requireText(requestHash, "requestHash");
        state = state == null ? State.IN_FLIGHT : state;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
