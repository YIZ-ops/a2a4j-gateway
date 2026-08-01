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

package io.github.a2ap.core.protocol.v1;

import java.util.Set;

/**
 * Stable constants for the A2A 1.0 protocol surface.
 *
 * <p>The normative data model is checked in as {@code specification/a2a.proto}. This
 * class intentionally contains protocol identifiers only; transport and gateway policy
 * belong in their respective modules.</p>
 */
public final class A2AProtocolV1 {

    /** A2A protocol version used by the Gateway MVP. */
    public static final String VERSION = "1.0";

    /** JSON-RPC protocol version. */
    public static final String JSON_RPC_VERSION = "2.0";

    /** A2A version request header. */
    public static final String VERSION_HEADER = "A2A-Version";

    /** A2A extensions request header. */
    public static final String EXTENSIONS_HEADER = "A2A-Extensions";

    /** Public Agent Card well-known path in A2A 1.0. */
    public static final String AGENT_CARD_PATH = "/.well-known/agent-card.json";

    /** JSON-RPC binding identifier. */
    public static final String JSON_RPC_BINDING = "JSONRPC";

    /** HTTP+JSON binding identifier. */
    public static final String HTTP_JSON_BINDING = "HTTP+JSON";

    /** gRPC binding identifier, reserved for a later Gateway milestone. */
    public static final String GRPC_BINDING = "GRPC";

    /** A2A 1.0 JSON-RPC method names. */
    public static final Set<String> JSON_RPC_METHODS = Set.of(
            "SendMessage",
            "SendStreamingMessage",
            "GetTask",
            "ListTasks",
            "CancelTask",
            "SubscribeToTask",
            "CreateTaskPushNotificationConfig",
            "GetTaskPushNotificationConfig",
            "ListTaskPushNotificationConfigs",
            "DeleteTaskPushNotificationConfig",
            "GetExtendedAgentCard");

    private A2AProtocolV1() {
    }

}
