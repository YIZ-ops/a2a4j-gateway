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

package io.github.a2ap.gateway.spring.exception;

/** Sanitized HTTP boundary failure used before an SSE response is committed. */
public final class GatewayHttpException extends RuntimeException {

    private final int status;

    private final String code;

    /** Creates an HTTP error with a stable public code. */
    public GatewayHttpException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** Returns the HTTP status. */
    public int status() {
        return status;
    }

    /** Returns the protocol-safe error code. */
    public String code() {
        return code;
    }

}
