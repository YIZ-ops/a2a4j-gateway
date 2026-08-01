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

import io.github.a2ap.gateway.core.security.ApiKeyAuthenticator;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** WebFlux filter for explicitly enabled API-key development mode. */
public final class GatewayApiKeyWebFilter implements WebFilter {

    private final String headerName;

    private final ApiKeyAuthenticator authenticator;

    /** Creates an API-key filter with a constant-time authenticator. */
    public GatewayApiKeyWebFilter(String headerName, ApiKeyAuthenticator authenticator) {
        this.headerName = Objects.requireNonNull(headerName, "headerName");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String presentedKey = exchange.getRequest().getHeaders().getFirst(headerName);
        if (presentedKey == null) {
            return chain.filter(exchange);
        }
        return authenticator.authenticate(presentedKey)
                .map(GatewayAuthenticationToken::new)
                .flatMap(authentication -> chain.filter(exchange).contextWrite(
                        ReactiveSecurityContextHolder.withSecurityContext(
                                Mono.just(new SecurityContextImpl(authentication))))
                        .thenReturn(Boolean.TRUE))
                .switchIfEmpty(Mono.defer(() -> unauthorized(exchange).thenReturn(Boolean.FALSE)))
                .then();
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

}
