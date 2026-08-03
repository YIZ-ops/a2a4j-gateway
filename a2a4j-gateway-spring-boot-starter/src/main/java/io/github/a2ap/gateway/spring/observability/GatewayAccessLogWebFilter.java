/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.a2ap.gateway.spring.observability;

import io.github.a2ap.gateway.api.GatewayHeaders;
import io.github.a2ap.gateway.api.GatewayTraceContext;
import io.github.a2ap.gateway.api.model.GatewayAuditEvent;
import io.github.a2ap.gateway.api.spi.GatewayAuditSink;
import io.github.a2ap.gateway.spring.support.GatewayRequestIdResolver;
import java.time.Duration;
import java.time.Instant;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Emits one body-free structured access record per HTTP exchange. */
public final class GatewayAccessLogWebFilter implements WebFilter {

    private final GatewayAuditSink auditSink;

    public GatewayAccessLogWebFilter(GatewayAuditSink auditSink) {
        this.auditSink = auditSink;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Instant started = Instant.now();
        String requestId = GatewayRequestIdResolver.resolve(exchange);
        String traceId = GatewayTraceContext.traceIdOr(
                exchange.getRequest().getHeaders().getFirst(GatewayHeaders.TRACEPARENT),
                requestId);
        exchange.getResponse().getHeaders().set(GatewayHeaders.GATEWAY_REQUEST_ID, requestId);
        return chain.filter(exchange)
                .doOnSuccess(ignored -> record(exchange, requestId, traceId, started, null))
                .doOnError(error -> record(exchange, requestId, traceId, started, error));
    }

    private void record(ServerWebExchange exchange, String requestId, String traceId, Instant started,
            Throwable error) {
        try {
            String outcome = error == null && (exchange.getResponse().getStatusCode() == null
                    || exchange.getResponse().getStatusCode().is2xxSuccessful()) ? "SUCCESS" : "ERROR";
            auditSink.record(GatewayAuditEvent.access(Instant.now(), requestId, traceId, "HTTP_ACCESS", outcome,
                    latencyBucket(Duration.between(started, Instant.now()).toMillis()),
                    error == null ? "" : error.getClass().getSimpleName()));
        }
        catch (RuntimeException ignored) {
            // Logging is best effort and must not affect the response.
        }
    }

    private static String latencyBucket(long millis) {
        return millis < 10 ? "<10ms" : millis < 100 ? "10-100ms" : millis < 1000 ? "100ms-1s" : ">=1s";
    }

}
