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

import io.github.a2ap.gateway.api.spi.AgentRegistry;
import io.github.a2ap.gateway.api.spi.AgentLoadBalancer;
import io.github.a2ap.gateway.api.spi.AgentInterfaceSelector;
import io.github.a2ap.gateway.api.spi.AuthorizationPolicy;
import io.github.a2ap.gateway.api.spi.CredentialProvider;
import io.github.a2ap.gateway.api.spi.AgentTransport;
import io.github.a2ap.gateway.api.spi.IdempotencyStore;
import io.github.a2ap.gateway.api.spi.GatewayAuditSink;
import io.github.a2ap.gateway.api.spi.GatewayMetrics;
import io.github.a2ap.gateway.api.spi.ProtocolAdapter;
import io.github.a2ap.gateway.api.spi.RouteResolver;
import io.github.a2ap.gateway.api.spi.TaskRouteStore;
import io.github.a2ap.gateway.core.DefaultGatewayProtocolRegistry;
import io.github.a2ap.gateway.core.GatewayProtocolRegistry;
import io.github.a2ap.gateway.core.discovery.AgentCardFetcher;
import io.github.a2ap.gateway.core.discovery.AgentCardNormalizer;
import io.github.a2ap.gateway.core.discovery.AgentCardProbe;
import io.github.a2ap.gateway.core.discovery.AgentCardUrlPolicy;
import io.github.a2ap.gateway.core.discovery.InMemoryAgentRegistry;
import io.github.a2ap.gateway.core.forwarding.GatewayForwarder;
import io.github.a2ap.gateway.core.forwarding.TenantStreamLimiter;
import io.github.a2ap.gateway.core.protocol.JsonRpcProtocolAdapter;
import io.github.a2ap.gateway.core.protocol.HttpJsonProtocolAdapter;
import io.github.a2ap.gateway.core.routing.DefaultAgentInterfaceSelector;
import io.github.a2ap.gateway.core.security.DefaultAuthorizationPolicy;
import io.github.a2ap.gateway.core.security.EnvironmentCredentialProvider;
import io.github.a2ap.gateway.core.routing.DeterministicRouteResolver;
import io.github.a2ap.gateway.core.routing.WeightedLeastActiveLoadBalancer;
import io.github.a2ap.gateway.core.store.InMemoryIdempotencyStore;
import io.github.a2ap.gateway.core.store.InMemoryTaskRouteStore;
import io.github.a2ap.gateway.core.transport.ReactorNettyAgentTransport;
import java.time.Clock;
import java.util.Map;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;

/** Spring Boot entry point for the framework-neutral gateway contracts. */
@AutoConfiguration
@EnableConfigurationProperties(GatewayProperties.class)
@ConditionalOnProperty(prefix = "a2a.gateway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GatewayAutoConfiguration {

    /**
     * Creates the default MVP protocol registry when an application has not supplied one.
     *
     * @return default protocol registry
     */
    @Bean
    @ConditionalOnMissingBean(GatewayProtocolRegistry.class)
    public GatewayProtocolRegistry gatewayProtocolRegistry() {
        return new DefaultGatewayProtocolRegistry();
    }

    /** Creates the deterministic tenant-aware authorization policy for the MVP. */
    @Bean
    @ConditionalOnMissingBean(AuthorizationPolicy.class)
    public AuthorizationPolicy authorizationPolicy() {
        return new DefaultAuthorizationPolicy();
    }

    /**
     * Creates the environment-backed outbound credential provider for {@code env://} references.
     *
     * @return environment-backed credential provider
     */
    @Bean
    @ConditionalOnMissingBean(CredentialProvider.class)
    public CredentialProvider credentialProvider() {
        return new EnvironmentCredentialProvider();
    }

    /** Creates the deterministic tenant/Agent/Skill route resolver. */
    @Bean
    @ConditionalOnMissingBean(RouteResolver.class)
    public RouteResolver routeResolver(GatewayProperties properties, AgentRegistry registry,
            AuthorizationPolicy authorizationPolicy, ObjectProvider<TaskRouteStore> taskRouteStores) {
        properties.validate();
        return new DeterministicRouteResolver(registry, taskRouteStores.getIfAvailable(), authorizationPolicy,
                properties.getDefaultAgentByTenant());
    }

    /** Creates the weighted least-active instance selector with MVP bulkhead defaults. */
    @Bean
    @ConditionalOnMissingBean(AgentLoadBalancer.class)
    public AgentLoadBalancer agentLoadBalancer() {
        return new WeightedLeastActiveLoadBalancer();
    }

    /** Creates the bounded in-memory task affinity store for the single-node MVP. */
    @Bean
    @ConditionalOnMissingBean(TaskRouteStore.class)
    public TaskRouteStore taskRouteStore(GatewayProperties properties) {
        properties.validate();
        return new InMemoryTaskRouteStore(properties.getTaskRouteMaxEntries(), Clock.systemUTC());
    }

    /** Creates the bounded in-memory idempotency store for the single-node MVP. */
    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public IdempotencyStore idempotencyStore(GatewayProperties properties) {
        properties.validate();
        return new InMemoryIdempotencyStore(properties.getIdempotencyMaxEntries(), properties.getIdempotencyTtl(),
                Clock.systemUTC());
    }

    /** Creates the Micrometer metrics bridge when Actuator exposes a registry. */
    @Bean
    @ConditionalOnMissingBean(GatewayMetrics.class)
    public GatewayMetrics gatewayMetrics(ObjectProvider<MeterRegistry> registries) {
        MeterRegistry registry = registries.getIfAvailable();
        return registry == null ? GatewayMetrics.noop() : new MicrometerGatewayMetrics(registry);
    }

    /** Registers occupancy gauges for the bounded MVP Stores when Micrometer is present. */
    @Bean
    @ConditionalOnMissingBean(GatewayStoreMetrics.class)
    public GatewayStoreMetrics gatewayStoreMetrics(ObjectProvider<MeterRegistry> registries,
            TaskRouteStore taskRouteStore, ObjectProvider<IdempotencyStore> idempotencyStores) {
        return new GatewayStoreMetrics(registries.getIfAvailable(), taskRouteStore,
                idempotencyStores.getIfAvailable());
    }

    /** Creates the default body-free structured audit logger. */
    @Bean
    @ConditionalOnMissingBean(GatewayAuditSink.class)
    public GatewayAuditSink gatewayAuditSink() {
        return new Slf4jGatewayAuditSink();
    }

    /** Creates the default A2A 1.0 JSON-RPC adapter. */
    @Bean
    @ConditionalOnMissingBean(ProtocolAdapter.class)
    public ProtocolAdapter protocolAdapter() {
        return new JsonRpcProtocolAdapter();
    }

    /** Creates the inbound A2A 1.0 HTTP+JSON adapter used by the WebFlux data plane. */
    @Bean
    @ConditionalOnMissingBean(HttpJsonProtocolAdapter.class)
    public HttpJsonProtocolAdapter httpJsonProtocolAdapter() {
        return new HttpJsonProtocolAdapter();
    }

    /** Creates the deterministic JSON-RPC-first, HTTP+JSON-fallback interface selector. */
    @Bean
    @ConditionalOnMissingBean(AgentInterfaceSelector.class)
    public AgentInterfaceSelector agentInterfaceSelector() {
        return new DefaultAgentInterfaceSelector();
    }

    /** Creates the pooled, SSRF-guarded Reactor Netty Agent transport. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AgentTransport.class)
    public AgentTransport agentTransport(GatewayProperties properties, AgentCardUrlPolicy urlPolicy) {
        properties.validate();
        return new ReactorNettyAgentTransport(urlPolicy, properties.getConnectTimeout(),
                properties.getResponseTimeout(), properties.getMaxResponseBytes(), properties.getMaxEventBytes());
    }

    /** Creates the protocol-neutral forwarding orchestrator. */
    @Bean
    @ConditionalOnMissingBean(GatewayForwarder.class)
    public GatewayForwarder gatewayForwarder(AgentRegistry agentRegistry, RouteResolver routeResolver,
            AgentLoadBalancer loadBalancer, ProtocolAdapter protocolAdapter, AgentTransport agentTransport,
            HttpJsonProtocolAdapter httpJsonProtocolAdapter, CredentialProvider credentialProvider,
            TaskRouteStore taskRouteStore,
            ObjectProvider<IdempotencyStore> idempotencyStores, GatewayProperties properties,
            GatewayMetrics metrics, AgentInterfaceSelector interfaceSelector) {
        return new GatewayForwarder(agentRegistry, routeResolver, loadBalancer, protocolAdapter, agentTransport,
                credentialProvider, taskRouteStore, idempotencyStores.getIfAvailable(), properties.getTaskRouteTtl(),
                properties.getStreamIdleTimeout(), new TenantStreamLimiter(properties.getMaxConcurrentStreams()), metrics,
                Map.of(protocolAdapter.descriptor().protocolBinding(), protocolAdapter,
                        httpJsonProtocolAdapter.descriptor().protocolBinding(), httpJsonProtocolAdapter),
                interfaceSelector);
    }

    /** Exposes the authenticated HTTP+JSON data-plane endpoints when WebFlux is present. */
    @Bean
    @ConditionalOnMissingBean(GatewayHttpJsonController.class)
    public GatewayHttpJsonController gatewayHttpJsonController(GatewayForwarder forwarder,
            HttpJsonProtocolAdapter adapter, GatewayProperties properties, GatewayAuditSink auditSink,
            GatewayMetrics metrics) {
        return new GatewayHttpJsonController(forwarder, adapter, properties, auditSink, metrics);
    }

    /** Exposes the authenticated A2A 1.0 JSON-RPC data-plane endpoints. */
    @Bean
    @ConditionalOnMissingBean(GatewayJsonRpcController.class)
    public GatewayJsonRpcController gatewayJsonRpcController(GatewayForwarder forwarder,
            GatewayProperties properties, GatewayAuditSink auditSink, GatewayMetrics metrics) {
        return new GatewayJsonRpcController(forwarder, new JsonRpcProtocolAdapter(), properties, auditSink, metrics);
    }

    /** Exposes tenant-scoped Agent discovery and Card projections. */
    @Bean
    @ConditionalOnMissingBean(GatewayAgentCatalogController.class)
    public GatewayAgentCatalogController gatewayAgentCatalogController(AgentRegistry registry,
            ObjectProvider<com.fasterxml.jackson.databind.ObjectMapper> objectMappers) {
        return new GatewayAgentCatalogController(registry,
                objectMappers.getIfAvailable(com.fasterxml.jackson.databind.ObjectMapper::new));
    }

    /** Maps data-plane failures to the A2A HTTP+JSON error envelope. */
    @Bean
    @ConditionalOnMissingBean(GatewayHttpErrorHandler.class)
    public GatewayHttpErrorHandler gatewayHttpErrorHandler() {
        return new GatewayHttpErrorHandler();
    }

    /** Maps JSON-RPC data-plane failures to JSON-RPC error responses. */
    @Bean
    @ConditionalOnMissingBean(GatewayJsonRpcErrorHandler.class)
    public GatewayJsonRpcErrorHandler gatewayJsonRpcErrorHandler() {
        return new GatewayJsonRpcErrorHandler();
    }

    /** Creates the atomic MVP Agent registry when an application has not supplied one. */
    @Bean
    @ConditionalOnMissingBean(AgentRegistry.class)
    public InMemoryAgentRegistry agentRegistry() {
        return new InMemoryAgentRegistry();
    }

    /** Creates the configured URL and response-size policy. */
    @Bean
    public AgentCardUrlPolicy agentCardUrlPolicy(GatewayProperties properties) {
        properties.validate();
        AgentCardUrlPolicy policy = new AgentCardUrlPolicy(properties.isAllowHttp(), properties.isAllowPrivateNetwork(),
                properties.getAllowedCidrs(), properties.getMaxCardBytes());
        properties.validateCardUrls(policy);
        return policy;
    }

    /** Creates the default Reactor Netty Agent Card fetcher. */
    @Bean
    @ConditionalOnMissingBean(AgentCardFetcher.class)
    public AgentCardFetcher agentCardFetcher(GatewayProperties properties, AgentCardUrlPolicy urlPolicy) {
        return new io.github.a2ap.gateway.core.discovery.ReactorNettyAgentCardFetcher(urlPolicy,
                properties.getCardTimeout());
    }

    /** Creates the A2A 1.0 Card normalizer. */
    @Bean
    @ConditionalOnMissingBean(AgentCardNormalizer.class)
    public AgentCardNormalizer agentCardNormalizer(AgentCardUrlPolicy urlPolicy) {
        return new AgentCardNormalizer(urlPolicy);
    }

    /** Creates the Card probe used by the initial and periodic refresh scheduler. */
    @Bean
    @ConditionalOnMissingBean(AgentCardProbe.class)
    public AgentCardProbe agentCardProbe(AgentCardFetcher fetcher, AgentCardNormalizer normalizer,
            InMemoryAgentRegistry registry, GatewayProperties properties) {
        return new AgentCardProbe(fetcher, normalizer, registry, properties.getUnhealthyAfterFailures());
    }

    /** Starts Card refreshes for configured Agents. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AgentCardRefreshScheduler.class)
    public AgentCardRefreshScheduler agentCardRefreshScheduler(GatewayProperties properties, AgentCardProbe probe) {
        return new AgentCardRefreshScheduler(properties, probe);
    }

    /** Exposes Card and instance health through Spring Boot Actuator. */
    @Bean
    @ConditionalOnMissingBean(name = "gatewayAgentHealthIndicator")
    public HealthIndicator gatewayAgentHealthIndicator(InMemoryAgentRegistry registry) {
        return new GatewayAgentHealthIndicator(registry);
    }

    /** Exposes discovered Agent dependency readiness through Actuator. */
    @Bean
    @ConditionalOnMissingBean(name = "gatewayDependencyHealthIndicator")
    public HealthIndicator gatewayDependencyHealthIndicator(InMemoryAgentRegistry registry) {
        return new GatewayDependencyHealthIndicator(registry);
    }

    /** Emits body-free structured access records for all WebFlux exchanges. */
    @Bean(name = "gatewayAccessLogWebFilter")
    @ConditionalOnMissingBean(name = "gatewayAccessLogWebFilter")
    public GatewayAccessLogWebFilter gatewayAccessLogWebFilter(GatewayAuditSink auditSink) {
        return new GatewayAccessLogWebFilter(auditSink);
    }

}
