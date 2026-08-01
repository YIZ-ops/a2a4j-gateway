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

/** A2A 1.0 JSON-RPC error codes used by gateway adapters. */
public final class GatewayErrorCodes {

    /** JSON-RPC parse error. */
    public static final int PARSE_ERROR = -32700;

    /** JSON-RPC invalid request error. */
    public static final int INVALID_REQUEST = -32600;

    /** JSON-RPC method not found error. */
    public static final int METHOD_NOT_FOUND = -32601;

    /** JSON-RPC invalid params error. */
    public static final int INVALID_PARAMS = -32602;

    /** JSON-RPC internal error. */
    public static final int INTERNAL_ERROR = -32603;

    /** A2A task was not found. */
    public static final int TASK_NOT_FOUND = -32001;

    /** A2A task cannot be canceled in its current state. */
    public static final int TASK_NOT_CANCELABLE = -32002;

    /** A2A push notifications are not supported. */
    public static final int PUSH_NOTIFICATION_NOT_SUPPORTED = -32003;

    /** The requested A2A operation is not supported. */
    public static final int UNSUPPORTED_OPERATION = -32004;

    /** No requested content type is supported. */
    public static final int CONTENT_TYPE_NOT_SUPPORTED = -32005;

    /** Upstream returned an invalid A2A response. */
    public static final int INVALID_AGENT_RESPONSE = -32006;

    /** Extended Agent Card is not configured. */
    public static final int EXTENDED_AGENT_CARD_NOT_CONFIGURED = -32007;

    /** A required A2A extension is not supported. */
    public static final int EXTENSION_SUPPORT_REQUIRED = -32008;

    /** Requested A2A protocol version is not supported. */
    public static final int VERSION_NOT_SUPPORTED = -32009;

    private GatewayErrorCodes() {
    }

}
