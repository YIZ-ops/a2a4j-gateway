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

import java.util.Set;

/** Immutable allow-list for protocol versions and bindings used by an Agent. */
public record ProtocolPolicy(Set<String> protocolVersions, Set<String> protocolBindings) {

    /** Creates an immutable protocol policy. */
    public ProtocolPolicy {
        protocolVersions = protocolVersions == null ? Set.of() : Set.copyOf(protocolVersions);
        protocolBindings = protocolBindings == null ? Set.of() : Set.copyOf(protocolBindings);
    }

    /** Returns the default A2A 1.0 JSON-RPC/HTTP+JSON policy. */
    public static ProtocolPolicy a2aV1Mvp() {
        return new ProtocolPolicy(Set.of("1.0"), Set.of("JSONRPC", "HTTP+JSON"));
    }

    /** Returns whether the policy permits the supplied version and binding. */
    public boolean allows(String version, String binding) {
        return protocolVersions.contains(version) && protocolBindings.contains(binding);
    }

}
