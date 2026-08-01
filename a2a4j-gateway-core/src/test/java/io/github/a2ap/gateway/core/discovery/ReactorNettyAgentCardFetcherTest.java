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

package io.github.a2ap.gateway.core.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

class ReactorNettyAgentCardFetcherTest {

    @Test
    void sendsA2AVersionAndFetchesOnlySuccessfulResponses() {
        AtomicReference<String> version = new AtomicReference<>();
        DisposableServer server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    version.set(request.requestHeaders().get("A2A-Version"));
                    return response.status(200).header("Content-Type", "application/json")
                            .sendString(Mono.just("{}"));
                })
                .bindNow();
        try {
            AgentCardUrlPolicy policy = new AgentCardUrlPolicy(true, true, Set.of(), 1024);
            ReactorNettyAgentCardFetcher fetcher = new ReactorNettyAgentCardFetcher(policy, Duration.ofSeconds(3));
            assertEquals("{}", fetcher.fetch("http://127.0.0.1:" + server.port() + "/card")
                    .block(Duration.ofSeconds(3)));
            assertEquals("1.0", version.get());
        }
        finally {
            server.disposeNow();
        }
    }

}
