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

/** SPI for checking protocol versions and transport bindings accepted by a gateway. */
public interface GatewayProtocolRegistry {

    /**
     * Returns whether the gateway accepts the supplied A2A protocol version.
     *
     * @param protocolVersion protocol version from an Agent Card or request
     * @return whether the version is accepted
     */
    boolean supportsVersion(String protocolVersion);

    /**
     * Returns whether the gateway can use the supplied protocol binding.
     *
     * @param protocolBinding A2A protocol binding name
     * @return whether the binding is accepted
     */
    boolean supportsBinding(String protocolBinding);

}
