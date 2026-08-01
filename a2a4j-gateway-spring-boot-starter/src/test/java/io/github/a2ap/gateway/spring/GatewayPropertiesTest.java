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

package io.github.a2ap.gateway.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayPropertiesTest {

    @Test
    void convertsYamlLikePropertiesToValidatedRegistrations() {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.AgentProperties agent = new GatewayProperties.AgentProperties();
        agent.setTenantId("tenant-a");
        agent.setAgentId("agent-a");
        GatewayProperties.InstanceProperties instance = new GatewayProperties.InstanceProperties();
        instance.setInstanceId("instance-1");
        instance.setCardUrl("https://agent.example.test/card");
        agent.setInstances(List.of(instance));
        properties.setAgents(List.of(agent));

        assertEquals("tenant-a", properties.toRegistrations().get(0).tenantId());
        assertEquals("1.0", properties.toRegistrations().get(0).protocolPolicy().protocolVersions().iterator().next());
    }

    @Test
    void rejectsDuplicateLogicalAgentAndMissingInstances() {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.AgentProperties first = new GatewayProperties.AgentProperties();
        first.setTenantId("tenant-a");
        first.setAgentId("agent-a");
        GatewayProperties.AgentProperties duplicate = new GatewayProperties.AgentProperties();
        duplicate.setTenantId("tenant-a");
        duplicate.setAgentId("agent-a");
        properties.setAgents(List.of(first, duplicate));

        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void validatesTenantDefaultAgentMapWithoutChangingRegistrationScope() {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.AgentProperties agent = new GatewayProperties.AgentProperties();
        agent.setTenantId("tenant-a");
        agent.setAgentId("agent-a");
        GatewayProperties.InstanceProperties instance = new GatewayProperties.InstanceProperties();
        instance.setInstanceId("instance-1");
        instance.setCardUrl("https://agent.example.test/card");
        agent.setInstances(List.of(instance));
        properties.setAgents(List.of(agent));
        properties.setDefaultAgentByTenant(Map.of("tenant-a", "agent-a"));
        properties.validate();

        properties.setDefaultAgentByTenant(Map.of("", "agent-a"));
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void exposesBoundedStreamingDefaultsAndValidatesTheirRelationship() {
        GatewayProperties properties = new GatewayProperties();
        assertEquals(1024 * 1024, properties.getMaxRequestBytes());
        assertEquals(1024 * 1024, properties.getMaxEventBytes());
        assertEquals(Duration.ofSeconds(30), properties.getStreamIdleTimeout());
        assertEquals(200, properties.getMaxConcurrentStreams());

        properties.setMaxResponseBytes(1024);
        properties.setMaxEventBytes(2048);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

}
