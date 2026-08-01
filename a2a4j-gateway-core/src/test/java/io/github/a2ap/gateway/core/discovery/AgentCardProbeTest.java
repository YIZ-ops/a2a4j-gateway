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

package io.github.a2ap.gateway.core.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInstanceRegistration;
import io.github.a2ap.gateway.api.model.AgentRegistration;
import io.github.a2ap.gateway.api.model.ProtocolPolicy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AgentCardProbeTest {

    private static final String CARD = """
            {"name":"Probe Agent","description":"Probe test","version":"1.0",
             "supportedInterfaces":[{"url":"https://agent.example.test/a2a","protocolBinding":"JSONRPC","protocolVersion":"1.0"}],
             "capabilities":{"streaming":false},"defaultInputModes":["text/plain"],
             "defaultOutputModes":["text/plain"],"skills":[{"id":"echo","name":"Echo",
             "description":"Echo","tags":["text"]}]}
            """;

    @Test
    void refreshesAndPublishesACompleteSnapshot() {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        AgentCardProbe probe = new AgentCardProbe(url -> Mono.just(CARD), new AgentCardNormalizer(), registry, 2);

        probe.refresh(registration()).block();

        assertEquals("agent-a", registry.snapshot().get(0).agentId());
        assertEquals(1, registry.snapshot().get(0).instances().size());
    }

    @Test
    void marksExistingSnapshotUnhealthyWhenCardValidationFails() {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        AtomicReference<String> body = new AtomicReference<>(CARD);
        AgentCardProbe probe = new AgentCardProbe(url -> Mono.just(body.get()), new AgentCardNormalizer(), registry, 1);
        AgentRegistration registration = registration();
        probe.refresh(registration).block();

        body.set("{\"legacy\":true}");
        assertThrows(IllegalArgumentException.class, () -> probe.refresh(registration).block());
        assertEquals(AgentInstance.HealthStatus.UNHEALTHY,
                registry.snapshot().get(0).instances().get(0).healthStatus());
    }

    private AgentRegistration registration() {
        return new AgentRegistration("tenant-a", "agent-a", null, true, Map.of(), ProtocolPolicy.a2aV1Mvp(),
                List.of(new AgentInstanceRegistration("instance-1",
                        "https://agent.example.test/.well-known/agent-card.json", 1, null)));
    }

}
