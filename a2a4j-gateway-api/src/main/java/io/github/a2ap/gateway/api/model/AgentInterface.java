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

package io.github.a2ap.gateway.api.model;

/** Immutable A2A interface advertised by one Agent instance. */
public record AgentInterface(String interfaceKey, String endpointUrl, String protocolBinding,
        String protocolVersion, String upstreamTenant) {

    /** Creates a validated immutable Agent interface. */
    public AgentInterface {
        requireText(interfaceKey, "interfaceKey");
        requireText(endpointUrl, "endpointUrl");
        requireText(protocolBinding, "protocolBinding");
        requireText(protocolVersion, "protocolVersion");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
