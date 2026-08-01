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

package io.github.a2ap.gateway.api;

/** Canonical HTTP header names used at the gateway boundary. */
public final class GatewayHeaders {

    /** A2A protocol version negotiated for the request. */
    public static final String A2A_VERSION = "A2A-Version";

    /** Comma-separated A2A extension URIs requested by the caller. */
    public static final String A2A_EXTENSIONS = "A2A-Extensions";

    /** Explicit logical agent route selected by the caller. */
    public static final String TARGET_AGENT = "X-A2A-Target-Agent";

    /** Optional skill route selected by the caller. */
    public static final String TARGET_SKILL = "X-A2A-Target-Skill";

    /** Caller supplied idempotency key for task creation. */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** Gateway request correlation identifier. */
    public static final String GATEWAY_REQUEST_ID = "X-Gateway-Request-Id";

    /** Gateway task identifier used for sticky follow-up operations. */
    public static final String GATEWAY_TASK_ID = "X-Gateway-Task-Id";

    /** Last upstream event identifier presented when a stream is resumed. */
    public static final String LAST_EVENT_ID = "Last-Event-ID";

    /** Internal operation marker used by the HTTP+JSON controller adapter. */
    public static final String GATEWAY_OPERATION = "X-Gateway-Operation";

    /** W3C trace context parent header. */
    public static final String TRACEPARENT = "traceparent";

    /** W3C trace context state header. */
    public static final String TRACESTATE = "tracestate";

    private GatewayHeaders() {
    }

}
