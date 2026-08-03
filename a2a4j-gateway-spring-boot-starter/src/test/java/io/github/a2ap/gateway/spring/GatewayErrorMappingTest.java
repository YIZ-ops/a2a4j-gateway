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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.a2ap.gateway.core.exception.GatewayForwardingException;
import io.github.a2ap.gateway.spring.error.GatewayHttpErrorHandler;
import io.github.a2ap.gateway.spring.error.GatewayJsonRpcErrorHandler;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class GatewayErrorMappingTest {

    @Test
    void mapsUnsupportedPushToHttp400AndJsonRpcMinus32003() {
        GatewayForwardingException error = new GatewayForwardingException(
                GatewayForwardingException.Code.PUSH_NOTIFICATION_NOT_SUPPORTED, "push is unavailable");
        var http = new GatewayHttpErrorHandler().handle(error);
        assertEquals(400, http.getStatusCode().value());
        assertTrue(body(http).contains("PUSH_NOTIFICATION_NOT_SUPPORTED"));

        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/a2a").build());
        var rpc = new GatewayJsonRpcErrorHandler().handle(error, exchange);
        assertEquals(400, rpc.getStatusCode().value());
        assertTrue(body(rpc).contains("-32003"));
    }

    private String body(org.springframework.http.ResponseEntity<byte[]> response) {
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }

}
