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

package io.github.a2ap.core.protocol.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;

/**
 * Lightweight structural validation for the A2A 1.0 JSON fixtures and boundary adapters.
 *
 * <p>This is deliberately not a replacement for generated Proto models. It prevents the
 * Gateway boundary from silently accepting the legacy 0.2.x Agent Card and JSON-RPC
 * shapes until the generated model module is introduced.</p>
 */
public final class A2AProtocolV1Validator {

    private static final Set<String> CARD_BINDINGS = Set.of(
            A2AProtocolV1.JSON_RPC_BINDING,
            A2AProtocolV1.HTTP_JSON_BINDING,
            A2AProtocolV1.GRPC_BINDING);

    private A2AProtocolV1Validator() {
    }

    /**
     * Validates the required public Agent Card structure for A2A 1.0.
     *
     * @param card Agent Card JSON tree
     * @throws IllegalArgumentException when the card is not an A2A 1.0 card
     */
    public static void validateAgentCard(JsonNode card) {
        requireObject(card, "Agent Card");
        requireText(card, "name");
        requireText(card, "description");
        requireText(card, "version");
        requireArray(card, "supportedInterfaces", true);
        requireObject(card, "capabilities");
        requireArray(card, "defaultInputModes", true);
        requireArray(card, "defaultOutputModes", true);
        requireArray(card, "skills", true);

        for (JsonNode agentInterface : card.get("supportedInterfaces")) {
            requireObject(agentInterface, "supportedInterfaces entry");
            requireText(agentInterface, "url");
            requireText(agentInterface, "protocolBinding");
            requireText(agentInterface, "protocolVersion");
            String binding = agentInterface.get("protocolBinding").asText();
            if (!CARD_BINDINGS.contains(binding)) {
                throw invalid("unsupported protocolBinding: " + binding);
            }
            if (!A2AProtocolV1.VERSION.equals(agentInterface.get("protocolVersion").asText())) {
                throw invalid("unsupported protocolVersion: " + agentInterface.get("protocolVersion").asText());
            }
        }

        for (JsonNode skill : card.get("skills")) {
            requireObject(skill, "skills entry");
            requireText(skill, "id");
            requireText(skill, "name");
            requireText(skill, "description");
            requireArray(skill, "tags", true);
        }
    }

    /**
     * Validates the common JSON-RPC envelope and A2A 1.0 method name.
     *
     * @param request JSON-RPC request tree
     * @throws IllegalArgumentException when the request is not supported by A2A 1.0
     */
    public static void validateJsonRpcRequest(JsonNode request) {
        requireObject(request, "JSON-RPC request");
        requireText(request, "jsonrpc");
        if (!A2AProtocolV1.JSON_RPC_VERSION.equals(request.get("jsonrpc").asText())) {
            throw invalid("unsupported jsonrpc version: " + request.get("jsonrpc").asText());
        }
        requireText(request, "method");
        String method = request.get("method").asText();
        if (!A2AProtocolV1.JSON_RPC_METHODS.contains(method)) {
            throw invalid("unsupported A2A 1.0 method: " + method);
        }
        if (!request.has("id") || request.get("id").isNull()) {
            throw invalid("JSON-RPC request id is required");
        }
        if (request.has("params") && !request.get("params").isObject()) {
            throw invalid("JSON-RPC params must be an object");
        }
        JsonNode params = request.has("params") ? request.get("params") : null;
        if (params == null) {
            params = new ObjectMapper().createObjectNode();
        }
        switch (method) {
            case "SendMessage", "SendStreamingMessage" -> requireObjectField(params, "message");
            case "GetTask", "CancelTask", "SubscribeToTask" -> requireTextField(params, "id");
            case "CreateTaskPushNotificationConfig", "ListTaskPushNotificationConfigs" ->
                    requireTextField(params, "taskId");
            case "GetTaskPushNotificationConfig", "DeleteTaskPushNotificationConfig" -> {
                requireTextField(params, "taskId");
                requireTextField(params, "id");
            }
            default -> {
                // ListTasks and GetExtendedAgentCard have no additional required parameter.
            }
        }
    }

    /**
     * Parses a JSON document using the supplied mapper and validates it as a Card.
     *
     * @param json JSON document
     * @param objectMapper mapper used by the caller's transport
     * @return parsed card tree
     */
    public static JsonNode parseAndValidateAgentCard(String json, ObjectMapper objectMapper) {
        try {
            JsonNode card = objectMapper.readTree(json);
            validateAgentCard(card);
            return card;
        }
        catch (Exception ex) {
            if (ex instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw invalid("invalid Agent Card JSON: " + ex.getMessage());
        }
    }

    private static void requireObject(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            throw invalid(name + " must be an object");
        }
    }

    private static void requireText(JsonNode node, String field) {
        if (!node.has(field) || !node.get(field).isTextual() || node.get(field).asText().isBlank()) {
            throw invalid(field + " must be a non-empty string");
        }
    }

    private static void requireArray(JsonNode node, String field, boolean nonEmpty) {
        if (!node.has(field) || !node.get(field).isArray()
                || (nonEmpty && node.get(field).isEmpty())) {
            throw invalid(field + " must be a non-empty array");
        }
    }

    private static void requireObjectField(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).isObject()) {
            throw invalid(field + " must be an object");
        }
    }

    private static void requireTextField(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).isTextual()
                || node.get(field).asText().isBlank()) {
            throw invalid(field + " must be a non-empty string");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid A2A 1.0 document: " + message);
    }

}
