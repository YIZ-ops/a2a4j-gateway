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

package io.github.a2ap.gateway.core.forwarding;

/** A normalized upstream A2A error raised before a streaming response starts. */
public final class GatewayUpstreamException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int httpStatus;

    private final int rpcCode;

    private final String reason;

    /** Creates an exception carrying the binding-neutral upstream error mapping. */
    public GatewayUpstreamException(int httpStatus, int rpcCode, String reason, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.rpcCode = rpcCode;
        this.reason = reason;
    }

    /** Returns the HTTP status for the HTTP+JSON binding. */
    public int httpStatus() {
        return httpStatus;
    }

    /** Returns the JSON-RPC error code for the JSON-RPC binding. */
    public int rpcCode() {
        return rpcCode;
    }

    /** Returns the canonical A2A error reason, when one was identified. */
    public String reason() {
        return reason;
    }

}
