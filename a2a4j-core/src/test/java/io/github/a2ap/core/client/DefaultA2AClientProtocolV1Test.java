/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package io.github.a2ap.core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.a2ap.core.client.impl.DefaultA2AClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Verifies that the client emits the A2A 1.0 JSON-RPC wire names and version header. */
class DefaultA2AClientProtocolV1Test {

    @Test
    void sendsA2A10MethodAndVersionHeader() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> version = new AtomicReference<>();
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            version.set(request.requestHeaders().get("A2A-Version"));
            return request.receive().aggregate().asString().flatMap(body -> {
                requestBody.set(body);
                return response.header("Content-Type", "application/json")
                        .sendString(Mono.just("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{"
                                + "\"task\":{\"id\":\"task-1\",\"contextId\":\"ctx-1\","
                                + "\"status\":{\"state\":\"TASK_STATE_WORKING\"}}}}"))
                        .then();
            });
        }).bindNow();
        try {
            DefaultA2AClient client = new DefaultA2AClient(card(server.port()));
            Task task = (Task) client.sendMessage(params());

            assertEquals("1.0", version.get());
            assertTrue(requestBody.get().contains("\"method\":\"SendMessage\""), requestBody.get());
            assertEquals("task-1", task.id());
        }
        finally {
            server.disposeNow(Duration.ofSeconds(5));
        }
    }

    @Test
    void parsesMultipleSseFramesWithoutReplayingEvents() throws Exception {
        String working = sseTask("task-working", "TASK_STATE_WORKING");
        String completed = sseTask("task-completed", "TASK_STATE_COMPLETED");
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) ->
                response.header("Content-Type", "text/event-stream")
                        .sendString(Flux.just(working.substring(0, 5), working.substring(5),
                                completed.substring(0, 7), completed.substring(7))).then()).bindNow();
        try {
            DefaultA2AClient client = new DefaultA2AClient(card(server.port()));
            List<StreamingEventKind> events = client.sendMessageStream(params()).collectList().block();

            assertEquals(2, events.size());
            assertEquals("task-working", ((Task) events.get(0)).id());
            assertEquals("task-completed", ((Task) events.get(1)).id());
        }
        finally {
            server.disposeNow(Duration.ofSeconds(5));
        }
    }

    @Test
    void convertsNonStreamingFailuresToSdkInternalError() throws Exception {
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) ->
                response.header("Content-Type", "application/json").sendString(Mono.just("not-json")).then())
                .bindNow();
        try {
            DefaultA2AClient client = new DefaultA2AClient(card(server.port()));
            A2AError error = assertThrows(A2AError.class, () -> client.sendMessage(params()));

            assertTrue(error instanceof InternalError);
            assertEquals(-32603, error.getCode());
        }
        finally {
            server.disposeNow(Duration.ofSeconds(5));
        }
    }

    @Test
    void convertsCommunicationFailuresToSdkInternalError() throws Exception {
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) ->
                response.send().then()).bindNow();
        int port = server.port();
        server.disposeNow(Duration.ofSeconds(5));

        DefaultA2AClient client = new DefaultA2AClient(card(port));
        A2AError error = assertThrows(A2AError.class, () -> client.sendMessage(params()));

        assertTrue(error instanceof InternalError);
        assertEquals(-32603, error.getCode());
    }

    private AgentCard card(int port) {
        return AgentCard.builder().name("test-agent").description("Test agent")
                .url("http://127.0.0.1:" + port + "/a2a").version("1.0.0")
                .capabilities(new org.a2aproject.sdk.spec.AgentCapabilities(false, false, false, List.of()))
                .defaultInputModes(List.of("text/plain")).defaultOutputModes(List.of("text/plain"))
                .skills(List.of()).supportedInterfaces(List.of())
                .build();
    }

    private MessageSendParams params() {
        Message message = Message.builder().messageId("message-1").role(Message.Role.ROLE_USER)
                .parts(List.of(new TextPart("hello"))).build();
        return MessageSendParams.builder().message(message).build();
    }

    private String sseTask(String taskId, String state) {
        return "data: {\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"task\":{"
                + "\"id\":\"" + taskId + "\",\"contextId\":\"ctx-1\","
                + "\"status\":{\"state\":\"" + state + "\"}}}}\n\n";
    }

}
