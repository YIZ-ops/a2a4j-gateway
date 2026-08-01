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

import io.github.a2ap.gateway.core.security.GatewayPrincipalFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

class GatewayJwtAuthenticationConverterTest {

    @Test
    void validatesIssuerAudienceTimeAndBuildsGatewayPrincipal() {
        GatewaySecurityProperties.JwtProperties properties = properties();
        assertEquals("https://issuer.example.test", jwt("a2a-gateway").getIssuer().toString());
        GatewayJwtAuthenticationConverter converter = new GatewayJwtAuthenticationConverter(properties,
                new GatewayPrincipalFactory(), Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC));
        GatewayAuthenticationToken authentication = converter.convert(jwt("a2a-gateway")).block();

        assertEquals("tenant-a", authentication.principalContext().tenantId());
        assertEquals("user-a", authentication.getName());
        assertEquals(null, authentication.getCredentials());
    }

    @Test
    void rejectsUnexpectedAudience() {
        GatewayJwtAuthenticationConverter converter = new GatewayJwtAuthenticationConverter(properties(),
                new GatewayPrincipalFactory());

        assertThrows(OAuth2AuthenticationException.class, () -> converter.convert(jwt("other")).block());
    }

    private GatewaySecurityProperties.JwtProperties properties() {
        GatewaySecurityProperties.JwtProperties properties = new GatewaySecurityProperties.JwtProperties();
        properties.setIssuerUri("https://issuer.example.test");
        properties.setAudiences(Set.of("a2a-gateway"));
        properties.setAuthorityClaims(List.of("scope"));
        return properties;
    }

    private Jwt jwt(String audience) {
        return Jwt.withTokenValue("raw-token-must-not-be-forwarded")
                .header("alg", "RS256")
                .issuer("https://issuer.example.test")
                .audience(List.of(audience))
                .subject("user-a")
                .claim("tenant_id", "tenant-a")
                .claim("scope", "agent:discover")
                .issuedAt(Instant.parse("2026-07-30T23:59:00Z"))
                .expiresAt(Instant.parse("2026-07-31T01:00:00Z"))
                .build();
    }

}
