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

/** Sanitized error raised by the protocol-neutral forwarding orchestrator. */
public final class GatewayForwardingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Code code;

    /** Creates a forwarding error without exposing credential or upstream body details. */
    public GatewayForwardingException(Code code, String message) {
        super(message);
        this.code = code;
    }

    /** Creates a forwarding error while retaining a local cause for diagnostics. */
    public GatewayForwardingException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Returns the stable gateway error category. */
    public Code code() {
        return code;
    }

    /** Stable error categories suitable for protocol mapping and metrics. */
    public enum Code {
        /** A request cannot be encoded for the selected interface. */
        INVALID_REQUEST,
        /** The selected Agent does not advertise the required interface. */
        INTERFACE_UNAVAILABLE,
        /** Outbound credential resolution failed. */
        CREDENTIALS_UNAVAILABLE,
        /** The transport failed before a definitive upstream response. */
        TRANSPORT,
        /** The upstream response was not valid for the selected protocol. */
        UPSTREAM_PROTOCOL,
        /** An idempotency key is already executing. */
        DUPLICATE_IN_FLIGHT,
        /** An earlier request's upstream outcome cannot be safely retried. */
        OUTCOME_UNKNOWN,
        /** A tenant or principal exceeded the configured stream quota. */
        RATE_LIMITED
    }

}
