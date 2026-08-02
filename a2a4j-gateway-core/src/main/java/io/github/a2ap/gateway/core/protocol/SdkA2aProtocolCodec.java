/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.a2ap.gateway.core.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendConfiguration;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;

/**
 * Permanent protocol-boundary codec between Gateway payload maps and official SDK models.
 *
 * <p>This is not a compatibility mapper: Gateway routing, authorization, storage, and
 * transport SPI contracts remain map-based, while A2A standard payloads are validated and
 * decoded only through the official SDK codec at this boundary.</p>
 */
final class SdkA2aProtocolCodec {

    private SdkA2aProtocolCodec() {
    }

    static AgentCard agentCard(String json) {
        return fromJson(json, AgentCard.class);
    }

    @SuppressWarnings("unchecked")
    static Message message(Map<String, Object> value) {
        return fromJson(toJson(value), Message.class);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> messageMap(Map<String, Object> value) {
        Map<String, Object> encoded = toMap(message(value));
        Object wrapped = encoded.get("message");
        return wrapped instanceof Map<?, ?> ? (Map<String, Object>) wrapped : encoded;
    }

    static MessageSendConfiguration configuration(Map<String, Object> value) {
        return fromJson(toJson(value), MessageSendConfiguration.class);
    }

    static MessageSendParams messageSendParams(Map<String, Object> value) {
        return fromJson(toJson(value), MessageSendParams.class);
    }

    static Task task(String json) {
        return fromJson(json, Task.class);
    }

    static Artifact artifact(String json) {
        return fromJson(json, Artifact.class);
    }

    static TaskStatusUpdateEvent statusUpdate(String json) {
        return fromJson(json, TaskStatusUpdateEvent.class);
    }

    static TaskArtifactUpdateEvent artifactUpdate(String json) {
        return fromJson(json, TaskArtifactUpdateEvent.class);
    }

    static StreamingEventKind streamingEvent(String json) {
        return fromJson(json, StreamingEventKind.class);
    }

    static TaskQueryParams taskQueryParams(Map<String, Object> value) {
        return fromJson(toJson(value), TaskQueryParams.class);
    }

    static TaskIdParams taskIdParams(Map<String, Object> value) {
        return fromJson(toJson(value), TaskIdParams.class);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toMap(Object value) {
        return fromJson(toJson(value), Map.class);
    }

    static void validateResponse(JsonNode body, ObjectMapper objectMapper) {
        JsonNode result = body.get("result");
        if (result == null || result.isNull()) {
            return;
        }
        if (result.has("task")) {
            task(result.get("task").toString());
        }
        else if (result.has("message")) {
            message(objectMapper.convertValue(result.get("message"), Map.class));
        }
        else if (result.has("statusUpdate")) {
            statusUpdate(result.get("statusUpdate").toString());
        }
        else if (result.has("artifactUpdate")) {
            artifactUpdate(result.get("artifactUpdate").toString());
        }
        else if (result.has("id") && result.has("contextId") && result.has("status")) {
            task(result.toString());
        }
    }

    static String toJson(Object value) {
        try {
            return JsonUtil.toJson(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("could not encode A2A SDK model", ex);
        }
    }

    private static <T> T fromJson(String json, Class<T> type) {
        try {
            return JsonUtil.fromJson(json, type);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("could not decode A2A SDK " + type.getSimpleName(), ex);
        }
    }

}
