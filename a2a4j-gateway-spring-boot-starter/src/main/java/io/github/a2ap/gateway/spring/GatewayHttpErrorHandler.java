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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.a2ap.gateway.core.forwarding.GatewayForwardingException;
import io.github.a2ap.gateway.core.routing.RouteResolutionException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps gateway and protocol failures to a stable HTTP+JSON error envelope. */
@RestControllerAdvice(assignableTypes = { GatewayHttpJsonController.class, GatewayAgentCatalogController.class })
public final class GatewayHttpErrorHandler {

    private static final MediaType A2A_JSON = MediaType.parseMediaType("application/a2a+json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Handles all failures emitted by the data-plane controller. */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<byte[]> handle(Throwable error) {
        Throwable cause = unwrap(error);
        Mapping mapping = map(cause);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", mapping.code());
        detail.put("message", safeMessage(cause));
        Map<String, Object> body = Map.of("error", detail);
        try {
            return ResponseEntity.status(mapping.status()).contentType(A2A_JSON)
                    .body(objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        }
        catch (JsonProcessingException ex) {
            return ResponseEntity.status(mapping.status()).contentType(A2A_JSON)
                    .body("{\"error\":{\"code\":\"INTERNAL\",\"message\":\"gateway error\"}}"
                            .getBytes(StandardCharsets.UTF_8));
        }
    }

    private Mapping map(Throwable error) {
        if (error instanceof GatewayHttpException http) {
            return new Mapping(http.status(), http.code());
        }
        if (error instanceof RouteResolutionException route) {
            return switch (route.code()) {
                case TASK_ROUTE_NOT_FOUND, AGENT_NOT_FOUND -> new Mapping(404, "GATEWAY_ROUTE_NOT_FOUND");
                case ROUTE_CONFLICT -> new Mapping(409, "GATEWAY_ROUTE_CONFLICT");
                case AGENT_UNAVAILABLE, DEADLINE_EXCEEDED -> new Mapping(503, "GATEWAY_AGENT_UNAVAILABLE");
                case AUTHORIZATION_DENIED -> new Mapping(403, "GATEWAY_POLICY_DENIED");
            };
        }
        if (error instanceof GatewayForwardingException forwarding) {
            return switch (forwarding.code()) {
                case INVALID_REQUEST -> new Mapping(400, "GATEWAY_INVALID_REQUEST");
                case INTERFACE_UNAVAILABLE, CREDENTIALS_UNAVAILABLE, TRANSPORT ->
                    new Mapping(503, "GATEWAY_AGENT_UNAVAILABLE");
                case UPSTREAM_PROTOCOL -> new Mapping(502, "GATEWAY_UPSTREAM_PROTOCOL_ERROR");
                case DUPLICATE_IN_FLIGHT -> new Mapping(409, "GATEWAY_DUPLICATE_IN_FLIGHT");
                case OUTCOME_UNKNOWN -> new Mapping(503, "GATEWAY_OUTCOME_UNKNOWN");
                case RATE_LIMITED -> new Mapping(429, "GATEWAY_RATE_LIMITED");
            };
        }
        if (error instanceof AuthenticationException) {
            return new Mapping(401, "UNAUTHENTICATED");
        }
        if (error instanceof AccessDeniedException) {
            return new Mapping(403, "GATEWAY_POLICY_DENIED");
        }
        if (error instanceof IllegalArgumentException) {
            return new Mapping(400, "INVALID_ARGUMENT");
        }
        return new Mapping(500, "INTERNAL");
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "gateway request failed" : message;
    }

    private record Mapping(int status, String code) {
    }

}
