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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.AgentSkillDefinition;
import io.github.a2ap.gateway.api.model.ProtocolPolicy;
import io.github.a2ap.gateway.api.model.TargetHint;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryAgentRegistryTest {

    @Test
    void keepsAtomicSnapshotAndTransitionsHealthAfterConsecutiveFailures() {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(agent());

        assertEquals(1, registry.list("tenant-a", new TargetHint(null, "echo", Map.of("region", "cn")))
                .collectList().block().size());
        registry.recordProbeFailure("tenant-a", "agent-a", "instance-1", 2);
        assertEquals(AgentInstance.HealthStatus.DEGRADED,
                registry.snapshot().get(0).instances().get(0).healthStatus());
        registry.recordProbeFailure("tenant-a", "agent-a", "instance-1", 2);
        assertEquals(AgentInstance.HealthStatus.UNHEALTHY,
                registry.snapshot().get(0).instances().get(0).healthStatus());
        registry.recordProbeSuccess("tenant-a", "agent-a", "instance-1");
        assertTrue(registry.snapshot().get(0).instances().get(0).eligibleForNewWork());
    }

    private AgentDefinition agent() {
        return new AgentDefinition("tenant-a", "agent-a", "Agent A", true,
                List.of(new AgentSkillDefinition("echo", "Echo", "Echo text", List.of("text"),
                        List.of("text/plain"), List.of("text/plain"))), Map.of("region", "cn"),
                ProtocolPolicy.a2aV1Mvp(), List.of(new AgentInstance("instance-1",
                        "https://agent.example.test/card", List.of(new AgentInterface("jsonrpc",
                                "https://agent.example.test/a2a", "JSONRPC", "1.0", null)), 1, null,
                        AgentInstance.HealthStatus.HEALTHY, "hash", null)));
    }

}
