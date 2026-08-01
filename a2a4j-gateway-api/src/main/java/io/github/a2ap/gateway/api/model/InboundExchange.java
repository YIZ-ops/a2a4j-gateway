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
import java.util.Objects;

/**
 * Immutable inbound protocol exchange passed to a {@code ProtocolAdapter}.
 */
public record InboundExchange(ProtocolDescriptor protocol, String body, Map<String, String> headers,
        String requestId, PrincipalContext principal) {

    /**
     * Creates an exchange without an attached authentication context.
     *
     * @param protocol protocol descriptor
     * @param body inbound body
     * @param headers inbound headers
     * @param requestId gateway request id
     */
    public InboundExchange(ProtocolDescriptor protocol, String body, Map<String, String> headers,
            String requestId) {
        this(protocol, body, headers, requestId, null);
    }

    /** Creates a validated immutable inbound exchange. */
    public InboundExchange {
        protocol = Objects.requireNonNull(protocol, "protocol");
        body = body == null ? "" : body;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        requireText(requestId, "requestId");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
