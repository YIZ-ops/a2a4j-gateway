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

import io.github.a2ap.core.protocol.v1.A2AProtocolV1;
import java.util.Set;

/** Gateway-facing view of the A2A 1.0 protocol surface. */
public final class GatewayProtocol {

    /** The only protocol version enabled by the MVP. */
    public static final String VERSION = A2AProtocolV1.VERSION;

    /** JSON-RPC binding supported by the MVP. */
    public static final String JSON_RPC_BINDING = A2AProtocolV1.JSON_RPC_BINDING;

    /** HTTP+JSON binding supported by the MVP. */
    public static final String HTTP_JSON_BINDING = A2AProtocolV1.HTTP_JSON_BINDING;

    /** gRPC binding reserved for a later gateway increment. */
    public static final String GRPC_BINDING = A2AProtocolV1.GRPC_BINDING;

    /** Agent Card discovery path defined by A2A 1.0. */
    public static final String AGENT_CARD_PATH = A2AProtocolV1.AGENT_CARD_PATH;

    /** MVP JSON-RPC operations. */
    public static final Set<String> JSON_RPC_METHODS = A2AProtocolV1.JSON_RPC_METHODS;

    private GatewayProtocol() {
    }

}
