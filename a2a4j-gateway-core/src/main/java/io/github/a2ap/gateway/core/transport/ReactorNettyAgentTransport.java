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

package io.github.a2ap.gateway.core.transport;

import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.OutboundCredentials;
import io.github.a2ap.gateway.api.model.OutboundRequest;
import io.github.a2ap.gateway.api.model.OutboundResponse;
import io.github.a2ap.gateway.api.spi.AgentTransport;
import io.github.a2ap.gateway.core.exception.AgentTransportException;
import io.github.a2ap.gateway.core.discovery.AgentCardUrlPolicy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.timeout.ReadTimeoutException;

/** Reactor Netty transport with SSRF checks, bounded response bodies and phase timeouts. */
public final class ReactorNettyAgentTransport implements AgentTransport, AutoCloseable {

    private final AgentCardUrlPolicy urlPolicy;

    private final Duration connectTimeout;

    private final Duration responseTimeout;

    private final int maxResponseBytes;

    private final int maxEventBytes;

    private final ConnectionProvider connectionProvider;

    /** Creates a transport with a bounded connection pool and response size. */
    public ReactorNettyAgentTransport(AgentCardUrlPolicy urlPolicy, Duration connectTimeout,
            Duration responseTimeout, int maxResponseBytes) {
        this(urlPolicy, connectTimeout, responseTimeout, maxResponseBytes, maxResponseBytes,
                ConnectionProvider.builder("a2a-gateway-agent").maxConnections(100).pendingAcquireMaxCount(200)
                        .build());
    }

    /** Creates a transport with an explicit SSE event-size limit. */
    public ReactorNettyAgentTransport(AgentCardUrlPolicy urlPolicy, Duration connectTimeout,
            Duration responseTimeout, int maxResponseBytes, int maxEventBytes) {
        this(urlPolicy, connectTimeout, responseTimeout, maxResponseBytes, maxEventBytes,
                ConnectionProvider.builder("a2a-gateway-agent").maxConnections(100).pendingAcquireMaxCount(200)
                        .build());
    }

    /** Creates a transport with an injected Reactor Netty connection provider. */
    public ReactorNettyAgentTransport(AgentCardUrlPolicy urlPolicy, Duration connectTimeout,
            Duration responseTimeout, int maxResponseBytes, ConnectionProvider connectionProvider) {
        this(urlPolicy, connectTimeout, responseTimeout, maxResponseBytes, maxResponseBytes, connectionProvider);
    }

    /** Creates a transport with injected pool and explicit SSE event-size limit. */
    public ReactorNettyAgentTransport(AgentCardUrlPolicy urlPolicy, Duration connectTimeout,
            Duration responseTimeout, int maxResponseBytes, int maxEventBytes,
            ConnectionProvider connectionProvider) {
        this.urlPolicy = Objects.requireNonNull(urlPolicy, "urlPolicy");
        this.connectTimeout = positive(connectTimeout, "connectTimeout");
        this.responseTimeout = positive(responseTimeout, "responseTimeout");
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
        if (maxEventBytes < 1 || maxEventBytes > maxResponseBytes) {
            throw new IllegalArgumentException("maxEventBytes must be positive and no greater than maxResponseBytes");
        }
        this.maxEventBytes = maxEventBytes;
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    }

    @Override
    public Flux<OutboundResponse> exchange(AgentInstance target, OutboundRequest request,
            OutboundCredentials credentials) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(request, "request");
        URI uri;
        try {
            uri = urlPolicy.validateConfiguredUrl(request.endpointUrl());
        }
        catch (IllegalArgumentException ex) {
            return Flux.error(new AgentTransportException(AgentTransportException.Code.NETWORK_POLICY,
                    "upstream destination rejected by network policy"));
        }
        return Mono.fromRunnable(() -> urlPolicy.validateResolved(uri))
                .thenMany(send(uri, request, credentials))
                .timeout(responseTimeout)
                .onErrorMap(this::categorize);
    }

    /**
     * Exchanges a streaming request without aggregating an SSE response. Each complete
     * SSE event is emitted as one {@link OutboundResponse}; cancellation propagates to
     * the underlying Reactor Netty connection.
     */
    @Override
    public Flux<OutboundResponse> exchangeStream(AgentInstance target, OutboundRequest request,
            OutboundCredentials credentials) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(request, "request");
        URI uri;
        try {
            uri = urlPolicy.validateConfiguredUrl(request.endpointUrl());
        }
        catch (IllegalArgumentException ex) {
            return Flux.error(new AgentTransportException(AgentTransportException.Code.NETWORK_POLICY,
                    "upstream destination rejected by network policy"));
        }
        SseEventCodec codec = new SseEventCodec(maxEventBytes);
        return Mono.fromRunnable(() -> urlPolicy.validateResolved(uri))
                .thenMany(streamSend(uri, request, credentials, codec))
                .timeout(responseTimeout)
                .onErrorMap(this::categorize);
    }

    @Override
    public void close() {
        connectionProvider.disposeLater().block(responseTimeout);
    }

    private Flux<OutboundResponse> send(URI uri, OutboundRequest request, OutboundCredentials credentials) {
        HttpClient client = client(request, credentials);
        HttpClient.RequestSender sender = client.request(HttpMethod.valueOf(request.httpMethod()))
                .uri(uri.toString());
        HttpClient.ResponseReceiver<?> receiver = request.body().isEmpty() ? sender
                : sender.send((requestSender, outbound) -> outbound.sendString(Mono.just(request.body()),
                        StandardCharsets.UTF_8));
        return receiver.responseSingle((response, content) -> content.asByteArray().flatMap(bytes -> {
            if (bytes.length > maxResponseBytes) {
                return Mono.error(new AgentTransportException(AgentTransportException.Code.RESPONSE_TOO_LARGE,
                        "upstream response exceeds configured size"));
            }
            Map<String, String> headers = new LinkedHashMap<>();
            response.responseHeaders().forEach(entry -> headers.putIfAbsent(entry.getKey(), entry.getValue()));
            return Mono.just(new OutboundResponse(request.protocol(), response.status().code(),
                    new String(bytes, StandardCharsets.UTF_8), headers, true));
        }))
                .flux();
    }

    private Flux<OutboundResponse> streamSend(URI uri, OutboundRequest request,
            OutboundCredentials credentials, SseEventCodec codec) {
        HttpClient client = client(request, credentials);
        HttpClient.RequestSender sender = client.request(HttpMethod.valueOf(request.httpMethod()))
                .uri(uri.toString());
        HttpClient.ResponseReceiver<?> receiver = request.body().isEmpty() ? sender
                : sender.send((requestSender, outbound) -> outbound.sendString(Mono.just(request.body()),
                        StandardCharsets.UTF_8));
        return receiver.response((response, content) -> {
            int statusCode = response.status().code();
            if (statusCode < 200 || statusCode >= 300) {
                Map<String, String> headers = new LinkedHashMap<>();
                response.responseHeaders().forEach(entry -> headers.putIfAbsent(entry.getKey(), entry.getValue()));
                return content.asString(StandardCharsets.UTF_8).collectList()
                        .flatMapMany(parts -> {
                            String body = String.join("", parts);
                            if (body.getBytes(StandardCharsets.UTF_8).length > maxResponseBytes) {
                                return Flux.error(new AgentTransportException(AgentTransportException.Code.RESPONSE_TOO_LARGE,
                                        "upstream response exceeds configured size"));
                            }
                            return Flux.just(new OutboundResponse(request.protocol(), statusCode, body, headers, true));
                        });
            }
            Map<String, String> headers = new LinkedHashMap<>();
            response.responseHeaders().forEach(entry -> headers.putIfAbsent(entry.getKey(), entry.getValue()));
            String contentType = response.responseHeaders().get("Content-Type");
            if (contentType == null || !contentType.toLowerCase().startsWith("text/event-stream")) {
                return content.asString(StandardCharsets.UTF_8).collectList()
                        .map(parts -> new OutboundResponse(request.protocol(), statusCode,
                                String.join("", parts), headers, true)).flux();
            }
            SseEventCodec.Parser parser = codec.parser();
            return content.asString(StandardCharsets.UTF_8)
                    .concatWith(Mono.just("\n"))
                    .flatMapIterable(chunk -> parser.feed(chunk).stream()
                            .map(event -> codec.toResponse(event, request.protocol(), statusCode, headers, false))
                            .toList())
                    .concatWith(Flux.defer(() -> Flux.fromIterable(parser.complete())
                            .map(event -> codec.toResponse(event, request.protocol(), statusCode, headers, true))));
        });
    }

    private HttpClient client(OutboundRequest request, OutboundCredentials credentials) {
        return HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
                .responseTimeout(responseTimeout)
                .followRedirect(false)
                .headers(headers -> {
                    request.headers().forEach(headers::set);
                    String contentType = request.headers().get("Content-Type");
                    headers.set("Content-Type", contentType == null ? request.protocol().mediaType() : contentType);
                    headers.set("A2A-Version", request.protocol().protocolVersion());
                    if (credentials != null) {
                        headers.set("Authorization", credentials.scheme() + " " + credentials.value());
                    }
                });
    }

    private Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private Throwable categorize(Throwable error) {
        if (error instanceof AgentTransportException) {
            return error;
        }
        if (error instanceof TimeoutException || error instanceof ReadTimeoutException) {
            return new AgentTransportException(AgentTransportException.Code.TIMEOUT,
                    "upstream transport timed out");
        }
        if (error instanceof IllegalArgumentException) {
            return new AgentTransportException(AgentTransportException.Code.NETWORK_POLICY,
                    "upstream destination rejected by network policy");
        }
        return new AgentTransportException(AgentTransportException.Code.NETWORK,
                "upstream network exchange failed");
    }

}
