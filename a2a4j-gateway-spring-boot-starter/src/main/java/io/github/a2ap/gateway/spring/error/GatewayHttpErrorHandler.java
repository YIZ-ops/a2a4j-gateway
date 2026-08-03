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

package io.github.a2ap.gateway.spring.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.a2ap.gateway.core.exception.GatewayForwardingException;
import io.github.a2ap.gateway.core.exception.GatewayUpstreamException;
import io.github.a2ap.gateway.core.exception.RouteResolutionException;
import io.github.a2ap.gateway.core.exception.VersionNotSupportedException;
import io.github.a2ap.gateway.spring.exception.GatewayHttpException;
import io.github.a2ap.gateway.spring.controller.GatewayAgentCatalogController;
import io.github.a2ap.gateway.spring.controller.GatewayHttpJsonController;
import java.nio.charset.StandardCharsets;
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
        Map<String, Object> body = A2aErrorPayload.httpBody(
                new A2aErrorPayload.Mapping(mapping.status(), mapping.gatewayCode(), mapping.reason()),
                safeMessage(cause));
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
            return mapping(http.status(), http.code());
        }
        if (error instanceof RouteResolutionException route) {
            return switch (route.code()) {
                case TASK_ROUTE_NOT_FOUND -> mapping(404, "TASK_NOT_FOUND");
                case AGENT_NOT_FOUND -> mapping(404, "GATEWAY_ROUTE_NOT_FOUND");
                case ROUTE_CONFLICT -> mapping(409, "GATEWAY_ROUTE_CONFLICT");
                case AGENT_UNAVAILABLE, DEADLINE_EXCEEDED -> mapping(503, "GATEWAY_AGENT_UNAVAILABLE");
                case AUTHORIZATION_DENIED -> mapping(403, "GATEWAY_POLICY_DENIED");
            };
        }
        if (error instanceof VersionNotSupportedException) {
            return mapping(400, "VERSION_NOT_SUPPORTED");
        }
        if (error instanceof GatewayUpstreamException upstream) {
            return mapping(upstream.httpStatus(), upstream.reason() == null ? "UPSTREAM_ERROR" : upstream.reason());
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
                case PUSH_NOTIFICATION_NOT_SUPPORTED -> mapping(400, "PUSH_NOTIFICATION_NOT_SUPPORTED");
                case UNSUPPORTED_OPERATION -> mapping(400, "UNSUPPORTED_OPERATION");
                case EXTENDED_AGENT_CARD_NOT_CONFIGURED -> mapping(400, "EXTENDED_AGENT_CARD_NOT_CONFIGURED");
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

    private Mapping mapping(int status, String code) {
        return new Mapping(status, code, A2aErrorPayload.reasonFor(code));
    }

    private record Mapping(int status, String gatewayCode, String reason) {
    }

}
