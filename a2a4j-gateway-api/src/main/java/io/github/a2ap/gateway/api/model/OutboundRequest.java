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

/** Immutable request emitted by a protocol adapter for an upstream Agent. */
public record OutboundRequest(ProtocolDescriptor protocol, String endpointUrl, String body,
        Map<String, String> headers, String gatewayTaskId, String httpMethod) {

    private static final String DEFAULT_HTTP_METHOD = "POST";

    /** Creates a POST outbound request, preserving the original request contract. */
    public OutboundRequest(ProtocolDescriptor protocol, String endpointUrl, String body,
            Map<String, String> headers, String gatewayTaskId) {
        this(protocol, endpointUrl, body, headers, gatewayTaskId, DEFAULT_HTTP_METHOD);
    }

    /** Creates a validated immutable outbound request. */
    public OutboundRequest {
        protocol = Objects.requireNonNull(protocol, "protocol");
        requireText(endpointUrl, "endpointUrl");
        body = body == null ? "" : body;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        httpMethod = requireHttpMethod(httpMethod);
    }

    private static String requireHttpMethod(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("httpMethod must not be blank");
        }
        return value.toUpperCase(java.util.Locale.ROOT);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
