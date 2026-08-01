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
import java.util.Map;
import java.util.Objects;

/** Immutable event emitted by a gateway operation or protocol adapter. */
public record GatewayEvent(Type type, String tenantId, String gatewayTaskId, Object payload,
        Instant occurredAt, Map<String, Object> metadata) {

    /** Event categories that are stable across protocol bindings. */
    public enum Type {
        /** Task created or accepted upstream. */
        TASK_ACCEPTED,
        /** Task status changed. */
        TASK_STATUS,
        /** Task artifact or message data arrived. */
        TASK_ARTIFACT,
        /** Task reached a terminal state. */
        TASK_COMPLETED,
        /** Gateway or upstream failure. */
        ERROR
    }

    /** Creates a validated immutable event. */
    public GatewayEvent {
        type = Objects.requireNonNull(type, "type");
        requireText(tenantId, "tenantId");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
