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

import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstanceRegistration;
import io.github.a2ap.gateway.api.model.AgentRegistration;
import io.github.a2ap.gateway.api.model.ProtocolPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentCardNormalizerTest {

    private static final String CARD = """
            {
              "name": "Gateway Test Agent",
              "description": "A minimal A2A 1.0 agent.",
              "supportedInterfaces": [
                {"url":"https://agent.example.test/a2a/jsonrpc","protocolBinding":"JSONRPC","protocolVersion":"1.0"},
                {"url":"https://agent.example.test/a2a/http","protocolBinding":"HTTP+JSON","protocolVersion":"1.0"}
              ],
              "version": "1.0.0",
              "capabilities": {"streaming": true, "extensions": [{"uri":"https://example.test/ext/v1",
                "required": true}]},
              "defaultInputModes": ["text/plain"],
              "defaultOutputModes": ["text/plain"],
              "skills": [{"id":"echo","name":"Echo","description":"Echo text","tags":["text"],
                "inputModes":["text/plain"],"outputModes":["text/plain"]}]
            }
            """;

    private static final AgentRegistration REGISTRATION = new AgentRegistration(
            "tenant-a", "agent-a", null, true, Map.of("region", "cn"), ProtocolPolicy.a2aV1Mvp(),
            List.of(new AgentInstanceRegistration("instance-1",
                    "https://agent.example.test/.well-known/agent-card.json", 2, "credential-ref")));

    @Test
    void normalizesAndHashesAValidCard() {
        Instant checkedAt = Instant.parse("2026-07-31T00:00:00Z");
        AgentDefinition definition = new AgentCardNormalizer().normalize(REGISTRATION,
                Map.of("instance-1", CARD), checkedAt);

        assertEquals("Gateway Test Agent", definition.displayName());
        assertEquals("echo", definition.skills().get(0).skillId());
        assertEquals(2, definition.instances().get(0).weight());
        assertEquals(2, definition.instances().get(0).interfaces().size());
        assertEquals(checkedAt, definition.instances().get(0).lastCheckedAt());
        assertEquals(64, definition.instances().get(0).lastCardHash().length());
        Map<?, ?> capabilities = (Map<?, ?>) definition.cardMetadata().get("capabilities");
        assertEquals("https://example.test/ext/v1",
                ((Map<?, ?>) ((List<?>) capabilities.get("extensions")).get(0)).get("uri"));
    }

    @Test
    void rejectsLegacyCardShapeBeforeRegistration() {
        String legacyCard = """
                {"name":"Legacy","description":"legacy","version":"0.2.1","skills":[]}
                """;
        assertThrows(IllegalArgumentException.class, () -> new AgentCardNormalizer().normalize(REGISTRATION,
                Map.of("instance-1", legacyCard), Instant.now()));
    }

}
