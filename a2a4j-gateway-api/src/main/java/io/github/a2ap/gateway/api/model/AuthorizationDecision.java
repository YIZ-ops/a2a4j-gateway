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

/** Immutable result of an authorization policy evaluation. */
public record AuthorizationDecision(boolean allowed, String reason, Map<String, Object> obligations) {

    /** Creates an authorization decision with immutable obligations. */
    public AuthorizationDecision {
        reason = reason == null ? "" : reason;
        obligations = obligations == null ? Map.of() : Map.copyOf(obligations);
    }

    /** Creates an allow decision. */
    public static AuthorizationDecision allow() {
        return new AuthorizationDecision(true, "allowed", Map.of());
    }

    /** Creates a deny decision. */
    public static AuthorizationDecision deny(String reason) {
        return new AuthorizationDecision(false, reason, Map.of());
    }

}
