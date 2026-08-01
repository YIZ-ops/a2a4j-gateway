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

package io.github.a2ap.gateway.core;

import io.github.a2ap.gateway.api.GatewayProtocol;

/** Default MVP registry accepting A2A 1.0 JSON-RPC and HTTP+JSON bindings. */
public final class DefaultGatewayProtocolRegistry implements GatewayProtocolRegistry {

    @Override
    public boolean supportsVersion(String protocolVersion) {
        return GatewayProtocol.VERSION.equals(protocolVersion);
    }

    @Override
    public boolean supportsBinding(String protocolBinding) {
        return GatewayProtocol.JSON_RPC_BINDING.equals(protocolBinding)
                || GatewayProtocol.HTTP_JSON_BINDING.equals(protocolBinding);
    }

}
