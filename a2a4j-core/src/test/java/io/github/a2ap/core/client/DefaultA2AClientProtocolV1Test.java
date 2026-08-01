/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package io.github.a2ap.core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.a2ap.core.client.impl.DefaultA2AClient;
import io.github.a2ap.core.model.AgentCard;
import io.github.a2ap.core.model.Message;
import io.github.a2ap.core.model.MessageSendParams;
import io.github.a2ap.core.model.Task;
import io.github.a2ap.core.model.TextPart;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Verifies that the legacy client emits the A2A 1.0 JSON-RPC wire names and version header. */
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
                                + "\"kind\":\"task\",\"id\":\"task-1\",\"contextId\":\"ctx-1\"}}"))
                        .then();
            });
        }).bindNow();
        try {
            AgentCard card = AgentCard.builder().name("test-agent")
                    .url("http://127.0.0.1:" + server.port() + "/a2a").version("1.0.0").build();
            DefaultA2AClient client = new DefaultA2AClient(card);
            Message message = Message.builder().messageId("message-1").role("user")
                    .parts(List.of(TextPart.builder().text("hello").build())).build();
            Task task = (Task) client.sendMessage(MessageSendParams.builder().message(message).build());

            assertEquals("1.0", version.get());
            assertTrue(requestBody.get().contains("\"method\":\"SendMessage\""), requestBody.get());
            assertEquals("task-1", task.getId());
        }
        finally {
            server.disposeNow(Duration.ofSeconds(5));
        }
    }

}
