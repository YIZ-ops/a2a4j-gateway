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

package io.github.a2ap.gateway.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.api.model.ProtocolDescriptor;
import io.github.a2ap.gateway.api.model.TargetHint;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultAgentInterfaceSelectorTest {

    private final DefaultAgentInterfaceSelector selector = new DefaultAgentInterfaceSelector();

    @Test
    void prefersJsonRpcButFallsBackToHttpJsonOnlyInterface() {
        AgentInstance both = instance(List.of(
                new AgentInterface("http", "http://agent/http", "HTTP+JSON", "1.0", null),
                new AgentInterface("rpc", "http://agent/rpc", "JSONRPC", "1.0", null)));
        assertEquals("rpc", selector.choose(both, ProtocolDescriptor.httpJson(false), command()).block().interfaceKey());

        AgentInstance httpOnly = instance(List.of(
                new AgentInterface("http", "http://agent/http", "HTTP+JSON", "1.0", null)));
        assertEquals("http", selector.choose(httpOnly, ProtocolDescriptor.jsonRpc(), command()).block().interfaceKey());
    }

    @Test
    void ignoresUnsupportedVersionsAndBindings() {
        AgentInstance unsupported = instance(List.of(
                new AgentInterface("old", "http://agent/old", "JSONRPC", "0.2.1", null),
                new AgentInterface("grpc", "http://agent/grpc", "GRPC", "1.0", null)));
        assertTrue(selector.choose(unsupported, ProtocolDescriptor.jsonRpc(), command()).blockOptional().isEmpty());
    }

    private AgentInstance instance(List<AgentInterface> interfaces) {
        return new AgentInstance("instance-1", "http://agent/card", interfaces, 1, null,
                AgentInstance.HealthStatus.HEALTHY, "hash", Instant.now());
    }

    private GatewayCommand command() {
        PrincipalContext principal = new PrincipalContext("tenant-a", "user-a", Set.of("*"), Map.of(), "fp-a");
        return new GatewayCommand(GatewayCommand.Operation.SEND_MESSAGE, "tenant-a", principal,
                TargetHint.empty(), null, null, Map.of(), Map.of(), Map.of(), null, ProtocolDescriptor.httpJson(false),
                "1.0", Set.of());
    }

}
