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

import io.github.a2ap.gateway.api.model.AgentInstanceRegistration;
import io.github.a2ap.gateway.api.model.AgentRegistration;
import io.github.a2ap.gateway.api.model.ProtocolPolicy;
import io.github.a2ap.gateway.core.discovery.AgentCardUrlPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** YAML-bound configuration for controlled Agent Card discovery. */
@ConfigurationProperties(prefix = "a2a.gateway")
public class GatewayProperties {

    private boolean enabled = true;

    private boolean allowHttp;

    private boolean allowPrivateNetwork;

    private Set<String> allowedCidrs = new HashSet<>();

    private int maxCardBytes = 1024 * 1024;

    private Duration cardTimeout = Duration.ofSeconds(5);

    private Duration connectTimeout = Duration.ofSeconds(2);

    private Duration responseTimeout = Duration.ofSeconds(60);

    private int maxResponseBytes = 4 * 1024 * 1024;

    private int maxRequestBytes = 1024 * 1024;

    private int maxEventBytes = 1024 * 1024;

    private Duration streamIdleTimeout = Duration.ofSeconds(30);

    private int maxConcurrentStreams = 200;

    private Duration refreshInterval = Duration.ofMinutes(5);

    private int unhealthyAfterFailures = 3;

    private Duration taskRouteTtl = Duration.ofHours(24);

    private int taskRouteMaxEntries = 10000;

    private Duration idempotencyTtl = Duration.ofHours(24);

    private int idempotencyMaxEntries = 10000;

    private Map<String, String> defaultAgentByTenant = new HashMap<>();

    private List<AgentProperties> agents = new ArrayList<>();

    /** Returns whether gateway auto-configuration is enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Sets whether gateway auto-configuration is enabled. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Returns whether plain HTTP card URLs are allowed. */
    public boolean isAllowHttp() {
        return allowHttp;
    }

    /** Sets whether plain HTTP card URLs are allowed. */
    public void setAllowHttp(boolean allowHttp) {
        this.allowHttp = allowHttp;
    }

    /** Returns whether private network addresses are allowed. */
    public boolean isAllowPrivateNetwork() {
        return allowPrivateNetwork;
    }

    /** Sets whether private network addresses are allowed. */
    public void setAllowPrivateNetwork(boolean allowPrivateNetwork) {
        this.allowPrivateNetwork = allowPrivateNetwork;
    }

    /** Returns explicit private-network CIDR exceptions. */
    public Set<String> getAllowedCidrs() {
        return allowedCidrs;
    }

    /** Sets explicit private-network CIDR exceptions. */
    public void setAllowedCidrs(Set<String> allowedCidrs) {
        this.allowedCidrs = allowedCidrs == null ? new HashSet<>() : new HashSet<>(allowedCidrs);
    }

    /** Returns the maximum Agent Card response size. */
    public int getMaxCardBytes() {
        return maxCardBytes;
    }

    /** Sets the maximum Agent Card response size. */
    public void setMaxCardBytes(int maxCardBytes) {
        this.maxCardBytes = maxCardBytes;
    }

    /** Returns the card request timeout. */
    public Duration getCardTimeout() {
        return cardTimeout;
    }

    /** Sets the card request timeout. */
    public void setCardTimeout(Duration cardTimeout) {
        this.cardTimeout = cardTimeout;
    }

    /** Returns the upstream connection establishment timeout. */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /** Sets the upstream connection establishment timeout. */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /** Returns the upstream response timeout. */
    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    /** Sets the upstream response timeout. */
    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    /** Returns the maximum aggregated upstream response size. */
    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    /** Sets the maximum aggregated upstream response size. */
    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    /** Returns the maximum HTTP+JSON request body size. */
    public int getMaxRequestBytes() {
        return maxRequestBytes;
    }

    /** Sets the maximum HTTP+JSON request body size. */
    public void setMaxRequestBytes(int maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }

    /** Returns the maximum size of one upstream SSE event. */
    public int getMaxEventBytes() {
        return maxEventBytes;
    }

    /** Sets the maximum size of one upstream SSE event. */
    public void setMaxEventBytes(int maxEventBytes) {
        this.maxEventBytes = maxEventBytes;
    }

    /** Returns the maximum idle interval between stream events. */
    public Duration getStreamIdleTimeout() {
        return streamIdleTimeout;
    }

    /** Sets the maximum idle interval between stream events. */
    public void setStreamIdleTimeout(Duration streamIdleTimeout) {
        this.streamIdleTimeout = streamIdleTimeout;
    }

    /** Returns the per-tenant concurrent stream limit. */
    public int getMaxConcurrentStreams() {
        return maxConcurrentStreams;
    }

    /** Sets the per-tenant concurrent stream limit. */
    public void setMaxConcurrentStreams(int maxConcurrentStreams) {
        this.maxConcurrentStreams = maxConcurrentStreams;
    }

    /** Returns the periodic refresh interval. */
    public Duration getRefreshInterval() {
        return refreshInterval;
    }

    /** Sets the periodic refresh interval. */
    public void setRefreshInterval(Duration refreshInterval) {
        this.refreshInterval = refreshInterval;
    }

    /** Returns the consecutive failure threshold for unhealthy status. */
    public int getUnhealthyAfterFailures() {
        return unhealthyAfterFailures;
    }

    /** Sets the consecutive failure threshold for unhealthy status. */
    public void setUnhealthyAfterFailures(int unhealthyAfterFailures) {
        this.unhealthyAfterFailures = unhealthyAfterFailures;
    }

    /** Returns task route retention. */
    public Duration getTaskRouteTtl() {
        return taskRouteTtl;
    }

    /** Sets task route retention. */
    public void setTaskRouteTtl(Duration taskRouteTtl) {
        this.taskRouteTtl = taskRouteTtl;
    }

    /** Returns the task route store capacity. */
    public int getTaskRouteMaxEntries() {
        return taskRouteMaxEntries;
    }

    /** Sets the task route store capacity. */
    public void setTaskRouteMaxEntries(int taskRouteMaxEntries) {
        this.taskRouteMaxEntries = taskRouteMaxEntries;
    }

    /** Returns idempotency retention. */
    public Duration getIdempotencyTtl() {
        return idempotencyTtl;
    }

    /** Sets idempotency retention. */
    public void setIdempotencyTtl(Duration idempotencyTtl) {
        this.idempotencyTtl = idempotencyTtl;
    }

    /** Returns the idempotency store capacity. */
    public int getIdempotencyMaxEntries() {
        return idempotencyMaxEntries;
    }

    /** Sets the idempotency store capacity. */
    public void setIdempotencyMaxEntries(int idempotencyMaxEntries) {
        this.idempotencyMaxEntries = idempotencyMaxEntries;
    }

    /** Returns optional deterministic default Agent ids keyed by tenant. */
    public Map<String, String> getDefaultAgentByTenant() {
        return defaultAgentByTenant;
    }

    /** Sets deterministic default Agent ids keyed by tenant. */
    public void setDefaultAgentByTenant(Map<String, String> defaultAgentByTenant) {
        this.defaultAgentByTenant = defaultAgentByTenant == null
                ? new HashMap<>() : new HashMap<>(defaultAgentByTenant);
    }

    /** Returns configured logical Agents. */
    public List<AgentProperties> getAgents() {
        return agents;
    }

    /** Sets configured logical Agents. */
    public void setAgents(List<AgentProperties> agents) {
        this.agents = agents == null ? new ArrayList<>() : new ArrayList<>(agents);
    }

    /** Validates global settings and every configured Agent. */
    public void validate() {
        if (maxCardBytes < 1024) {
            throw new IllegalArgumentException("a2a.gateway.max-card-bytes must be at least 1024");
        }
        if (cardTimeout == null || cardTimeout.isZero() || cardTimeout.isNegative()) {
            throw new IllegalArgumentException("a2a.gateway.card-timeout must be positive");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("a2a.gateway.connect-timeout must be positive");
        }
        if (responseTimeout == null || responseTimeout.isZero() || responseTimeout.isNegative()) {
            throw new IllegalArgumentException("a2a.gateway.response-timeout must be positive");
        }
        if (maxResponseBytes < 1024) {
            throw new IllegalArgumentException("a2a.gateway.max-response-bytes must be at least 1024");
        }
        if (maxRequestBytes < 1024) {
            throw new IllegalArgumentException("a2a.gateway.max-request-bytes must be at least 1024");
        }
        if (maxEventBytes < 256 || maxEventBytes > maxResponseBytes) {
            throw new IllegalArgumentException(
                    "a2a.gateway.max-event-bytes must be at least 256 and no greater than max-response-bytes");
        }
        if (streamIdleTimeout == null || streamIdleTimeout.isZero() || streamIdleTimeout.isNegative()) {
            throw new IllegalArgumentException("a2a.gateway.stream-idle-timeout must be positive");
        }
        if (maxConcurrentStreams < 1) {
            throw new IllegalArgumentException("a2a.gateway.max-concurrent-streams must be positive");
        }
        if (refreshInterval == null || refreshInterval.isZero() || refreshInterval.isNegative()) {
            throw new IllegalArgumentException("a2a.gateway.refresh-interval must be positive");
        }
        if (unhealthyAfterFailures < 1) {
            throw new IllegalArgumentException("a2a.gateway.unhealthy-after-failures must be positive");
        }
        if (taskRouteTtl == null || taskRouteTtl.isZero() || taskRouteTtl.isNegative()) {
            throw new IllegalArgumentException("a2a.gateway.task-route-ttl must be positive");
        }
        if (taskRouteMaxEntries < 1) {
            throw new IllegalArgumentException("a2a.gateway.task-route-max-entries must be positive");
        }
        if (idempotencyTtl == null || idempotencyTtl.isZero() || idempotencyTtl.isNegative()) {
            throw new IllegalArgumentException("a2a.gateway.idempotency-ttl must be positive");
        }
        if (idempotencyMaxEntries < 1) {
            throw new IllegalArgumentException("a2a.gateway.idempotency-max-entries must be positive");
        }
        Set<String> identifiers = new HashSet<>();
        for (Map.Entry<String, String> entry : defaultAgentByTenant.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null
                    || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("default-agent-by-tenant entries must not be blank");
            }
        }
        for (AgentProperties agent : agents) {
            AgentRegistration registration = agent.toRegistration();
            if (!identifiers.add(registration.tenantId() + "\u0000" + registration.agentId())) {
                throw new IllegalArgumentException("duplicate tenant/agent registration");
            }
            if (registration.protocolPolicy().protocolVersions().isEmpty()
                    || registration.protocolPolicy().protocolBindings().isEmpty()) {
                throw new IllegalArgumentException("Agent protocol policy must not be empty");
            }
            Set<String> instanceIds = new HashSet<>();
            for (AgentInstanceRegistration instance : registration.instances()) {
                if (!instanceIds.add(instance.instanceId())) {
                    throw new IllegalArgumentException("duplicate Agent instance registration");
                }
            }
        }
        for (Map.Entry<String, String> entry : defaultAgentByTenant.entrySet()) {
            if (!identifiers.contains(entry.getKey() + "\u0000" + entry.getValue())) {
                throw new IllegalArgumentException("default Agent is not configured for tenant");
            }
        }
    }

    /** Validates configured Card URL syntax and scheme against the supplied network policy. */
    public void validateCardUrls(AgentCardUrlPolicy urlPolicy) {
        validate();
        for (AgentProperties agent : agents) {
            for (InstanceProperties instance : agent.instances) {
                urlPolicy.validateConfiguredUrl(instance.cardUrl);
            }
        }
    }

    /** Converts all YAML entries to immutable gateway registrations. */
    public List<AgentRegistration> toRegistrations() {
        validate();
        return agents.stream().map(AgentProperties::toRegistration).toList();
    }

    /** Mutable YAML entry for one logical Agent. */
    public static class AgentProperties {

        private String tenantId;

        private String agentId;

        private String displayName;

        private boolean enabled = true;

        private Map<String, String> routingLabels = new HashMap<>();

        private Set<String> protocolVersions = new HashSet<>(Set.of("1.0"));

        private Set<String> protocolBindings = new HashSet<>(Set.of("JSONRPC", "HTTP+JSON"));

        private List<InstanceProperties> instances = new ArrayList<>();

        /** Returns the tenant id. */
        public String getTenantId() {
            return tenantId;
        }

        /** Sets the tenant id. */
        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        /** Returns the logical Agent id. */
        public String getAgentId() {
            return agentId;
        }

        /** Sets the logical Agent id. */
        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        /** Returns the optional display name. */
        public String getDisplayName() {
            return displayName;
        }

        /** Sets the optional display name. */
        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        /** Returns whether the logical Agent accepts new work. */
        public boolean isEnabled() {
            return enabled;
        }

        /** Sets whether the logical Agent accepts new work. */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** Returns routing labels. */
        public Map<String, String> getRoutingLabels() {
            return routingLabels;
        }

        /** Sets routing labels. */
        public void setRoutingLabels(Map<String, String> routingLabels) {
            this.routingLabels = routingLabels == null ? new HashMap<>() : new HashMap<>(routingLabels);
        }

        /** Returns allowed protocol versions. */
        public Set<String> getProtocolVersions() {
            return protocolVersions;
        }

        /** Sets allowed protocol versions. */
        public void setProtocolVersions(Set<String> protocolVersions) {
            this.protocolVersions = protocolVersions == null ? new HashSet<>() : new HashSet<>(protocolVersions);
        }

        /** Returns allowed protocol bindings. */
        public Set<String> getProtocolBindings() {
            return protocolBindings;
        }

        /** Sets allowed protocol bindings. */
        public void setProtocolBindings(Set<String> protocolBindings) {
            this.protocolBindings = protocolBindings == null ? new HashSet<>() : new HashSet<>(protocolBindings);
        }

        /** Returns configured instances. */
        public List<InstanceProperties> getInstances() {
            return instances;
        }

        /** Sets configured instances. */
        public void setInstances(List<InstanceProperties> instances) {
            this.instances = instances == null ? new ArrayList<>() : new ArrayList<>(instances);
        }

        private AgentRegistration toRegistration() {
            if (instances == null || instances.isEmpty()) {
                throw new IllegalArgumentException("Agent " + agentId + " must define instances");
            }
            return new AgentRegistration(tenantId, agentId, displayName, enabled, routingLabels,
                    new ProtocolPolicy(protocolVersions, protocolBindings),
                    instances.stream().map(InstanceProperties::toRegistration).toList());
        }
    }

    /** Mutable YAML entry for one Agent Card endpoint. */
    public static class InstanceProperties {

        private String instanceId;

        private String cardUrl;

        private int weight = 1;

        private String credentialRef;

        /** Returns the instance id. */
        public String getInstanceId() {
            return instanceId;
        }

        /** Sets the instance id. */
        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }

        /** Returns the Agent Card URL. */
        public String getCardUrl() {
            return cardUrl;
        }

        /** Sets the Agent Card URL. */
        public void setCardUrl(String cardUrl) {
            this.cardUrl = cardUrl;
        }

        /** Returns the load-balancing weight. */
        public int getWeight() {
            return weight;
        }

        /** Sets the load-balancing weight. */
        public void setWeight(int weight) {
            this.weight = weight;
        }

        /** Returns the outbound credential reference. */
        public String getCredentialRef() {
            return credentialRef;
        }

        /** Sets the outbound credential reference. */
        public void setCredentialRef(String credentialRef) {
            this.credentialRef = credentialRef;
        }

        private AgentInstanceRegistration toRegistration() {
            return new AgentInstanceRegistration(instanceId, cardUrl, weight, credentialRef);
        }
    }

}
