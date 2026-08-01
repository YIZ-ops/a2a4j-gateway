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

import java.util.List;
import java.util.Objects;

/** Deterministic failure raised when a routing decision cannot be made safely. */
public final class RouteResolutionException extends RuntimeException {

    /** Stable categories for protocol and HTTP error mapping in later increments. */
    public enum Code {
        /** Routing context is already past its deadline. */
        DEADLINE_EXCEEDED,
        /** The task affinity record is missing or belongs to another route. */
        TASK_ROUTE_NOT_FOUND,
        /** No enabled Agent matched the supplied constraints. */
        AGENT_NOT_FOUND,
        /** More than one Agent matched without a deterministic tie-breaker. */
        ROUTE_CONFLICT,
        /** A matched Agent has no eligible instance. */
        AGENT_UNAVAILABLE,
        /** The authorization policy rejected the selected Agent. */
        AUTHORIZATION_DENIED
    }

    private final Code code;

    private final List<String> candidates;

    /** Creates a categorized routing failure. */
    public RouteResolutionException(Code code, String message) {
        this(code, message, List.of());
    }

    /** Creates a categorized routing failure with safe candidate identifiers. */
    public RouteResolutionException(Code code, String message, List<String> candidates) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** Returns the stable failure category. */
    public Code code() {
        return code;
    }

    /** Returns candidate Agent ids without endpoint or credential data. */
    public List<String> candidates() {
        return candidates;
    }

}
