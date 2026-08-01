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
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.AgentSkillDefinition;
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.api.model.ProtocolDescriptor;
import io.github.a2ap.gateway.api.model.ProtocolPolicy;
import io.github.a2ap.gateway.api.model.RoutingContext;
import io.github.a2ap.gateway.api.model.TargetHint;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WeightedLeastActiveLoadBalancerTest {

    @Test
    void selectsHealthyInstancesByLeastActiveScoreAndRoundRobinTies() {
        AgentDefinition agent = agent(1, AgentInstance.HealthStatus.HEALTHY,
                AgentInstance.HealthStatus.HEALTHY);
        WeightedLeastActiveLoadBalancer balancer = new WeightedLeastActiveLoadBalancer(10, 3,
                Duration.ofSeconds(1));

        AgentInstance first = balancer.choose(agent, command(), context()).block();
        assertEquals("instance-1", first.instanceId());
        AgentInstance second = balancer.choose(agent, command(), context()).block();
        assertEquals("instance-2", second.instanceId());
        balancer.release(agent, first);
        balancer.release(agent, second);
        assertEquals(0, balancer.activeRequests(agent, first));

        AgentInstance next = balancer.choose(agent, command(), context()).block();
        assertEquals("instance-2", next.instanceId());
        balancer.release(agent, next);
    }

    @Test
    void excludesDegradedInstancesAndEnforcesBulkheadAndCircuit() {
        AgentDefinition agent = agent(1, AgentInstance.HealthStatus.DEGRADED,
                AgentInstance.HealthStatus.HEALTHY);
        WeightedLeastActiveLoadBalancer balancer = new WeightedLeastActiveLoadBalancer(1, 2,
                Duration.ofMillis(20));
        AgentInstance healthy = agent.instances().get(1);

        AgentInstance selected = balancer.choose(agent, command(), context()).block();
        assertEquals(healthy.instanceId(), selected.instanceId());
        assertThrows(RouteResolutionException.class, () -> balancer.choose(agent, command(), context()).block());
        balancer.release(agent, selected);

        balancer.recordFailure(agent, healthy);
        balancer.recordFailure(agent, healthy);
        assertEquals(WeightedLeastActiveLoadBalancer.CircuitState.OPEN,
                balancer.circuitState(agent, healthy));
        assertThrows(RouteResolutionException.class, () -> balancer.choose(agent, command(), context()).block());
    }

    @Test
    void closesCircuitAfterSuccessfulHalfOpenProbe() throws InterruptedException {
        AgentDefinition agent = agent(1, AgentInstance.HealthStatus.HEALTHY);
        WeightedLeastActiveLoadBalancer balancer = new WeightedLeastActiveLoadBalancer(2, 1,
                Duration.ofMillis(10));
        AgentInstance instance = agent.instances().get(0);
        balancer.recordFailure(agent, instance);
        assertEquals(WeightedLeastActiveLoadBalancer.CircuitState.OPEN,
                balancer.circuitState(agent, instance));
        Thread.sleep(20);
        AgentInstance probe = balancer.choose(agent, command(), context()).block();
        assertEquals(instance.instanceId(), probe.instanceId());
        balancer.recordSuccess(agent, probe);
        balancer.release(agent, probe);
        assertEquals(WeightedLeastActiveLoadBalancer.CircuitState.CLOSED,
                balancer.circuitState(agent, instance));
    }

    private AgentDefinition agent(int weight, AgentInstance.HealthStatus firstStatus,
            Object... additional) {
        List<AgentInstance> instances = new java.util.ArrayList<>();
        instances.add(instance("instance-1", weight, firstStatus));
        for (int index = 0; index < additional.length; index++) {
            instances.add(instance("instance-" + (index + 2), weight,
                    (AgentInstance.HealthStatus) additional[index]));
        }
        return new AgentDefinition("tenant-a", "agent-a", "Agent A", true,
                List.of(new AgentSkillDefinition("echo", "Echo", "Echo", List.of(), List.of("text/plain"),
                        List.of("text/plain"))), Map.of(), ProtocolPolicy.a2aV1Mvp(), instances);
    }

    private AgentDefinition agent(int weight, AgentInstance.HealthStatus status) {
        return new AgentDefinition("tenant-a", "agent-a", "Agent A", true,
                List.of(new AgentSkillDefinition("echo", "Echo", "Echo", List.of(), List.of("text/plain"),
                        List.of("text/plain"))), Map.of(), ProtocolPolicy.a2aV1Mvp(),
                List.of(instance("instance-1", weight, status)));
    }

    private AgentInstance instance(String id, int weight, AgentInstance.HealthStatus status) {
        return new AgentInstance(id, "https://agent.example.test/" + id,
                List.of(new AgentInterface("jsonrpc", "https://agent.example.test/a2a", "JSONRPC", "1.0", null)),
                weight, null, status, "hash", Instant.now());
    }

    private GatewayCommand command() {
        PrincipalContext principal = new PrincipalContext("tenant-a", "user-a", Set.of(), Map.of(), "fp");
        return new GatewayCommand(GatewayCommand.Operation.SEND_MESSAGE, "tenant-a", principal, TargetHint.empty(),
                null, null, Map.of(), Map.of(), Map.of(), null, ProtocolDescriptor.jsonRpc(), "1.0", Set.of());
    }

    private RoutingContext context() {
        return new RoutingContext("request-1", "trace-1", Instant.now().plusSeconds(30), Map.of());
    }

}
