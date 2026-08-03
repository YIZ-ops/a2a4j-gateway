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

package io.github.a2ap.gateway.spring.security;

import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.core.security.GatewayPrincipalFactory;
import io.github.a2ap.gateway.spring.autoconfigure.GatewaySecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

/** Converts a signature-verified JWT into a tenant-scoped gateway authentication. */
public final class GatewayJwtAuthenticationConverter
        implements Converter<Jwt, Mono<GatewayAuthenticationToken>> {

    private final GatewaySecurityProperties.JwtProperties properties;

    private final GatewayPrincipalFactory principalFactory;

    private final Clock clock;

    /** Creates a converter using the system clock. */
    public GatewayJwtAuthenticationConverter(GatewaySecurityProperties.JwtProperties properties,
            GatewayPrincipalFactory principalFactory) {
        this(properties, principalFactory, Clock.systemUTC());
    }

    /** Creates a converter with an injectable clock for deterministic validation tests. */
    public GatewayJwtAuthenticationConverter(GatewaySecurityProperties.JwtProperties properties,
            GatewayPrincipalFactory principalFactory, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.principalFactory = Objects.requireNonNull(principalFactory, "principalFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Mono<GatewayAuthenticationToken> convert(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt");
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        if (properties.getIssuerUri() != null && !properties.getIssuerUri().isBlank()
                && !properties.getIssuerUri().equals(issuer)) {
            return invalid("issuer claim is not trusted");
        }
        List<String> audiences = properties.getAudiences().stream().toList();
        if (audiences.stream().noneMatch(jwt.getAudience()::contains)) {
            return invalid("audience claim is not trusted");
        }
        Duration skew = properties.getClockSkew() == null ? Duration.ZERO : properties.getClockSkew();
        Instant now = Instant.now(clock);
        if (jwt.getExpiresAt() != null && now.minus(skew).isAfter(jwt.getExpiresAt())) {
            return invalid("token has expired");
        }
        if (jwt.getNotBefore() != null && now.plus(skew).isBefore(jwt.getNotBefore())) {
            return invalid("token is not active yet");
        }
        try {
            PrincipalContext principal = principalFactory.fromClaims(jwt.getClaims());
            return Mono.just(new GatewayAuthenticationToken(principal));
        }
        catch (IllegalArgumentException ex) {
            return invalid(ex.getMessage());
        }
    }

    private Mono<GatewayAuthenticationToken> invalid(String message) {
        return Mono.error(new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), message));
    }

}
