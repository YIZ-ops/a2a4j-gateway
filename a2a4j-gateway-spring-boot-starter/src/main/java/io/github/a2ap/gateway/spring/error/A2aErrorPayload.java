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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the A2A 1.0 ProtoJSON error details shared by both HTTP bindings. */
final class A2aErrorPayload {

    private static final String ERROR_INFO_TYPE = "type.googleapis.com/google.rpc.ErrorInfo";

    private static final String ERROR_DOMAIN = "a2a-protocol.org";

    private A2aErrorPayload() {
    }

    static Map<String, Object> httpBody(Mapping mapping, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", mapping.status());
        error.put("status", statusName(mapping.status()));
        error.put("message", message);
        error.put("details", List.of(errorInfo(mapping)));
        return Map.of("error", error);
    }

    static Map<String, Object> errorInfo(Mapping mapping) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("@type", ERROR_INFO_TYPE);
        info.put("reason", mapping.reason());
        info.put("domain", ERROR_DOMAIN);
        if (mapping.gatewayCode() != null && !mapping.gatewayCode().isBlank()) {
            info.put("metadata", Map.of("gatewayCode", mapping.gatewayCode()));
        }
        return info;
    }

    static String reasonFor(String gatewayCode) {
        if (gatewayCode == null || gatewayCode.isBlank()) {
            return "INTERNAL";
        }
        return switch (gatewayCode) {
            case "GATEWAY_UPSTREAM_PROTOCOL_ERROR" -> "INVALID_AGENT_RESPONSE";
            case "GATEWAY_POLICY_DENIED" -> "PERMISSION_DENIED";
            case "GATEWAY_AGENT_UNAVAILABLE" -> "AGENT_UNAVAILABLE";
            case "GATEWAY_ROUTE_NOT_FOUND" -> "TASK_NOT_FOUND";
            case "TASK_NOT_FOUND" -> "TASK_NOT_FOUND";
            case "PUSH_NOTIFICATION_NOT_SUPPORTED" -> "PUSH_NOTIFICATION_NOT_SUPPORTED";
            case "UNSUPPORTED_OPERATION" -> "UNSUPPORTED_OPERATION";
            case "EXTENDED_AGENT_CARD_NOT_CONFIGURED" -> "EXTENDED_AGENT_CARD_NOT_CONFIGURED";
            case "UNAUTHENTICATED" -> "UNAUTHENTICATED";
            default -> gatewayCode;
        };
    }

    static String statusName(int status) {
        return switch (status) {
            case 400 -> "INVALID_ARGUMENT";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "PERMISSION_DENIED";
            case 404 -> "NOT_FOUND";
            case 409 -> "ABORTED";
            case 413 -> "RESOURCE_EXHAUSTED";
            case 429 -> "RESOURCE_EXHAUSTED";
            case 500 -> "INTERNAL";
            case 501 -> "NOT_IMPLEMENTED";
            case 502 -> "BAD_GATEWAY";
            case 503 -> "UNAVAILABLE";
            case 504 -> "DEADLINE_EXCEEDED";
            default -> "UNKNOWN";
        };
    }

    record Mapping(int status, String gatewayCode, String reason) {
    }

}
