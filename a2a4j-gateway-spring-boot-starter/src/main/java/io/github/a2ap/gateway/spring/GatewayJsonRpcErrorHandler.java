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

/** Maps JSON-RPC data-plane failures to JSON-RPC errors with a stable Gateway code in data. */
@RestControllerAdvice(assignableTypes = GatewayJsonRpcController.class)
public final class GatewayJsonRpcErrorHandler {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Handles failures before an SSE response is committed. */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<byte[]> handle(Throwable error) {
        Throwable cause = unwrap(error);
        Mapping mapping = map(cause);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", mapping.rpcCode());
        detail.put("message", safeMessage(cause));
        detail.put("data", Map.of("gatewayCode", mapping.gatewayCode()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", null);
        body.put("error", detail);
        try {
            return ResponseEntity.status(mapping.status()).contentType(JSON)
                    .body(objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        }
        catch (JsonProcessingException ex) {
            return ResponseEntity.status(mapping.status()).contentType(JSON)
                    .body(("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{"
                            + "\"code\":-32603,\"message\":\"gateway error\"}}").getBytes(StandardCharsets.UTF_8));
        }
    }

    private Mapping map(Throwable error) {
        if (error instanceof GatewayHttpException http) {
            return mapping(http.status(), http.code());
        }
        if (error instanceof RouteResolutionException route) {
            return switch (route.code()) {
                case TASK_ROUTE_NOT_FOUND, AGENT_NOT_FOUND -> mapping(404, "GATEWAY_ROUTE_NOT_FOUND");
                case ROUTE_CONFLICT -> mapping(409, "GATEWAY_ROUTE_CONFLICT");
                case AGENT_UNAVAILABLE, DEADLINE_EXCEEDED -> mapping(503, "GATEWAY_AGENT_UNAVAILABLE");
                case AUTHORIZATION_DENIED -> mapping(403, "GATEWAY_POLICY_DENIED");
            };
        }
        if (error instanceof GatewayForwardingException forwarding) {
            return switch (forwarding.code()) {
                case INVALID_REQUEST -> mapping(400, "GATEWAY_INVALID_REQUEST");
                case INTERFACE_UNAVAILABLE, CREDENTIALS_UNAVAILABLE, TRANSPORT ->
                    mapping(503, "GATEWAY_AGENT_UNAVAILABLE");
                case UPSTREAM_PROTOCOL -> mapping(502, "GATEWAY_UPSTREAM_PROTOCOL_ERROR");
                case DUPLICATE_IN_FLIGHT -> mapping(409, "GATEWAY_DUPLICATE_IN_FLIGHT");
                case OUTCOME_UNKNOWN -> mapping(503, "GATEWAY_OUTCOME_UNKNOWN");
                case RATE_LIMITED -> mapping(429, "GATEWAY_RATE_LIMITED");
            };
        }
        if (error instanceof AuthenticationException) {
            return mapping(401, "UNAUTHENTICATED");
        }
        if (error instanceof AccessDeniedException) {
            return mapping(403, "GATEWAY_POLICY_DENIED");
        }
        if (error instanceof IllegalArgumentException) {
            return mapping(400, "INVALID_ARGUMENT");
        }
        return mapping(500, "INTERNAL");
    }

    private Mapping mapping(int status, String gatewayCode) {
        int rpcCode = switch (gatewayCode) {
            case "GATEWAY_ROUTE_NOT_FOUND" -> -32080;
            case "GATEWAY_ROUTE_CONFLICT" -> -32081;
            case "GATEWAY_POLICY_DENIED" -> -32083;
            case "GATEWAY_AGENT_UNAVAILABLE" -> -32084;
            case "GATEWAY_UPSTREAM_PROTOCOL_ERROR" -> -32006;
            case "GATEWAY_RATE_LIMITED" -> -32085;
            case "UNAUTHENTICATED" -> -32001;
            case "INVALID_ARGUMENT", "GATEWAY_INVALID_REQUEST" -> -32602;
            default -> -32603;
        };
        return new Mapping(status, rpcCode, gatewayCode);
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

    private record Mapping(int status, int rpcCode, String gatewayCode) {
    }

}
