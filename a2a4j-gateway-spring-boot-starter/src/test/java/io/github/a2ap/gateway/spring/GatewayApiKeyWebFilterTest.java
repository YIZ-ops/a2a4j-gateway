/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.a2ap.gateway.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.a2ap.gateway.api.model.PrincipalContext;
import io.github.a2ap.gateway.core.security.ApiKeyAuthenticator;
import io.github.a2ap.gateway.core.security.ApiKeyCredential;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

class GatewayApiKeyWebFilterTest {

    @Test
    void keepsTheAuthenticatedResponseStatusAfterTheDownstreamChainCompletes() {
        PrincipalContext principal = new PrincipalContext("tenant-a", "user-a", Set.of("*"), Map.of(), "fingerprint");
        ApiKeyAuthenticator authenticator = new ApiKeyAuthenticator(
                List.of(new ApiKeyCredential("local", "secret", principal)));
        GatewayApiKeyWebFilter filter = new GatewayApiKeyWebFilter("X-A2A-API-Key", authenticator);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/gateway/v1/agents").header("X-A2A-API-Key", "secret").build());

        filter.filter(exchange, downstream(exchange)).block();

        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
    }

    private WebFilterChain downstream(ServerWebExchange exchange) {
        return ignored -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };
    }

}
