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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.core.context.ReactiveSecurityContextHolder.withAuthentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.AgentSkillDefinition;
import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.api.model.ProtocolPolicy;
import io.github.a2ap.gateway.api.spi.CredentialProvider;
import io.github.a2ap.gateway.api.spi.GatewayMetrics;
import io.github.a2ap.gateway.api.spi.TaskRouteStore;
import io.github.a2ap.gateway.core.discovery.AgentCardUrlPolicy;
import io.github.a2ap.gateway.core.discovery.InMemoryAgentRegistry;
import io.github.a2ap.gateway.core.forwarding.GatewayForwarder;
import io.github.a2ap.gateway.core.forwarding.TenantStreamLimiter;
import io.github.a2ap.gateway.core.protocol.HttpJsonProtocolAdapter;
import io.github.a2ap.gateway.core.protocol.JsonRpcProtocolAdapter;
import io.github.a2ap.gateway.core.routing.DefaultAgentInterfaceSelector;
import io.github.a2ap.gateway.core.routing.DeterministicRouteResolver;
import io.github.a2ap.gateway.core.routing.WeightedLeastActiveLoadBalancer;
import io.github.a2ap.gateway.core.security.DefaultAuthorizationPolicy;
import io.github.a2ap.gateway.core.store.InMemoryIdempotencyStore;
import io.github.a2ap.gateway.core.store.InMemoryTaskRouteStore;
import io.github.a2ap.gateway.core.transport.ReactorNettyAgentTransport;
import io.github.a2ap.gateway.spring.autoconfigure.GatewayProperties;
import io.github.a2ap.gateway.spring.autoconfigure.GatewaySecurityProperties;
import io.github.a2ap.gateway.spring.controller.GatewayHttpJsonController;
import io.github.a2ap.gateway.spring.controller.GatewayJsonRpcController;
import io.github.a2ap.gateway.spring.error.GatewayHttpErrorHandler;
import io.github.a2ap.gateway.spring.error.GatewayJsonRpcErrorHandler;
import io.github.a2ap.gateway.spring.security.GatewayAuthenticationToken;
import io.github.a2ap.gateway.spring.security.GatewayJwtAuthenticationConverter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** End-to-end HTTP+JSON and SSE checks through the WebFlux controller and real upstream transport. */
class GatewayHttpJsonDataPlaneE2eTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DisposableServer upstream;

    private final AtomicBoolean upstreamCancelled = new AtomicBoolean();

    private ReactorNettyAgentTransport transport;

    private GatewayHttpJsonController controller;

    private WebTestClient authenticated;

    @BeforeEach
    void setUp() {
        upstream = HttpServer.create().host("127.0.0.1").port(0).handle(this::handleUpstream).bindNow();
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(agent(upstream.port()));
        InMemoryTaskRouteStore routes = new InMemoryTaskRouteStore(32);
        transport = new ReactorNettyAgentTransport(new AgentCardUrlPolicy(true, true, Set.of(), 1024 * 1024),
                Duration.ofSeconds(2), Duration.ofSeconds(5), 1024 * 1024, 1024 * 1024);
        GatewayForwarder forwarder = new GatewayForwarder(registry,
                new DeterministicRouteResolver(registry, routes, new DefaultAuthorizationPolicy(), Map.of()),
                new WeightedLeastActiveLoadBalancer(), new JsonRpcProtocolAdapter(), transport, noCredentials(), routes,
                new InMemoryIdempotencyStore(32), Duration.ofMinutes(5), Duration.ofSeconds(5),
                new TenantStreamLimiter(10), GatewayMetrics.noop());
        GatewayProperties properties = new GatewayProperties();
        properties.setResponseTimeout(Duration.ofSeconds(5));
        properties.setStreamIdleTimeout(Duration.ofSeconds(5));
        controller = new GatewayHttpJsonController(forwarder, new HttpJsonProtocolAdapter(), properties);
        authenticated = webClient(principal());
    }

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.close();
        }
        if (upstream != null) {
            upstream.disposeNow();
        }
    }

    @Test
    void propagatesHttpClientCancellationToStreamingUpstream() throws Exception {
        String request = "{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"cancel-stream\"}]}}";
        FluxExchangeResult<String> result = authenticated.post().uri("/message:stream")
                .header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM).returnResult(String.class);
        result.getResponseBody().take(1).then().block(Duration.ofSeconds(5));

        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!upstreamCancelled.get() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(upstreamCancelled.get(), "client cancellation must cancel the upstream SSE publisher");
    }

    @Test
    void forwardsHttpJsonAndPinnedTaskOperationsWithGatewayIds() throws Exception {
        String request = "{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"hello\"}]}}";
        String sendBody = authenticated.post().uri("/message:send").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.parseMediaType("application/a2a+json"))
                .expectBody(String.class).returnResult().getResponseBody();
        JsonNode sent = objectMapper.readTree(sendBody);
        String gatewayTaskId = sent.path("task").path("id").asText();
        assertFalse(gatewayTaskId.isBlank());
        assertNotEquals("up-1", gatewayTaskId);

        String getBody = authenticated.get().uri("/tasks/{taskId}", gatewayTaskId).header("A2A-Version", "1.0")
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertTrue(getBody.contains(gatewayTaskId));
        assertFalse(getBody.contains("up-1"));

        String cancelBody = authenticated.post().uri("/tasks/{taskId}:cancel", gatewayTaskId)
                .header("A2A-Version", "1.0").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertTrue(cancelBody.contains(gatewayTaskId));
        assertFalse(cancelBody.contains("up-1"));
    }

    @Test
    void bridgesHttpJsonStreamingEventsAndRewritesIdentifiers() throws Exception {
        String request = "{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"stream\"}]}}";
        FluxExchangeResult<String> result = authenticated.post().uri("/message:stream")
                .header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange()
                .expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class);
        String streamBody = result.getResponseBody().collect(Collectors.joining("\n"))
                .block(Duration.ofSeconds(5));
        assertTrue(streamBody.contains("\"task\""), streamBody);
        assertTrue(streamBody.contains("TASK_STATE_COMPLETED"));
        assertFalse(streamBody.contains("up-1"), streamBody);
        assertFalse(streamBody.contains("up-c"), streamBody);
    }

    @Test
    void rejectsUnauthenticatedAndUnsupportedVersionRequests() {
        WebTestClient unauthenticated = WebTestClient.bindToController(controllerForTest())
                .controllerAdvice(new GatewayHttpErrorHandler()).build();
        String unauthenticatedBody = unauthenticated.post().uri("/message:send").header("A2A-Version", "1.0")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isUnauthorized().expectBody(String.class)
                .returnResult().getResponseBody();
        assertTrue(unauthenticatedBody.contains("UNAUTHENTICATED"));

        String versionBody = authenticated.post().uri("/message:send").header("A2A-Version", "0.2.1")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isBadRequest().expectBody(String.class)
                .returnResult().getResponseBody();
        assertTrue(versionBody.contains("INVALID_ARGUMENT"));
    }

    @Test
    void authenticatesBearerJwtThroughTheRealWebFluxResourceServerChain() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAKey signingKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate()).keyID("e2e-key").build();
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(signingKey)));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("https://issuer.example.test")
                .subject("jwt-user").audience(List.of("a2a-gateway")).issuedAt(now)
                .expiresAt(now.plusSeconds(120)).claim("tenant_id", "tenant-a")
                .claim("scope", "agent:invoke").build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId("e2e-key").build(), claims)).getTokenValue();

        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setEnabled(true);
        properties.setMode("jwt");
        properties.getJwt().setIssuerUri("https://issuer.example.test");
        properties.getJwt().setAudiences(Set.of("a2a-gateway"));
        GatewayJwtAuthenticationConverter converter = new GatewayJwtAuthenticationConverter(properties.getJwt(),
                new io.github.a2ap.gateway.core.security.GatewayPrincipalFactory());
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic())
                .build();
        ServerHttpSecurity http = ServerHttpSecurity.http();
        SecurityWebFilterChain chain = http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges.anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtDecoder(decoder)
                        .jwtAuthenticationConverter(converter))).build();
        WebTestClient jwtClient = WebTestClient.bindToController(controller)
                .controllerAdvice(new GatewayHttpErrorHandler()).webFilter(new WebFilterChainProxy(chain)).build();

        String request = "{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"jwt\"}]}}";
        jwtClient.post().uri("/message:send").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange().expectStatus().isOk();

        jwtClient.post().uri("/message:send").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").header("Authorization", "Bearer invalid-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void mapsMalformedAndUnavailableUpstreamResponsesToStableGatewayErrors() {
        WebTestClient jsonRpc = jsonRpcWebClientForAgent(agent(upstream.port()));
        String malformed = "{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"bad-response\"}]}}";
        String malformedBody = authenticated.post().uri("/message:send").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(malformed).exchange().expectStatus().isEqualTo(502).expectBody(String.class)
                .returnResult().getResponseBody();
        assertTrue(malformedBody.contains("GATEWAY_UPSTREAM_PROTOCOL_ERROR"));

        String unavailable = "{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"upstream-error\"}]}}";
        String unavailableBody = authenticated.post().uri("/message:send").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(unavailable).exchange().expectStatus().isEqualTo(503).expectBody(String.class)
                .returnResult().getResponseBody();
        assertTrue(unavailableBody.contains("\"code\":503"), unavailableBody);
        assertTrue(unavailableBody.contains("\"status\":\"UNAVAILABLE\""), unavailableBody);

        String rpcUnavailableRequest = "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-upstream-error\","
                + "\"method\":\"SendMessage\",\"params\":{\"message\":{\"role\":\"ROLE_USER\","
                + "\"parts\":[{\"text\":\"upstream-error\"}]}}}";
        String rpcUnavailableBody = jsonRpc.post().uri("/gateway/v1/a2a").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(rpcUnavailableRequest).exchange().expectStatus().isEqualTo(503)
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(rpcUnavailableBody.contains("\"jsonrpc\":\"2.0\""), rpcUnavailableBody);
        assertTrue(rpcUnavailableBody.contains("\"code\":-32000"), rpcUnavailableBody);
    }

    @Test
    void deniesUnauthorizedInvocationAndProtectsTaskRoutesAcrossPrincipalsAndTenants() throws Exception {
        WebTestClient noInvoke = webClient(new PrincipalContext("tenant-a", "read-only",
                Set.of("task:read"), Map.of(), "read-only-fingerprint"));
        String request = "{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"denied\"}]}}";
        String deniedBody = noInvoke.post().uri("/message:send").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request).exchange().expectStatus().isForbidden().expectBody(String.class)
                .returnResult().getResponseBody();
        assertTrue(deniedBody.contains("GATEWAY_POLICY_DENIED"));

        String sendBody = authenticated.post().uri("/message:send").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        String gatewayTaskId = objectMapper.readTree(sendBody).path("task").path("id").asText();
        assertFalse(gatewayTaskId.isBlank());

        WebTestClient otherPrincipal = webClient(new PrincipalContext("tenant-a", "other-user",
                Set.of("task:read", "task:cancel"), Map.of(), "other-fingerprint"));
        String principalBody = otherPrincipal.get().uri("/tasks/{taskId}", gatewayTaskId)
                .header("A2A-Version", "1.0").exchange().expectStatus().isForbidden().expectBody(String.class)
                .returnResult().getResponseBody();
        assertTrue(principalBody.contains("GATEWAY_POLICY_DENIED"));
        assertFalse(principalBody.contains(gatewayTaskId));

        WebTestClient otherTenant = webClient(new PrincipalContext("tenant-b", "tenant-b-user",
                Set.of("task:read", "task:cancel"), Map.of(), "tenant-b-fingerprint"));
        String tenantBody = otherTenant.get().uri("/tasks/{taskId}", gatewayTaskId)
                .header("A2A-Version", "1.0").exchange().expectStatus().isNotFound().expectBody(String.class)
                .returnResult().getResponseBody();
        assertTrue(tenantBody.contains("TASK_NOT_FOUND"));
        assertFalse(tenantBody.contains(gatewayTaskId));
    }

    @Test
    void keepsAuthorizationOutcomesEquivalentAcrossHttpJsonAndJsonRpc() throws Exception {
        AgentDefinition definition = agent(upstream.port());
        String request = "{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"authorization\"}]}}";
        String rpcRequest = "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-auth\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"authorization\"}]}}}";

        PrincipalContext noInvokePrincipal = new PrincipalContext("tenant-a", "read-only",
                Set.of("task:read"), Map.of(), "read-only-fingerprint");
        String httpDenied = webClientForAgent(definition, 10, noInvokePrincipal).post().uri("/message:send")
                .header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange().expectStatus().isForbidden()
                .expectBody(String.class).returnResult().getResponseBody();
        String rpcDenied = jsonRpcWebClientForAgent(definition, noInvokePrincipal).post().uri("/gateway/v1/a2a")
                .header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(rpcRequest).exchange().expectStatus().isForbidden()
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(httpDenied.contains("GATEWAY_POLICY_DENIED"));
        assertTrue(rpcDenied.contains("GATEWAY_POLICY_DENIED"));

        PrincipalContext otherTenant = new PrincipalContext("tenant-b", "tenant-b-user",
                Set.of("*"), Map.of(), "tenant-b-fingerprint");
        String httpTenantMiss = webClientForAgent(definition, 10, otherTenant).post().uri("/message:send")
                .header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange().expectStatus().isNotFound()
                .expectBody(String.class).returnResult().getResponseBody();
        String rpcTenantMiss = jsonRpcWebClientForAgent(definition, otherTenant).post().uri("/gateway/v1/a2a")
                .header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(rpcRequest).exchange().expectStatus().isNotFound()
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(httpTenantMiss.contains("GATEWAY_ROUTE_NOT_FOUND"));
        assertTrue(rpcTenantMiss.contains("GATEWAY_ROUTE_NOT_FOUND"));
    }

    @Test
    void coversAgentSkillAndTaskAuthorizationMatrixAcrossBothBindings() throws Exception {
        AgentDefinition definition = agent(upstream.port());
        TaskRouteStore httpRoutes = new InMemoryTaskRouteStore(32);
        TaskRouteStore rpcRoutes = new InMemoryTaskRouteStore(32);
        WebTestClient httpJson = webClientForAgent(definition, 10, principal(), httpRoutes);
        WebTestClient jsonRpc = jsonRpcWebClientForAgent(definition, principal(), rpcRoutes);
        String httpMessage = "{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"matrix\"}]}}";
        String rpcMessage = "{\"jsonrpc\":\"2.0\",\"id\":\"matrix-send\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"matrix\"}]}}}";

        PrincipalContext invokeOnly = new PrincipalContext("tenant-a", "invoke-only", Set.of("task:read"),
                Map.of(), "invoke-only-fingerprint");
        String httpAgentDenied = webClientForAgent(definition, 10, invokeOnly, httpRoutes).post().uri("/message:send")
                .header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(httpMessage).exchange().expectStatus().isForbidden()
                .expectBody(String.class).returnResult().getResponseBody();
        String rpcAgentDenied = jsonRpcWebClientForAgent(definition, invokeOnly, rpcRoutes).post().uri("/gateway/v1/a2a")
                .header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(rpcMessage).exchange().expectStatus().isForbidden()
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(httpAgentDenied.contains("GATEWAY_POLICY_DENIED"));
        assertTrue(rpcAgentDenied.contains("GATEWAY_POLICY_DENIED"));

        PrincipalContext skillOnly = new PrincipalContext("tenant-a", "skill-only", Set.of("agent:invoke"),
                Map.of(), "skill-only-fingerprint");
        String httpSkillDenied = webClientForAgent(definition, 10, skillOnly, httpRoutes).post().uri("/message:send")
                .header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .header("X-A2A-Target-Skill", "echo").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(httpMessage).exchange().expectStatus().isForbidden().expectBody(String.class)
                .returnResult().getResponseBody();
        String rpcSkillDenied = jsonRpcWebClientForAgent(definition, skillOnly, rpcRoutes).post().uri("/gateway/v1/a2a")
                .header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .header("X-A2A-Target-Skill", "echo").contentType(MediaType.APPLICATION_JSON).bodyValue(rpcMessage)
                .exchange().expectStatus().isForbidden().expectBody(String.class).returnResult().getResponseBody();
        assertTrue(httpSkillDenied.contains("GATEWAY_POLICY_DENIED"));
        assertTrue(rpcSkillDenied.contains("GATEWAY_POLICY_DENIED"));

        String sendBody = httpJson.post().uri("/message:send").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(httpMessage).exchange().expectStatus().isOk().expectBody(String.class).returnResult()
                .getResponseBody();
        String taskId = objectMapper.readTree(sendBody).path("task").path("id").asText();
        assertFalse(taskId.isBlank());

        String rpcSendBody = jsonRpc.post().uri("/gateway/v1/a2a").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(rpcMessage).exchange().expectStatus().isOk().expectBody(String.class).returnResult()
                .getResponseBody();
        String rpcTaskId = objectMapper.readTree(rpcSendBody).path("result").path("task").path("id").asText();
        assertFalse(rpcTaskId.isBlank());

        PrincipalContext invokeWithoutTaskRead = new PrincipalContext("tenant-a", "task-operator",
                Set.of("agent:invoke", "task:cancel"), Map.of(), "e2e-fingerprint");
        String httpGetDenied = webClientForAgent(definition, 10, invokeWithoutTaskRead, httpRoutes).get()
                .uri("/tasks/{taskId}", taskId).header("A2A-Version", "1.0").exchange().expectStatus().isForbidden()
                .expectBody(String.class).returnResult().getResponseBody();
        String rpcGet = "{\"jsonrpc\":\"2.0\",\"id\":\"matrix-get\",\"method\":\"GetTask\","
                + "\"params\":{\"id\":\"" + rpcTaskId + "\"}}";
        String rpcGetDenied = jsonRpcWebClientForAgent(definition, invokeWithoutTaskRead, rpcRoutes).post()
                .uri("/gateway/v1/a2a").header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(rpcGet).exchange().expectStatus().isForbidden()
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(httpGetDenied.contains("GATEWAY_POLICY_DENIED"));
        assertTrue(rpcGetDenied.contains("GATEWAY_POLICY_DENIED"));

        String httpListDenied = webClientForAgent(definition, 10, invokeWithoutTaskRead, httpRoutes).get().uri("/tasks")
                .header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a").exchange()
                .expectStatus().isForbidden().expectBody(String.class).returnResult().getResponseBody();
        String rpcList = "{\"jsonrpc\":\"2.0\",\"id\":\"matrix-list\",\"method\":\"ListTasks\",\"params\":{}}";
        String rpcListDenied = jsonRpcWebClientForAgent(definition, invokeWithoutTaskRead, rpcRoutes).post()
                .uri("/gateway/v1/a2a").header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(rpcList).exchange().expectStatus().isForbidden()
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(httpListDenied.contains("GATEWAY_POLICY_DENIED"));
        assertTrue(rpcListDenied.contains("GATEWAY_POLICY_DENIED"));

        PrincipalContext taskReader = new PrincipalContext("tenant-a", "task-reader", Set.of("task:read"), Map.of(),
                "e2e-fingerprint");
        String httpCancelDenied = webClientForAgent(definition, 10, taskReader, httpRoutes).post()
                .uri("/tasks/{taskId}:cancel", taskId).header("A2A-Version", "1.0")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isForbidden()
                .expectBody(String.class).returnResult().getResponseBody();
        String rpcCancel = "{\"jsonrpc\":\"2.0\",\"id\":\"matrix-cancel\",\"method\":\"CancelTask\","
                + "\"params\":{\"id\":\"" + rpcTaskId + "\"}}";
        String rpcCancelDenied = jsonRpcWebClientForAgent(definition, taskReader, rpcRoutes).post().uri("/gateway/v1/a2a")
                .header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(rpcCancel).exchange().expectStatus().isForbidden()
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(httpCancelDenied.contains("GATEWAY_POLICY_DENIED"));
        assertTrue(rpcCancelDenied.contains("GATEWAY_POLICY_DENIED"));

        PrincipalContext cancelOnly = new PrincipalContext("tenant-a", "cancel-only", Set.of("task:cancel"), Map.of(),
                "e2e-fingerprint");
        String httpSubscribeDenied = webClientForAgent(definition, 10, cancelOnly, httpRoutes).post()
                .uri("/tasks/{taskId}:subscribe", taskId).header("A2A-Version", "1.0")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).bodyValue("{}")
                .exchange().expectStatus().isForbidden().expectBody(String.class).returnResult().getResponseBody();
        String rpcSubscribe = "{\"jsonrpc\":\"2.0\",\"id\":\"matrix-subscribe\",\"method\":\"SubscribeToTask\","
                + "\"params\":{\"id\":\"" + rpcTaskId + "\"}}";
        String rpcSubscribeDenied = jsonRpcWebClientForAgent(definition, cancelOnly, rpcRoutes).post()
                .uri("/gateway/v1/a2a").header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).bodyValue(rpcSubscribe)
                .exchange().expectStatus().isForbidden().expectBody(String.class).returnResult().getResponseBody();
        assertTrue(httpSubscribeDenied.contains("GATEWAY_POLICY_DENIED"));
        assertTrue(rpcSubscribeDenied.contains("GATEWAY_POLICY_DENIED"));
    }

    @Test
    void selectsHttpJsonOnlyUpstreamForSynchronousAndStreamingCalls() throws Exception {
        WebTestClient httpJsonUpstream = webClientForAgent(httpJsonAgent(upstream.port()));
        String request = "{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"http-json\"}]}}";
        String sendBody = httpJsonUpstream.post().uri("/message:send").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertTrue(sendBody.contains("\"task\""));
        assertFalse(sendBody.contains("up-1"));
        assertFalse(sendBody.contains("up-c"));

        FluxExchangeResult<String> result = httpJsonUpstream.post().uri("/message:stream")
                .header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM).returnResult(String.class);
        String streamBody = result.getResponseBody().collect(Collectors.joining("\n"))
                .block(Duration.ofSeconds(5));
        assertTrue(streamBody.contains("\"task\""), streamBody);
        assertTrue(streamBody.contains("TASK_STATE_COMPLETED"), streamBody);
        assertFalse(streamBody.contains("up-1"), streamBody);
        assertFalse(streamBody.contains("up-c"), streamBody);

        WebTestClient jsonRpc = jsonRpcWebClientForAgent(httpJsonAgent(upstream.port()));
        String rpcRequest = "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-http-only\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"http-json\"}]}}}";
        String rpcBody = jsonRpc.post().uri("/gateway/v1/a2a").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).bodyValue(rpcRequest).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        JsonNode rpcResponse = objectMapper.readTree(rpcBody);
        assertEquals("2.0", rpcResponse.path("jsonrpc").asText());
        assertEquals("rpc-http-only", rpcResponse.path("id").asText());
        assertTrue(rpcResponse.path("result").has("task"));

        String rpcStreamRequest = rpcRequest.replace("SendMessage", "SendStreamingMessage");
        FluxExchangeResult<String> rpcStream = jsonRpc.post().uri("/gateway/v1/a2a")
                .header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(rpcStreamRequest).exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM).returnResult(String.class);
        String rpcStreamBody = rpcStream.getResponseBody().collect(Collectors.joining("\n"))
                .block(Duration.ofSeconds(5));
        assertTrue(rpcStreamBody.contains("\"jsonrpc\":\"2.0\""), rpcStreamBody);
        assertTrue(rpcStreamBody.contains("\"result\""), rpcStreamBody);
        assertFalse(rpcStreamBody.contains("up-1"), rpcStreamBody);
        assertFalse(rpcStreamBody.contains("up-c"), rpcStreamBody);
    }

    @Test
    void returnsUpstreamStreamRejectionsBeforeStartingSse() throws Exception {
        WebTestClient httpJson = webClientForAgent(httpJsonAgent(upstream.port()));
        String httpRequest = "{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":"
                + "\"stream-upstream-error\"}]}}";
        String httpBody = httpJson.post().uri("/message:stream").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(httpRequest).exchange().expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith("application/a2a+json").expectBody(String.class)
                .returnResult().getResponseBody();
        JsonNode httpError = objectMapper.readTree(httpBody);
        assertEquals(404, httpError.at("/error/code").asInt());
        assertTrue(httpError.toString().contains("TASK_NOT_FOUND"), httpBody);
        assertFalse(httpBody.contains("data:"));

        WebTestClient jsonRpc = jsonRpcWebClientForAgent(httpJsonAgent(upstream.port()));
        String rpcRequest = "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-stream-error\","
                + "\"method\":\"SendStreamingMessage\",\"params\":{\"message\":{"
                + "\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"stream-upstream-error\"}]}}}";
        String rpcBody = jsonRpc.post().uri("/gateway/v1/a2a").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(rpcRequest).exchange().expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON).expectBody(String.class)
                .returnResult().getResponseBody();
        JsonNode rpcError = objectMapper.readTree(rpcBody);
        assertEquals("rpc-stream-error", rpcError.path("id").asText());
        assertEquals(-32001, rpcError.at("/error/code").asInt());
        assertFalse(rpcBody.contains("data:"));
    }

    @Test
    void distributesNewTasksAcrossTwoHealthyInstances() throws Exception {
        DisposableServer secondUpstream = HttpServer.create().host("127.0.0.1").port(0)
                .handle(this::handleSecondUpstream).bindNow();
        try {
            WebTestClient client = webClientForAgent(agentWithInstances(upstream.port(), secondUpstream.port()));
            Set<String> instances = new java.util.HashSet<>();
            String request = "{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"load\"}]}}";
            for (int i = 0; i < 8; i++) {
                String body = client.post().uri("/message:send").header("A2A-Version", "1.0")
                        .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(request).exchange().expectStatus().isOk().expectBody(String.class)
                        .returnResult().getResponseBody();
                instances.add(body.contains("served-by-b") ? "b" : "a");
            }
            assertEquals(Set.of("a", "b"), instances);
        }
        finally {
            secondUpstream.disposeNow();
        }
    }

    @Test
    void sustainsTwoHundredConcurrentSseStreamsWithinTenantQuota() {
        WebTestClient loadClient = webClientForAgent(agent(upstream.port()), 250);
        String request = "{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"load-sse\"}]}}";
        Flux.range(0, 200).flatMap(index -> loadClient.post().uri("/message:stream")
                .header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseBody().take(1).then(), 200)
                .blockLast(Duration.ofSeconds(30));
    }

    @Test
    void acceptsJsonRpcInboundAndBridgesTheSameUpstreamOperations() throws Exception {
        WebTestClient jsonRpc = jsonRpcWebClientForAgent(agent(upstream.port()));
        String request = "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-1\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"rpc\"}]}}}";
        String response = jsonRpc.post().uri("/gateway/v1/a2a").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).bodyValue(request).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(response.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(response.contains("\"task\""));
        assertFalse(response.contains("up-1"));
        assertFalse(response.contains("up-c"));

        String gatewayTaskId = objectMapper.readTree(response).path("result").path("task").path("id").asText();
        assertFalse(gatewayTaskId.isBlank());
        String getTask = "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-get\",\"method\":\"GetTask\"," 
                + "\"params\":{\"id\":\"" + gatewayTaskId + "\"}}";
        String getResponse = jsonRpc.post().uri("/gateway/v1/a2a").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).bodyValue(getTask).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(getResponse.contains("\"task\""));
        assertTrue(getResponse.contains(gatewayTaskId));
        assertFalse(getResponse.contains("up-1"));

        String cancelTask = "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-cancel\",\"method\":\"CancelTask\"," 
                + "\"params\":{\"id\":\"" + gatewayTaskId + "\"}}";
        String cancelResponse = jsonRpc.post().uri("/gateway/v1/a2a").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).bodyValue(cancelTask).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(cancelResponse.contains(gatewayTaskId));
        assertTrue(cancelResponse.contains("TASK_STATE_CANCELED"));
        assertFalse(cancelResponse.contains("up-1"));

        String streamingRequest = request.replace("SendMessage", "SendStreamingMessage");
        FluxExchangeResult<String> result = jsonRpc.post().uri("/gateway/v1/a2a").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(streamingRequest).exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM).returnResult(String.class);
        String streamBody = result.getResponseBody().collect(Collectors.joining("\n"))
                .block(Duration.ofSeconds(5));
        assertTrue(streamBody.contains("\"jsonrpc\":\"2.0\""), streamBody);
        assertTrue(streamBody.contains("TASK_STATE_COMPLETED"), streamBody);
        assertFalse(streamBody.contains("up-1"), streamBody);
        assertFalse(streamBody.contains("up-c"), streamBody);
    }

    @Test
    void keepsTaskListCancelAndSubscribeOperationsAvailableAcrossBothBindings() throws Exception {
        WebTestClient httpJson = webClientForAgent(agent(upstream.port()));
        WebTestClient jsonRpc = jsonRpcWebClientForAgent(agent(upstream.port()));

        String httpList = httpJson.get().uri(uriBuilder -> uriBuilder.path("/tasks").queryParam("limit", "5").build())
                .header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a").exchange().expectStatus().isOk()
                .expectBody(String.class)
                .returnResult().getResponseBody();
        assertTrue(httpList.contains("\"tasks\""));
        assertTrue(httpList.contains("\"pageSize\""));
        assertTrue(httpList.contains("\"totalSize\""));
        assertTrue(httpList.contains("\"nextPageToken\":\"\""));
        String rpcList = "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-list\",\"method\":\"ListTasks\","
                + "\"params\":{\"limit\":5}}";
        String rpcListBody = jsonRpc.post().uri("/gateway/v1/a2a").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).bodyValue(rpcList).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(rpcListBody.contains("\"tasks\""));
        assertTrue(rpcListBody.contains("\"pageSize\""));
        assertTrue(rpcListBody.contains("\"totalSize\""));

        String httpRequest = "{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"subscribe-http\"}]}}";
        String httpSend = httpJson.post().uri("/message:send").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(httpRequest).exchange().expectStatus().isOk().expectBody(String.class).returnResult()
                .getResponseBody();
        String httpTaskId = objectMapper.readTree(httpSend).path("task").path("id").asText();
        assertFalse(httpTaskId.isBlank());
        String httpSubscribeError = httpJson.post().uri("/tasks/{taskId}:subscribe", httpTaskId)
                .header("A2A-Version", "1.0").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("{}").exchange().expectStatus().isBadRequest().expectBody(String.class).returnResult()
                .getResponseBody();
        assertTrue(httpSubscribeError.contains("UNSUPPORTED_OPERATION"), httpSubscribeError);
        String httpCancel = httpJson.post().uri("/tasks/{taskId}:cancel", httpTaskId).header("A2A-Version", "1.0")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(httpCancel.contains(httpTaskId));

        String rpcSend = "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-send-task\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"subscribe-rpc\"}]}}}";
        String rpcSendBody = jsonRpc.post().uri("/gateway/v1/a2a").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).bodyValue(rpcSend).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        String rpcTaskId = objectMapper.readTree(rpcSendBody).path("result").path("task").path("id").asText();
        assertFalse(rpcTaskId.isBlank());
        String rpcSubscribe = "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-subscribe\",\"method\":\"SubscribeToTask\","
                + "\"params\":{\"id\":\"" + rpcTaskId + "\"}}";
        String rpcSubscribeError = jsonRpc.post().uri("/gateway/v1/a2a")
                .header("A2A-Version", "1.0").header("X-A2A-Target-Agent", "agent-a")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).bodyValue(rpcSubscribe)
                .exchange().expectStatus().isBadRequest().expectBody(String.class).returnResult().getResponseBody();
        assertTrue(rpcSubscribeError.contains("-32004"), rpcSubscribeError);
        String rpcCancel = "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-cancel-task\",\"method\":\"CancelTask\","
                + "\"params\":{\"id\":\"" + rpcTaskId + "\"}}";
        String rpcCancelBody = jsonRpc.post().uri("/gateway/v1/a2a").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).bodyValue(rpcCancel).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(rpcCancelBody.contains(rpcTaskId));
        assertTrue(rpcCancelBody.contains("TASK_STATE_CANCELED"));
    }

    @Test
    void keepsHttpJsonAndJsonRpcErrorsVersionedAndEquivalent() {
        WebTestClient httpJson = webClientForAgent(agent(upstream.port()));
        WebTestClient jsonRpc = jsonRpcWebClientForAgent(agent(upstream.port()));
        String httpRequest = "{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"bad-response\"}]}}";
        String httpError = httpJson.post().uri("/message:send").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(httpRequest).exchange().expectStatus().isEqualTo(502).expectBody(String.class)
                .returnResult().getResponseBody();
        String rpcRequest = "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-error\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"bad-response\"}]}}}";
        String rpcError = jsonRpc.post().uri("/gateway/v1/a2a").header("A2A-Version", "1.0")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).bodyValue(rpcRequest).exchange().expectStatus().isEqualTo(502)
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(httpError.contains("GATEWAY_UPSTREAM_PROTOCOL_ERROR"));
        assertTrue(httpError.contains("\"code\":502"), httpError);
        assertTrue(httpError.contains("\"status\":\"BAD_GATEWAY\""), httpError);
        assertTrue(httpError.contains("google.rpc.ErrorInfo"), httpError);
        assertTrue(httpError.contains("\"reason\":\"INVALID_AGENT_RESPONSE\""), httpError);
        assertTrue(rpcError.contains("\"gatewayCode\":\"GATEWAY_UPSTREAM_PROTOCOL_ERROR\""));
        assertTrue(rpcError.contains("\"data\":["), rpcError);
        assertTrue(rpcError.contains("google.rpc.ErrorInfo"), rpcError);
        assertTrue(rpcError.contains("\"reason\":\"INVALID_AGENT_RESPONSE\""), rpcError);

        String invalidVersion = jsonRpc.post().uri("/gateway/v1/a2a").header("A2A-Version", "0.2.1")
                .header("X-A2A-Target-Agent", "agent-a").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).bodyValue(rpcRequest).exchange().expectStatus().isBadRequest()
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(invalidVersion.contains("VERSION_NOT_SUPPORTED"));
        assertTrue(invalidVersion.contains("-32009"));
    }

    private GatewayHttpJsonController controllerForTest() {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(agent(upstream.port()));
        InMemoryTaskRouteStore routes = new InMemoryTaskRouteStore(8);
        GatewayForwarder forwarder = new GatewayForwarder(registry,
                new DeterministicRouteResolver(registry, routes, new DefaultAuthorizationPolicy(), Map.of()),
                new WeightedLeastActiveLoadBalancer(), new JsonRpcProtocolAdapter(), transport, noCredentials(), routes,
                null);
        return new GatewayHttpJsonController(forwarder, new HttpJsonProtocolAdapter(), new GatewayProperties());
    }

    private WebTestClient webClient(PrincipalContext principal) {
        GatewayAuthenticationToken token = new GatewayAuthenticationToken(principal);
        return WebTestClient.bindToController(controller).controllerAdvice(new GatewayHttpErrorHandler())
                .webFilter((exchange, chain) -> chain.filter(exchange).contextWrite(withAuthentication(token))).build();
    }

    private WebTestClient webClientForAgent(AgentDefinition definition) {
        return webClientForAgent(definition, 10, principal());
    }

    private WebTestClient webClientForAgent(AgentDefinition definition, int maxConcurrentStreams) {
        return webClientForAgent(definition, maxConcurrentStreams, principal());
    }

    private WebTestClient webClientForAgent(AgentDefinition definition, int maxConcurrentStreams,
            PrincipalContext principal) {
        return webClientForAgent(definition, maxConcurrentStreams, principal, new InMemoryTaskRouteStore(32));
    }

    private WebTestClient webClientForAgent(AgentDefinition definition, int maxConcurrentStreams,
            PrincipalContext principal, TaskRouteStore routes) {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(definition);
        GatewayForwarder forwarder = new GatewayForwarder(registry,
                new DeterministicRouteResolver(registry, routes, new DefaultAuthorizationPolicy(), Map.of()),
                new WeightedLeastActiveLoadBalancer(), new JsonRpcProtocolAdapter(), transport, noCredentials(), routes,
                new InMemoryIdempotencyStore(32), Duration.ofMinutes(5), Duration.ofSeconds(5),
                new TenantStreamLimiter(maxConcurrentStreams), GatewayMetrics.noop(),
                Map.of("JSONRPC", new JsonRpcProtocolAdapter(), "HTTP+JSON", new HttpJsonProtocolAdapter()),
                new DefaultAgentInterfaceSelector());
        GatewayHttpJsonController dynamicController = new GatewayHttpJsonController(forwarder,
                new HttpJsonProtocolAdapter(), new GatewayProperties());
        GatewayAuthenticationToken token = new GatewayAuthenticationToken(principal);
        return WebTestClient.bindToController(dynamicController).controllerAdvice(new GatewayHttpErrorHandler())
                .webFilter((exchange, chain) -> chain.filter(exchange).contextWrite(withAuthentication(token))).build();
    }

    private WebTestClient jsonRpcWebClientForAgent(AgentDefinition definition) {
        return jsonRpcWebClientForAgent(definition, principal());
    }

    private WebTestClient jsonRpcWebClientForAgent(AgentDefinition definition, PrincipalContext principal) {
        return jsonRpcWebClientForAgent(definition, principal, new InMemoryTaskRouteStore(32));
    }

    private WebTestClient jsonRpcWebClientForAgent(AgentDefinition definition, PrincipalContext principal,
            TaskRouteStore routes) {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.replace(definition);
        GatewayForwarder forwarder = new GatewayForwarder(registry,
                new DeterministicRouteResolver(registry, routes, new DefaultAuthorizationPolicy(), Map.of()),
                new WeightedLeastActiveLoadBalancer(), new JsonRpcProtocolAdapter(), transport, noCredentials(), routes,
                new InMemoryIdempotencyStore(32), Duration.ofMinutes(5), Duration.ofSeconds(5),
                new TenantStreamLimiter(10), GatewayMetrics.noop(),
                Map.of("JSONRPC", new JsonRpcProtocolAdapter(), "HTTP+JSON", new HttpJsonProtocolAdapter()),
                new DefaultAgentInterfaceSelector());
        GatewayJsonRpcController jsonRpcController = new GatewayJsonRpcController(forwarder,
                new JsonRpcProtocolAdapter(), new GatewayProperties());
        GatewayAuthenticationToken token = new GatewayAuthenticationToken(principal);
        return WebTestClient.bindToController(jsonRpcController).controllerAdvice(new GatewayJsonRpcErrorHandler())
                .webFilter((exchange, chain) -> chain.filter(exchange).contextWrite(withAuthentication(token))).build();
    }

    private Publisher<Void> handleUpstream(reactor.netty.http.server.HttpServerRequest request,
            reactor.netty.http.server.HttpServerResponse response) {
        String accept = request.requestHeaders().get("Accept");
        String contentType = request.requestHeaders().get("Content-Type");
        boolean httpJson = contentType != null && contentType.contains("application/a2a+json");
        return request.receive().aggregate().asString().defaultIfEmpty("").flatMap(body -> {
            if (body.contains("bad-response")) {
                return response.status(200).header("Content-Type", "application/json")
                        .sendString(Mono.just("{\"invalid\":")).then();
            }
            if (body.contains("stream-upstream-error")) {
                return response.status(404).header("Content-Type", "application/a2a+json")
                        .sendString(Mono.just("{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\","
                                + "\"message\":\"missing\"}}"))
                        .then();
            }
            if (body.contains("upstream-error")) {
                return response.status(503).header("Content-Type", "application/json")
                        .sendString(Mono.just("{\"error\":{\"code\":\"UNAVAILABLE\"}}")).then();
            }
            if (accept != null && accept.contains("text/event-stream")) {
                if (body.contains("load-sse")) {
                    Flux<String> events = Flux.interval(Duration.ofMillis(100))
                            .map(index -> "id: load-" + index + "\nevent: status-update\ndata: "
                                    + streamingResponse(false) + "\n\n");
                    return response.header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE).sendString(events)
                            .then();
                }
                if (body.contains("cancel-stream")) {
                    Flux<String> events = Flux.interval(Duration.ofMillis(10))
                            .map(index -> "id: cancel-" + index + "\nevent: status-update\ndata: "
                                    + streamingResponse(false) + "\n\n")
                            .doOnCancel(() -> upstreamCancelled.set(true));
                    return response.header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE).sendString(events)
                            .then();
                }
                String first = httpJson ? httpJsonStreamingResponse(false) : streamingResponse(false);
                String terminal = httpJson ? httpJsonStreamingResponse(true) : streamingResponse(true);
                Flux<String> events = Flux.just("id: e-1\nevent: status-update\ndata: " + first + "\n\n",
                        "id: e-2\nevent: status-update\ndata: " + terminal + "\n\n")
                        .delayElements(Duration.ofMillis(10));
                return response.header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE).sendString(events).then();
            }
            if (httpJson) {
                return response.status(200).header("Content-Type", "application/a2a+json")
                        .sendString(Mono.just(httpJsonSyncResponse())).then();
            }
            return response.status(200).header("Content-Type", "application/json")
                    .sendString(Mono.just(syncResponse(body))).then();
        });
    }

    private Publisher<Void> handleSecondUpstream(reactor.netty.http.server.HttpServerRequest request,
            reactor.netty.http.server.HttpServerResponse response) {
        return request.receive().aggregate().asString().defaultIfEmpty("").flatMap(body -> response.status(200)
                .header("Content-Type", "application/json")
                .sendString(Mono.just(syncResponse(body).replace("\"up-1\"", "\"up-b-1\"")
                        .replace("\"up-c\"", "\"up-b-c\"").replace("\"task\":{", "\"served-by-b\":true,\"task\":{")))
                .then());
    }

    private String syncResponse(String requestBody) {
        try {
            JsonNode request = objectMapper.readTree(requestBody);
            Object id = objectMapper.convertValue(request.path("id"), Object.class);
            String method = request.path("method").asText();
            String state = method.contains("Cancel") ? "TASK_STATE_CANCELED" : "TASK_STATE_COMPLETED";
            return objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "result",
                    Map.of("task", Map.of("id", "up-1", "contextId", "up-c",
                            "status", Map.of("state", state)))));
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String streamingResponse(boolean terminal) {
        String payload = terminal ? "{\"statusUpdate\":{\"taskId\":\"up-1\",\"contextId\":\"up-c\","
                + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}}"
                : "{\"task\":{\"id\":\"up-1\",\"contextId\":\"up-c\","
                        + "\"status\":{\"state\":\"TASK_STATE_WORKING\"}}}";
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + payload + "}";
    }

    private String httpJsonSyncResponse() {
        return "{\"task\":{\"id\":\"up-1\",\"contextId\":\"up-c\","
                + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}}";
    }

    private String httpJsonStreamingResponse(boolean terminal) {
        return terminal ? "{\"statusUpdate\":{\"taskId\":\"up-1\",\"contextId\":\"up-c\","
                + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}}"
                : "{\"task\":{\"id\":\"up-1\",\"contextId\":\"up-c\","
                        + "\"status\":{\"state\":\"TASK_STATE_WORKING\"}}}";
    }

    private AgentDefinition agent(int port) {
        return agent(port, "JSONRPC");
    }

    private AgentDefinition httpJsonAgent(int port) {
        return agent(port, "HTTP+JSON");
    }

    private AgentDefinition agent(int port, String protocolBinding) {
        String endpoint = "http://127.0.0.1:" + port + "/a2a";
        String interfaceKey = protocolBinding.equals("JSONRPC") ? "jsonrpc" : "http-json";
        AgentInstance instance = new AgentInstance("instance-1", "http://127.0.0.1:" + port + "/card",
                List.of(new AgentInterface(interfaceKey, endpoint, protocolBinding, "1.0", null)), 1, null,
                AgentInstance.HealthStatus.HEALTHY, "e2e-hash", Instant.now());
        return new AgentDefinition("tenant-a", "agent-a", "Agent A", true,
                List.of(new AgentSkillDefinition("echo", "Echo", "Echo", List.of("sample"),
                        List.of("text/plain"), List.of("text/plain"))), Map.of(), ProtocolPolicy.a2aV1Mvp(),
                List.of(instance), Map.of("capabilities", Map.of("streaming", true)));
    }

    private AgentDefinition agentWithInstances(int firstPort, int secondPort) {
        return new AgentDefinition("tenant-a", "agent-a", "Agent A", true,
                List.of(new AgentSkillDefinition("echo", "Echo", "Echo", List.of("sample"),
                        List.of("text/plain"), List.of("text/plain"))), Map.of(), ProtocolPolicy.a2aV1Mvp(),
                List.of(agentInstance("instance-a", firstPort), agentInstance("instance-b", secondPort)));
    }

    private AgentInstance agentInstance(String instanceId, int port) {
        String endpoint = "http://127.0.0.1:" + port + "/a2a";
        return new AgentInstance(instanceId, "http://127.0.0.1:" + port + "/card",
                List.of(new AgentInterface("jsonrpc-" + instanceId, endpoint, "JSONRPC", "1.0", null)), 1, null,
                AgentInstance.HealthStatus.HEALTHY, "e2e-hash-" + instanceId, Instant.now());
    }

    private PrincipalContext principal() {
        return new PrincipalContext("tenant-a", "e2e-user", Set.of("*"), Map.of(), "e2e-fingerprint");
    }

    private CredentialProvider noCredentials() {
        return (tenant, reference, target) -> Mono.empty();
    }

}
