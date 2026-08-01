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

import java.util.Map;

/** Immutable result stored for idempotency replay. */
public record GatewayResult(boolean success, Object payload, String errorCode,
        Map<String, Object> metadata) {

    /** Creates an immutable result. */
    public GatewayResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Creates a successful result. */
    public static GatewayResult success(Object payload) {
        return new GatewayResult(true, payload, null, Map.of());
    }

    /** Creates a failed result. */
    public static GatewayResult failure(String errorCode, Object payload) {
        return new GatewayResult(false, payload, errorCode, Map.of());
    }

}
