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

import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.core.security.ApiKeyAuthenticator;
import io.github.a2ap.gateway.core.security.ApiKeyCredential;
import io.github.a2ap.gateway.core.security.GatewayPrincipalFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;

/** Optional WebFlux authentication auto-configuration for JWT or development API keys. */
@AutoConfiguration(after = GatewayAutoConfiguration.class)
@EnableConfigurationProperties(GatewaySecurityProperties.class)
@ConditionalOnProperty(prefix = "a2a.gateway.security", name = "enabled", havingValue = "true")
public class GatewaySecurityAutoConfiguration {

    /** Creates the framework-neutral principal factory from trusted claim mapping. */
    @Bean
    @ConditionalOnMissingBean(GatewayPrincipalFactory.class)
    public GatewayPrincipalFactory gatewayPrincipalFactory(GatewaySecurityProperties properties) {
        properties.validate();
        GatewaySecurityProperties.JwtProperties jwt = properties.getJwt();
        return new GatewayPrincipalFactory(jwt.getTenantClaim(), jwt.getSubjectClaim(), jwt.getAuthorityClaims());
    }

    /** Creates the JWT-to-gateway authentication converter. */
    @Bean
    @ConditionalOnProperty(prefix = "a2a.gateway.security", name = "mode", havingValue = "jwt",
            matchIfMissing = true)
    public GatewayJwtAuthenticationConverter gatewayJwtAuthenticationConverter(GatewaySecurityProperties properties,
            GatewayPrincipalFactory principalFactory) {
        properties.validate();
        return new GatewayJwtAuthenticationConverter(properties.getJwt(), principalFactory);
    }

    /** Creates the signature-verifying reactive JWT decoder. */
    @Bean
    @ConditionalOnProperty(prefix = "a2a.gateway.security", name = "mode", havingValue = "jwt",
            matchIfMissing = true)
    @ConditionalOnMissingBean(ReactiveJwtDecoder.class)
    public ReactiveJwtDecoder gatewayReactiveJwtDecoder(GatewaySecurityProperties properties) {
        properties.validate();
        GatewaySecurityProperties.JwtProperties jwt = properties.getJwt();
        if (jwt.getJwkSetUri() != null && !jwt.getJwkSetUri().isBlank()) {
            return NimbusReactiveJwtDecoder.withJwkSetUri(jwt.getJwkSetUri()).build();
        }
        return ReactiveJwtDecoders.fromIssuerLocation(jwt.getIssuerUri());
    }

    /** Creates the JWT-protected WebFlux filter chain. */
    @Bean
    @ConditionalOnProperty(prefix = "a2a.gateway.security", name = "mode", havingValue = "jwt",
            matchIfMissing = true)
    @ConditionalOnMissingBean(SecurityWebFilterChain.class)
    public SecurityWebFilterChain gatewayJwtSecurityWebFilterChain(ServerHttpSecurity http,
            GatewayJwtAuthenticationConverter converter) {
        return baseSecurity(http)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }

    /** Creates the API-key authenticator from environment-backed entries. */
    @Bean
    @ConditionalOnProperty(prefix = "a2a.gateway.security", name = "mode", havingValue = "api-key")
    @ConditionalOnMissingBean(ApiKeyAuthenticator.class)
    public ApiKeyAuthenticator gatewayApiKeyAuthenticator(GatewaySecurityProperties properties) {
        properties.validate();
        GatewayPrincipalFactory apiKeyPrincipalFactory = new GatewayPrincipalFactory();
        List<ApiKeyCredential> credentials = new ArrayList<>();
        for (GatewaySecurityProperties.ApiKeyProperties.Entry entry : properties.getApiKey().getEntries()) {
            String secret = System.getenv(entry.getSecretEnv());
            if (secret == null || secret.isBlank()) {
                throw new IllegalArgumentException("configured API-key environment value is unavailable");
            }
            Map<String, Object> claims = Map.of("tenant_id", entry.getTenantId(), "sub", entry.getSubject(),
                    "auth_type", "api_key", "key_id", entry.getKeyId(), "authorities", entry.getAuthorities());
            PrincipalContext principal = apiKeyPrincipalFactory.fromClaims(claims, entry.getAuthorities());
            credentials.add(new ApiKeyCredential(entry.getKeyId(), secret, principal));
        }
        return new ApiKeyAuthenticator(credentials);
    }

    /** Creates the API-key WebFlux filter. */
    @Bean
    @ConditionalOnProperty(prefix = "a2a.gateway.security", name = "mode", havingValue = "api-key")
    public GatewayApiKeyWebFilter gatewayApiKeyWebFilter(GatewaySecurityProperties properties,
            ApiKeyAuthenticator authenticator) {
        return new GatewayApiKeyWebFilter(properties.getApiKey().getHeaderName(), authenticator);
    }

    /** Creates the API-key-protected WebFlux filter chain. */
    @Bean
    @ConditionalOnProperty(prefix = "a2a.gateway.security", name = "mode", havingValue = "api-key")
    @ConditionalOnMissingBean(SecurityWebFilterChain.class)
    public SecurityWebFilterChain gatewayApiKeySecurityWebFilterChain(ServerHttpSecurity http,
            GatewayApiKeyWebFilter apiKeyWebFilter) {
        return baseSecurity(http).addFilterAt(apiKeyWebFilter, SecurityWebFiltersOrder.AUTHENTICATION).build();
    }

    private ServerHttpSecurity baseSecurity(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeExchange(exchanges -> exchanges.pathMatchers("/actuator/health/**", "/actuator/info")
                        .permitAll().anyExchange().authenticated());
    }

}
