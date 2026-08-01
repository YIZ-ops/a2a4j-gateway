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
import java.util.Set;

/** Immutable identity and authorization context established at the gateway edge. */
public record PrincipalContext(String tenantId, String subject, Set<String> authorities,
        Map<String, Object> claims, String fingerprint) {

    /** Creates an immutable principal context. */
    public PrincipalContext {
        requireText(tenantId, "tenantId");
        requireText(subject, "subject");
        authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
        claims = claims == null ? Map.of() : Map.copyOf(claims);
        requireText(fingerprint, "fingerprint");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
