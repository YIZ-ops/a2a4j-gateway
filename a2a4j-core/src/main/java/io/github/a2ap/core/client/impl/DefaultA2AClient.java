/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.a2ap.core.client.impl;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.a2ap.core.client.A2AClient;
import io.github.a2ap.core.client.CardResolver;
import io.github.a2ap.core.protocol.v1.A2AProtocolV1;
import io.github.a2ap.core.util.JsonUtil;
import io.github.a2ap.core.util.SdkModelCodec;
import io.netty.buffer.Unpooled;
import io.netty.util.internal.StringUtil;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.a2aproject.sdk.spec.Event;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.JSONParseError;
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TaskNotFoundError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/** Reactor Netty A2A client using official SDK standard domain records. */
public class DefaultA2AClient implements A2AClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultA2AClient.class);

    private AgentCard agentCard;
    private final CardResolver cardResolver;
    private final HttpClient client;

    public DefaultA2AClient(CardResolver cardResolver) {
        this.cardResolver = Objects.requireNonNull(cardResolver, "cardResolver");
        this.agentCard = cardResolver.resolveCard();
        this.client = HttpClient.create();
    }

    public DefaultA2AClient(AgentCard agentCard) {
        this.agentCard = Objects.requireNonNull(agentCard, "agentCard");
        this.cardResolver = null;
        this.client = HttpClient.create();
    }

    public DefaultA2AClient(AgentCard agentCard, CardResolver cardResolver) {
        this.agentCard = Objects.requireNonNull(agentCard, "agentCard");
        this.cardResolver = cardResolver;
        this.client = HttpClient.create();
    }

    @Override
    public AgentCard agentCard() {
        return agentCard == null ? retrieveAgentCard() : agentCard;
    }

    @Override
    public AgentCard retrieveAgentCard() {
        if (cardResolver != null) {
            agentCard = cardResolver.resolveCard();
        }
        return agentCard;
    }

    @Override
    public Event sendMessage(MessageSendParams params) throws A2AError {
        return send("SendMessage", wireParams(params), Event.class);
    }

    @Override
    public Flux<StreamingEventKind> sendMessageStream(MessageSendParams params) {
        return stream("SendStreamingMessage", wireParams(params));
    }

    @Override
    public Task getTask(TaskQueryParams params) {
        return send("GetTask", SdkModelCodec.toMap(params), Task.class);
    }

    @Override
    public Task cancelTask(TaskIdParams params) {
        return send("CancelTask", SdkModelCodec.toMap(params), Task.class);
    }

    @Override
    public TaskPushNotificationConfig setTaskPushNotification(TaskPushNotificationConfig params) {
        return send("CreateTaskPushNotificationConfig", SdkModelCodec.toMap(params),
                TaskPushNotificationConfig.class);
    }

    @Override
    public TaskPushNotificationConfig getTaskPushNotification(TaskIdParams params) {
        return send("GetTaskPushNotificationConfig", SdkModelCodec.toMap(params),
                TaskPushNotificationConfig.class);
    }

    @Override
    public Flux<StreamingEventKind> resubscribeTask(TaskQueryParams params) {
        return stream("SubscribeToTask", Map.of("id", params.id()));
    }

    @Override
    public Boolean supports(String capability) {
        AgentCard card = agentCard();
        if (card == null || card.capabilities() == null || capability == null) {
            return false;
        }
        return switch (capability.toLowerCase()) {
            case "streaming" -> card.capabilities().streaming();
            case "pushnotifications" -> card.capabilities().pushNotifications();
            case "extendedagentcard" -> card.capabilities().extendedAgentCard();
            default -> false;
        };
    }

    private <T> T send(String method, Map<String, Object> params, Class<T> resultType) {
        try {
            List<String> chunks = exchange(method, params, false).collectList().block();
            String body = chunks == null ? null : String.join("", chunks);
            if (body == null || body.isBlank()) {
                throw new InternalError("A2A response was empty");
            }
            JsonNode response = JsonUtil.fromJson(body);
            if (response == null || !response.isObject()) {
                throw new InternalError("A2A response was not valid JSON");
            }
            checkError(response);
            JsonNode result = response.get("result");
            if (result == null || result.isNull()) {
                return null;
            }
            if (resultType == Event.class) {
                return resultType.cast(event(result));
            }
            if (resultType == Task.class && result.has("task")) {
                result = result.get("task");
            }
            return SdkModelCodec.fromJson(result.toString(), resultType);
        }
        catch (A2AError ex) {
            throw ex;
        }
        catch (Exception ex) {
            log.error("A2A {} failed for {}", method, cardName(), ex);
            throw internalError("A2A " + method + " failed", ex);
        }
    }

    private Flux<StreamingEventKind> stream(String method, Map<String, Object> params) {
        return Flux.defer(() -> {
            SseFrameDecoder decoder = new SseFrameDecoder();
            return exchange(method, params, true)
                    .concatMapIterable(decoder::accept)
                    .concatWith(Flux.defer(() -> Flux.fromIterable(decoder.finish())));
        })
                .doOnError(ex -> log.error("A2A streaming {} failed", method, ex));
    }

    private Flux<String> exchange(String method, Map<String, Object> params, boolean streaming) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        request.put("params", params);
        request.put("id", UUID.randomUUID().toString());
        return client.headers(headers -> {
            headers.add("Content-Type", "application/json");
            headers.add("Accept", streaming ? "text/event-stream" : "application/json");
            headers.add(A2AProtocolV1.VERSION_HEADER, A2AProtocolV1.VERSION);
        }).post().uri(endpoint()).send(Mono.just(Unpooled.wrappedBuffer(
                JsonUtil.toJson(request).getBytes(StandardCharsets.UTF_8))))
                .responseContent().asString();
    }

    private Map<String, Object> wireParams(MessageSendParams params) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("message", SdkModelCodec.messageMap(params.message()));
        if (params.configuration() != null) {
            wire.put("configuration", SdkModelCodec.toMap(params.configuration()));
        }
        if (params.metadata() != null) {
            wire.put("metadata", params.metadata());
        }
        if (params.tenant() != null) {
            wire.put("tenant", params.tenant());
        }
        return wire;
    }

    private StreamingEventKind parseServerSentEvent(String eventData) {
        if (StringUtil.isNullOrEmpty(eventData)) {
            return null;
        }
        String json = extractJsonFromSse(eventData);
        if (json == null) {
            return null;
        }
        JsonNode response = JsonUtil.fromJson(json);
        checkError(response);
        if (response == null || response.get("result") == null || response.get("result").isNull()) {
            return null;
        }
        return streamingEvent(response.get("result"));
    }

    private StreamingEventKind streamingEvent(JsonNode node) {
        if (node.has("task")) {
            return SdkModelCodec.fromJson(node.get("task").toString(), Task.class);
        }
        if (node.has("message")) {
            return SdkModelCodec.fromJson(node.get("message").toString(), Message.class);
        }
        if (node.has("artifactUpdate")) {
            return SdkModelCodec.fromJson(node.get("artifactUpdate").toString(), TaskArtifactUpdateEvent.class);
        }
        if (node.has("statusUpdate")) {
            return SdkModelCodec.fromJson(node.get("statusUpdate").toString(), TaskStatusUpdateEvent.class);
        }
        if (node.has("id") && node.has("status")) {
            return SdkModelCodec.fromJson(node.toString(), Task.class);
        }
        throw new IllegalArgumentException("unsupported A2A streaming result");
    }

    private Event event(JsonNode result) {
        return streamingEvent(result);
    }

    private InternalError internalError(String message, Throwable cause) {
        InternalError error = new InternalError(message);
        if (cause != null) {
            error.initCause(cause);
        }
        return error;
    }

    private final class SseFrameDecoder {

        private final StringBuilder pending = new StringBuilder();

        private List<StreamingEventKind> accept(String chunk) {
            if (StringUtil.isNullOrEmpty(chunk)) {
                return List.of();
            }
            pending.append(chunk);
            List<StreamingEventKind> events = new ArrayList<>();
            int boundary;
            while ((boundary = frameBoundary()) >= 0) {
                String frame = pending.substring(0, boundary);
                int boundaryLength = pending.charAt(boundary) == '\r' ? 4 : 2;
                pending.delete(0, boundary + boundaryLength);
                StreamingEventKind event = parseServerSentEvent(frame);
                if (event != null) {
                    events.add(event);
                }
            }
            return events;
        }

        private List<StreamingEventKind> finish() {
            if (pending.isEmpty()) {
                return List.of();
            }
            String frame = pending.toString();
            pending.setLength(0);
            StreamingEventKind event = parseServerSentEvent(frame);
            return event == null ? List.of() : List.of(event);
        }

        private int frameBoundary() {
            int lfBoundary = pending.indexOf("\n\n");
            int crlfBoundary = pending.indexOf("\r\n\r\n");
            if (lfBoundary < 0) {
                return crlfBoundary;
            }
            if (crlfBoundary < 0) {
                return lfBoundary;
            }
            return Math.min(lfBoundary, crlfBoundary);
        }

    }

    private static void checkError(JsonNode response) throws A2AError {
        if (response == null || !response.has("error") || response.get("error").isNull()) {
            return;
        }
        JsonNode error = response.get("error");
        int code = error.path("code").asInt(A2AErrorCodes.INTERNAL.code());
        String message = error.path("message").asText("A2A request failed");
        Map<String, Object> details = details(error.get("data"));
        throw sdkError(code, message, details);
    }

    private static A2AError sdkError(int code, String message, Map<String, Object> details) {
        A2AErrorCodes known = A2AErrorCodes.fromCode(code);
        if (known == null) {
            return new A2AError(code, message, details);
        }
        return switch (known) {
            case INVALID_PARAMS -> new InvalidParamsError(code, message, details);
            case METHOD_NOT_FOUND -> new MethodNotFoundError(code, message, details);
            case INVALID_REQUEST -> new InvalidRequestError(code, message, details);
            case JSON_PARSE -> new JSONParseError(code, message, details);
            case INTERNAL -> new InternalError(code, message, details);
            case TASK_NOT_FOUND -> new TaskNotFoundError(message, details);
            default -> new A2AError(code, message, details);
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(JsonNode data) {
        if (data == null || data.isNull()) {
            return Map.of();
        }
        if (data.isObject()) {
            Map<String, Object> result = JsonUtil.fromJson(data.toString(), Map.class);
            return result == null ? Map.of() : result;
        }
        return Map.of("data", data.asText());
    }

    private String endpoint() {
        if (agentCard == null || agentCard.url() == null) {
            throw new IllegalStateException("AgentCard has no url");
        }
        return agentCard.url();
    }

    private String cardName() {
        return agentCard == null ? "unknown" : agentCard.name();
    }

    private String extractJsonFromSse(String sseData) {
        StringBuilder json = new StringBuilder();
        for (String line : sseData.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("data:")) {
                json.append(trimmed.substring(5).trim());
            }
        }
        return json.isEmpty() ? null : json.toString();
    }

}
