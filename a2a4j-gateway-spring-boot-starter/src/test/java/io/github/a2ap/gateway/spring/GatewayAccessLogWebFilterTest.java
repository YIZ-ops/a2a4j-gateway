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

import io.github.a2ap.gateway.api.GatewayHeaders;
import io.github.a2ap.gateway.api.model.GatewayAuditEvent;
import io.github.a2ap.gateway.spring.observability.GatewayAccessLogWebFilter;
import io.github.a2ap.gateway.spring.support.GatewayRequestIdResolver;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class GatewayAccessLogWebFilterTest {

    @Test
    void accessAndDataPlaneStagesShareTheGeneratedRequestId() {
        AtomicReference<GatewayAuditEvent> audit = new AtomicReference<>();
        AtomicReference<String> dataPlaneRequestId = new AtomicReference<>();
        GatewayAccessLogWebFilter filter = new GatewayAccessLogWebFilter(audit::set);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/a2a").build());

        filter.filter(exchange, current -> {
            dataPlaneRequestId.set(GatewayRequestIdResolver.resolve(current));
            return Mono.empty();
        }).block();

        assertEquals(dataPlaneRequestId.get(), audit.get().requestId());
        assertEquals(dataPlaneRequestId.get(), audit.get().traceId());
        assertEquals(dataPlaneRequestId.get(),
                exchange.getResponse().getHeaders().getFirst(GatewayHeaders.GATEWAY_REQUEST_ID));
    }

}
