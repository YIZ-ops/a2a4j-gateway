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
import io.github.a2ap.gateway.spring.support.GatewayRequestIdResolver;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class GatewayRequestIdResolverTest {

    @Test
    void reusesGeneratedIdentifierForTheWholeExchange() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/a2a").build());

        String first = GatewayRequestIdResolver.resolve(exchange);
        String second = GatewayRequestIdResolver.resolve(exchange);

        assertEquals(first, second);
        assertEquals(first, UUID.fromString(first).toString());
    }

    @Test
    void preservesCallerSuppliedIdentifier() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/a2a")
                .header(GatewayHeaders.GATEWAY_REQUEST_ID, "request-123").build());

        assertEquals("request-123", GatewayRequestIdResolver.resolve(exchange));
        assertEquals("request-123", GatewayRequestIdResolver.resolve(exchange));
    }

}
