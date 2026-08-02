/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.a2ap.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.a2ap.core.server.impl.DefaultDispatcher;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Event;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class DefaultDispatcherErrorTest {

    private final Dispatcher dispatcher = new DefaultDispatcher(new StubServer(), new ObjectMapper());

    @Test
    void mapsMethodNotFoundThroughOfficialSdkErrorShape() {
        Map<String, Object> response = dispatcher.dispatch(Map.of(
                "jsonrpc", "2.0", "id", "request-1", "method", "UnknownMethod"));

        Map<?, ?> error = error(response);
        assertEquals(-32601, error.get("code"));
        assertEquals("Method not found", error.get("message"));
    }

    @Test
    void mapsMalformedParamsThroughOfficialSdkErrorShape() {
        Map<String, Object> response = dispatcher.dispatch(Map.of(
                "jsonrpc", "2.0", "id", "request-2", "method", "GetTask", "params", Map.of()));

        Map<?, ?> error = error(response);
        assertEquals(-32602, error.get("code"));
        assertEquals("Invalid parameters", error.get("message"));
    }

    @Test
    void mapsAsynchronousStreamErrorsToAJsonRpcErrorEnvelope() {
        Dispatcher streamingDispatcher = new DefaultDispatcher(
                new StubServer(Flux.error(new InternalError("upstream failed"))), new ObjectMapper());

        List<Map<String, Object>> responses = streamingDispatcher.dispatchStream(Map.of(
                "jsonrpc", "2.0", "id", "stream-1", "method", "SendStreamingMessage",
                "params", Map.of("message", Map.of("messageId", "message-1", "role", "ROLE_USER",
                        "parts", List.of(Map.of("text", "hello")))))).collectList().block();

        assertNotNull(responses);
        assertEquals(1, responses.size());
        Map<?, ?> error = error(responses.get(0));
        assertEquals(-32603, error.get("code"));
        assertEquals("upstream failed", error.get("message"));
    }

    private Map<?, ?> error(Map<String, Object> response) {
        assertNotNull(response.get("error"));
        return (Map<?, ?>) response.get("error");
    }

    private static final class StubServer implements A2AServer {

        private final Flux<StreamingEventKind> streamingEvents;

        private StubServer() {
            this(Flux.empty());
        }

        private StubServer(Flux<StreamingEventKind> streamingEvents) {
            this.streamingEvents = streamingEvents;
        }

        @Override
        public Event handleMessage(MessageSendParams params) {
            return null;
        }

        @Override
        public Flux<StreamingEventKind> handleMessageStream(MessageSendParams params) {
            return streamingEvents;
        }

        @Override
        public Task getTask(String taskId) {
            return null;
        }

        @Override
        public Task cancelTask(String taskId) {
            return null;
        }

        @Override
        public TaskPushNotificationConfig setTaskPushNotification(TaskPushNotificationConfig config) {
            return null;
        }

        @Override
        public TaskPushNotificationConfig getTaskPushNotification(String taskId) {
            return null;
        }

        @Override
        public AgentCard getSelfAgentCard() {
            return null;
        }

        @Override
        public AgentCard getAuthenticatedExtendedCard() {
            return null;
        }

        @Override
        public Flux<StreamingEventKind> subscribeToTaskUpdates(String taskId) {
            return Flux.empty();
        }

    }

}
