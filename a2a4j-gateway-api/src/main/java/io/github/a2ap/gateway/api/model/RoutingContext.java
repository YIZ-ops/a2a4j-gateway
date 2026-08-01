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

/** Immutable routing-time context shared by resolver and load-balancer implementations. */
public record RoutingContext(String requestId, String traceId, Instant deadline,
        Map<String, Object> metadata) {

    /** Creates a routing context with defensive metadata copying. */
    public RoutingContext {
        requireText(requestId, "requestId");
        requireText(traceId, "traceId");
        deadline = deadline == null ? Instant.now().plusSeconds(30) : deadline;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Returns whether this context deadline has elapsed. */
    public boolean expired() {
        return !Instant.now().isBefore(deadline);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
