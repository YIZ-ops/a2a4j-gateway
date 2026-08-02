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

import io.github.a2ap.gateway.api.GatewayHeaders;
import java.util.UUID;
import org.springframework.web.server.ServerWebExchange;

/** Resolves one stable Gateway request identifier for the lifetime of an exchange. */
final class GatewayRequestIdResolver {

    private static final String ATTRIBUTE = GatewayRequestIdResolver.class.getName() + ".requestId";

    private GatewayRequestIdResolver() {
    }

    static String resolve(ServerWebExchange exchange) {
        return (String) exchange.getAttributes().computeIfAbsent(ATTRIBUTE, ignored -> {
            String supplied = exchange.getRequest().getHeaders().getFirst(GatewayHeaders.GATEWAY_REQUEST_ID);
            return supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied;
        });
    }

}
