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

package io.github.a2ap.gateway.core.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.AgentSkillDefinition;
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.api.model.ProtocolDescriptor;
import io.github.a2ap.gateway.api.model.ProtocolPolicy;
import io.github.a2ap.gateway.api.model.TargetHint;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultAuthorizationPolicyTest {

    private static final AgentDefinition AGENT = new AgentDefinition("tenant-a", "agent-a", "Agent A", true,
            List.of(new AgentSkillDefinition("echo", "Echo", "Echo text", List.of("text"),
                    List.of("text/plain"), List.of("text/plain"))), Map.of(), ProtocolPolicy.a2aV1Mvp(),
            List.of(new AgentInstance("instance-1", "https://agent.example.test/card",
                    List.of(new AgentInterface("jsonrpc", "https://agent.example.test/a2a", "JSONRPC", "1.0", null)),
                    1, null, AgentInstance.HealthStatus.HEALTHY, "hash", null)));

    @Test
    void requiresAgentAndSkillAuthoritiesForInvocation() {
        PrincipalContext principal = principal(Set.of("agent:invoke:agent-a", "skill:invoke:echo"));
        GatewayCommand command = command(GatewayCommand.Operation.SEND_MESSAGE, "echo", null, principal);

        assertTrue(new DefaultAuthorizationPolicy().authorize(principal, command, AGENT).block().allowed());
        PrincipalContext missingSkill = principal(Set.of("agent:invoke:agent-a"));
        GatewayCommand missingSkillCommand = command(GatewayCommand.Operation.SEND_MESSAGE, "echo", null,
                missingSkill);
        assertFalse(new DefaultAuthorizationPolicy().authorize(missingSkill, missingSkillCommand, AGENT)
                .block().allowed());
    }

    @Test
    void rejectsCrossTenantAndTaskOperationsWithoutTaskAuthority() {
        PrincipalContext principal = principal(Set.of("task:read"));
        GatewayCommand command = command(GatewayCommand.Operation.GET_TASK, null, "task-1", principal);
        assertTrue(new DefaultAuthorizationPolicy().authorize(principal, command, AGENT).block().allowed());

        PrincipalContext crossTenant = new PrincipalContext("tenant-b", principal.subject(), principal.authorities(),
                principal.claims(), principal.fingerprint());
        assertFalse(new DefaultAuthorizationPolicy().authorize(crossTenant, command, AGENT).block().allowed());
    }

    private PrincipalContext principal(Set<String> authorities) {
        return new PrincipalContext("tenant-a", "user-a", authorities, Map.of(), "principal-fingerprint");
    }

    private GatewayCommand command(GatewayCommand.Operation operation, String skillId, String taskId,
            PrincipalContext principal) {
        return new GatewayCommand(operation, "tenant-a", principal, new TargetHint("agent-a", skillId, Map.of()),
                taskId, null, Map.of(), Map.of(), Map.of(), null, ProtocolDescriptor.jsonRpc(), "1.0", Set.of());
    }

}
