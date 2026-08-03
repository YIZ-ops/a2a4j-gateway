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

package io.github.a2ap.gateway.core.exception;

/** Categorized outbound transport failure for retry and circuit decisions. */
public final class AgentTransportException extends RuntimeException {

    /** Transport failure categories. */
    public enum Code {
        /** Connection or response phase timed out. */
        TIMEOUT,
        /** Upstream returned a non-success HTTP response. */
        UPSTREAM_HTTP,
        /** Response body exceeded the configured bound. */
        RESPONSE_TOO_LARGE,
        /** URL or DNS policy rejected the destination. */
        NETWORK_POLICY,
        /** Connection or protocol exchange failed. */
        NETWORK
    }

    private final Code code;

    /** Creates a categorized transport failure. */
    public AgentTransportException(Code code, String message) {
        super(message);
        this.code = code;
    }

    /** Returns the stable transport failure category. */
    public Code code() {
        return code;
    }

}
