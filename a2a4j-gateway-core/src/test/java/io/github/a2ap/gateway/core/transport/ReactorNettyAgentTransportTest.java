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

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.OutboundCredentials;
import io.github.a2ap.gateway.api.model.OutboundRequest;
import io.github.a2ap.gateway.api.model.OutboundResponse;
import io.github.a2ap.gateway.api.model.ProtocolDescriptor;
import io.github.a2ap.gateway.core.discovery.AgentCardUrlPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

class ReactorNettyAgentTransportTest {

    @Test
    void sendsProtocolHeadersAndOutboundCredentialsThroughPooledTransport() {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> version = new AtomicReference<>();
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> {
                    authorization.set(request.requestHeaders().get("Authorization"));
                    version.set(request.requestHeaders().get("A2A-Version"));
                    return response.status(200).sendString(Mono.just("{\"ok\":true}"));
                }).bindNow();
        ReactorNettyAgentTransport transport = new ReactorNettyAgentTransport(
                new AgentCardUrlPolicy(true, true, Set.of(), 1024), Duration.ofSeconds(2), Duration.ofSeconds(3), 1024);
        try {
            OutboundResponse response = transport.exchange(instance(server.port()), new OutboundRequest(
                    ProtocolDescriptor.jsonRpc(), "http://127.0.0.1:" + server.port() + "/a2a", "{}",
                    Map.of("X-Test", "true"), "gateway-task"), new OutboundCredentials("Bearer", "secret"))
                    .blockFirst(Duration.ofSeconds(3));
            assertEquals(200, response.statusCode());
            assertEquals("{\"ok\":true}", response.body());
            assertEquals("Bearer secret", authorization.get());
            assertEquals("1.0", version.get());
        }
        finally {
            transport.close();
            server.disposeNow();
        }
    }

    @Test
    void streamsSseEventsWithoutAggregatingTheResponse() {
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> response.header("Content-Type", "text/event-stream")
                        .sendString(Flux.just("id: e-1\ndata: {\"one\":1}\n\n",
                                "id: e-2\ndata: {\"two\":2}\n\n")))
                .bindNow();
        ReactorNettyAgentTransport transport = new ReactorNettyAgentTransport(
                new AgentCardUrlPolicy(true, true, Set.of(), 1024), Duration.ofSeconds(2), Duration.ofSeconds(3), 1024);
        try {
            List<OutboundResponse> responses = transport.exchangeStream(instance(server.port()), new OutboundRequest(
                    ProtocolDescriptor.jsonRpcStreaming(), "http://127.0.0.1:" + server.port() + "/a2a", "{}",
                    Map.of("Accept", "text/event-stream"), "gateway-task"), null).collectList()
                    .block(Duration.ofSeconds(3));
            assertEquals(2, responses.size());
            assertEquals("{\"one\":1}", responses.get(0).body());
            assertEquals("e-2", responses.get(1).headers().get("SSE-Id"));
        }
        finally {
            transport.close();
            server.disposeNow();
        }
    }

    private AgentInstance instance(int port) {
        return new AgentInstance("instance-1", "http://127.0.0.1:" + port + "/card",
                List.of(new AgentInterface("jsonrpc", "http://127.0.0.1:" + port + "/a2a", "JSONRPC", "1.0",
                        null)), 1, null, AgentInstance.HealthStatus.HEALTHY, "hash", Instant.now());
    }

}
