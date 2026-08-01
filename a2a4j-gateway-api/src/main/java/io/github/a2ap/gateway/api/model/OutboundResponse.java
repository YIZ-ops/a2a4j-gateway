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

/** Immutable upstream response chunk delivered to a protocol adapter. */
public record OutboundResponse(ProtocolDescriptor protocol, int statusCode, String body,
        Map<String, String> headers, boolean terminal) {

    /** Creates an immutable outbound response chunk. */
    public OutboundResponse {
        protocol = Objects.requireNonNull(protocol, "protocol");
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be an HTTP status code");
        }
        body = body == null ? "" : body;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

}
