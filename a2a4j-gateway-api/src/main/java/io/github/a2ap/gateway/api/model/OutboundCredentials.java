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

/** Secret-bearing outbound credential value with a redacted string representation. */
public final class OutboundCredentials {

    private final String scheme;

    private final String value;

    /** Creates outbound credentials for an upstream transport. */
    public OutboundCredentials(String scheme, String value) {
        if (scheme == null || scheme.isBlank()) {
            throw new IllegalArgumentException("scheme must not be blank");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        this.scheme = scheme;
        this.value = value;
    }

    /** Returns the authentication scheme. */
    public String scheme() {
        return scheme;
    }

    /** Returns the secret value for transport use. */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return "OutboundCredentials{scheme='" + scheme + "', value='[REDACTED]'}";
    }

}
