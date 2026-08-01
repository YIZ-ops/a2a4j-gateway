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

package io.github.a2ap.gateway.api.model;

import java.util.Map;
import java.util.Set;
import java.util.Objects;

/** Protocol-neutral command passed through gateway routing and forwarding. */
public record GatewayCommand(Operation operation, String tenantId, PrincipalContext principal,
        TargetHint targetHint, String gatewayTaskId, String gatewayContextId, Map<String, Object> message,
        Map<String, Object> configuration, Map<String, Object> metadata, String idempotencyKey,
        ProtocolDescriptor inboundProtocol, String requestedProtocolVersion, Set<String> extensions) {

    /** Operations normalized from A2A 1.0 bindings. */
    public enum Operation {
        /** Create or continue a task with a non-streaming response. */
        SEND_MESSAGE,
        /** Create or continue a task with streaming events. */
        SEND_STREAMING_MESSAGE,
        /** Retrieve one task. */
        GET_TASK,
        /** List tasks visible to the principal. */
        LIST_TASKS,
        /** Cancel one task. */
        CANCEL_TASK,
        /** Subscribe to one task's events. */
        SUBSCRIBE_TO_TASK,
        /** Create push notification configuration. */
        CREATE_TASK_PUSH_NOTIFICATION_CONFIG,
        /** Retrieve push notification configuration. */
        GET_TASK_PUSH_NOTIFICATION_CONFIG,
        /** List push notification configurations. */
        LIST_TASK_PUSH_NOTIFICATION_CONFIGS,
        /** Delete push notification configuration. */
        DELETE_TASK_PUSH_NOTIFICATION_CONFIG,
        /** Retrieve the authenticated extended Agent Card. */
        GET_EXTENDED_AGENT_CARD
    }

    /** Creates a validated immutable command. */
    public GatewayCommand {
        operation = Objects.requireNonNull(operation, "operation");
        requireText(tenantId, "tenantId");
        principal = Objects.requireNonNull(principal, "principal");
        targetHint = targetHint == null ? TargetHint.empty() : targetHint;
        message = message == null ? Map.of() : Map.copyOf(message);
        configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        inboundProtocol = Objects.requireNonNull(inboundProtocol, "inboundProtocol");
        requestedProtocolVersion = requestedProtocolVersion == null
                ? inboundProtocol.protocolVersion() : requestedProtocolVersion;
        extensions = extensions == null ? Set.of() : Set.copyOf(extensions);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
