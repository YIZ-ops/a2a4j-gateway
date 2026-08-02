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

import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.TargetHint;
import io.github.a2ap.gateway.api.spi.AgentRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Atomic in-memory Agent snapshot used by the MVP and replaceable by a control-plane store. */
public final class InMemoryAgentRegistry implements AgentRegistry {

    private final AtomicReference<Map<String, AgentDefinition>> snapshot = new AtomicReference<>(Map.of());

    private final ConcurrentMap<String, Integer> probeFailures = new ConcurrentHashMap<>();

    /** Replaces one logical Agent atomically. */
    public void replace(AgentDefinition agent) {
        snapshot.updateAndGet(current -> {
            Map<String, AgentDefinition> next = new LinkedHashMap<>(current);
            next.put(key(agent.tenantId(), agent.agentId()), agent);
            return Map.copyOf(next);
        });
        agent.instances().forEach(instance -> probeFailures.remove(instanceKey(
                agent.tenantId(), agent.agentId(), instance.instanceId())));
    }

    /** Replaces the complete logical Agent snapshot atomically. */
    public void replaceAll(Collection<AgentDefinition> agents) {
        Map<String, AgentDefinition> next = new LinkedHashMap<>();
        for (AgentDefinition agent : agents) {
            next.put(key(agent.tenantId(), agent.agentId()), agent);
        }
        snapshot.set(Map.copyOf(next));
    }

    /** Records a failed card probe without removing an existing Agent snapshot. */
    public void recordProbeFailure(String tenantId, String agentId, String instanceId, int unhealthyAfterFailures) {
        if (unhealthyAfterFailures < 1) {
            throw new IllegalArgumentException("unhealthyAfterFailures must be positive");
        }
        String failureKey = instanceKey(tenantId, agentId, instanceId);
        int failures = probeFailures.merge(failureKey, 1, Integer::sum);
        AgentInstance.HealthStatus status = failures >= unhealthyAfterFailures
                ? AgentInstance.HealthStatus.UNHEALTHY : AgentInstance.HealthStatus.DEGRADED;
        updateAgent(key(tenantId, agentId), agent -> updateInstance(agent, instanceId, status));
    }

    /** Records a successful probe and clears the consecutive failure counter. */
    public void recordProbeSuccess(String tenantId, String agentId, String instanceId) {
        probeFailures.remove(instanceKey(tenantId, agentId, instanceId));
        updateAgent(key(tenantId, agentId), agent -> updateInstance(agent, instanceId,
                AgentInstance.HealthStatus.HEALTHY));
    }

    /** Returns a stable point-in-time snapshot for diagnostics and aggregation. */
    public List<AgentDefinition> snapshot() {
        return List.copyOf(snapshot.get().values());
    }

    /** Returns tenant-safe health details keyed by logical Agent and instance. */
    public Map<String, AgentInstance.HealthStatus> healthSummary() {
        Map<String, AgentInstance.HealthStatus> result = new LinkedHashMap<>();
        for (AgentDefinition agent : snapshot.get().values()) {
            for (AgentInstance instance : agent.instances()) {
                result.put(instanceKey(agent.tenantId(), agent.agentId(), instance.instanceId()),
                        instance.healthStatus());
            }
        }
        return Map.copyOf(result);
    }

    @Override
    public Flux<AgentDefinition> list(String tenantId, TargetHint targetHint) {
        TargetHint hint = targetHint == null ? TargetHint.empty() : targetHint;
        return Flux.fromIterable(snapshot.get().values())
                .filter(AgentDefinition::enabled)
                .filter(agent -> agent.tenantId().equals(tenantId))
                .filter(agent -> matches(agent, hint));
    }

    @Override
    public Flux<AgentDefinition> listAll() {
        return Flux.fromIterable(snapshot.get().values()).filter(AgentDefinition::enabled);
    }

    @Override
    public Mono<AgentDefinition> get(String tenantId, String agentId) {
        return Mono.justOrEmpty(snapshot.get().get(key(tenantId, agentId)))
                .filter(AgentDefinition::enabled);
    }

    @Override
    public Flux<AgentDefinition> findBySkill(String tenantId, String skillId) {
        return Flux.fromIterable(snapshot.get().values())
                .filter(AgentDefinition::enabled)
                .filter(agent -> agent.tenantId().equals(tenantId))
                .filter(agent -> agent.skills().stream().anyMatch(skill -> skill.skillId().equals(skillId)));
    }

    private boolean matches(AgentDefinition agent, TargetHint hint) {
        if (hint.agentId() != null && !hint.agentId().equals(agent.agentId())) {
            return false;
        }
        if (hint.skillId() != null && agent.skills().stream()
                .noneMatch(skill -> skill.skillId().equals(hint.skillId()))) {
            return false;
        }
        return hint.labels().entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(agent.routingLabels().get(entry.getKey())));
    }

    private void updateAgent(String agentKey, UnaryOperator<AgentDefinition> updater) {
        snapshot.updateAndGet(current -> {
            AgentDefinition currentAgent = current.get(agentKey);
            if (currentAgent == null) {
                return current;
            }
            Map<String, AgentDefinition> next = new LinkedHashMap<>(current);
            next.put(agentKey, updater.apply(currentAgent));
            return Map.copyOf(next);
        });
    }

    private AgentDefinition updateInstance(AgentDefinition agent, String instanceId,
            AgentInstance.HealthStatus status) {
        List<AgentInstance> instances = new ArrayList<>();
        for (AgentInstance instance : agent.instances()) {
            if (instance.instanceId().equals(instanceId)) {
                instances.add(new AgentInstance(instance.instanceId(), instance.cardUrl(), instance.interfaces(),
                        instance.weight(), instance.credentialRef(), status, instance.lastCardHash(),
                        instance.lastCheckedAt() == null ? Instant.now() : instance.lastCheckedAt()));
            }
            else {
                instances.add(instance);
            }
        }
        return new AgentDefinition(agent.tenantId(), agent.agentId(), agent.displayName(), agent.enabled(),
                agent.skills(), agent.routingLabels(), agent.protocolPolicy(), instances, agent.cardMetadata());
    }

    private static String key(String tenantId, String agentId) {
        return tenantId + "\u0000" + agentId;
    }

    private static String instanceKey(String tenantId, String agentId, String instanceId) {
        return key(tenantId, agentId) + "\u0000" + instanceId;
    }

}
