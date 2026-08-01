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

import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AuthorizationDecision;
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.api.spi.AuthorizationPolicy;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Deterministic tenant, Agent, Skill and Task authorization policy for the MVP. */
public final class DefaultAuthorizationPolicy implements AuthorizationPolicy {

    @Override
    public Mono<AuthorizationDecision> authorize(PrincipalContext principal, GatewayCommand command,
            AgentDefinition agent) {
        if (principal == null || command == null || agent == null) {
            return deny("missing authorization context");
        }
        if (!Objects.equals(principal.tenantId(), command.tenantId())
                || !Objects.equals(principal.tenantId(), agent.tenantId())
                || !Objects.equals(principal.fingerprint(), command.principal().fingerprint())) {
            return deny("tenant or principal mismatch");
        }
        if (!agent.enabled()) {
            return deny("agent is disabled");
        }
        GatewayCommand.Operation operation = command.operation();
        if (operation == GatewayCommand.Operation.GET_EXTENDED_AGENT_CARD) {
            return require(principal, "agent:discover");
        }
        if (operation == GatewayCommand.Operation.LIST_TASKS) {
            return require(principal, "task:read");
        }
        if (operation == GatewayCommand.Operation.GET_TASK || operation == GatewayCommand.Operation.SUBSCRIBE_TO_TASK) {
            return requireTaskId(command).flatMap(ignored -> require(principal, "task:read"));
        }
        if (operation == GatewayCommand.Operation.CANCEL_TASK) {
            return requireTaskId(command).flatMap(ignored -> require(principal, "task:cancel"));
        }
        AuthorizationDecision agentDecision = decisionFor(principal, "agent:invoke:" + agent.agentId(),
                "agent:invoke");
        if (!agentDecision.allowed()) {
            return Mono.just(agentDecision);
        }
        String skillId = command.targetHint().skillId();
        if (skillId == null || skillId.isBlank()) {
            return Mono.just(agentDecision);
        }
        boolean skillExists = agent.skills().stream().anyMatch(skill -> skill.skillId().equals(skillId));
        if (!skillExists) {
            return deny("skill is not registered on agent");
        }
        return require(principal, "skill:invoke:" + skillId)
                .flatMap(decision -> decision.allowed() ? Mono.just(decision) : require(principal, "skill:invoke"));
    }

    private Mono<AuthorizationDecision> requireTaskId(GatewayCommand command) {
        return command.gatewayTaskId() == null || command.gatewayTaskId().isBlank()
                ? deny("gateway task id is required") : Mono.just(AuthorizationDecision.allow());
    }

    private Mono<AuthorizationDecision> require(PrincipalContext principal, String authority) {
        return Mono.just(decisionFor(principal, authority));
    }

    private AuthorizationDecision decisionFor(PrincipalContext principal, String... authorities) {
        for (String authority : authorities) {
            if (principal.authorities().contains(authority) || principal.authorities().contains("SCOPE_" + authority)
                    || principal.authorities().contains("ROLE_" + authority)
                    || principal.authorities().contains("*")) {
                return AuthorizationDecision.allow();
            }
        }
        return AuthorizationDecision.deny("required authority is missing");
    }

    private Mono<AuthorizationDecision> deny(String reason) {
        return Mono.just(AuthorizationDecision.deny(reason));
    }

}
