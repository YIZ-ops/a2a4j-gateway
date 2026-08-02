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

package io.github.a2ap.core.server.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.a2ap.core.model.MessageSendParams;
import io.github.a2ap.core.model.SendMessageResponse;
import io.github.a2ap.core.model.Task;
import io.github.a2ap.core.model.TaskState;
import io.github.a2ap.core.model.TaskStatus;
import io.github.a2ap.core.model.TaskStatusUpdateEvent;
import io.github.a2ap.core.model.RequestContext;
import io.github.a2ap.core.server.AgentExecutor;
import io.github.a2ap.core.server.EventQueue;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DefaultA2AServerReturnImmediatelyTest {

    @Test
    void streamingMessageEmitsCurrentTaskBeforeStatusUpdates() throws Exception {
        InMemoryTaskManager taskManager = new InMemoryTaskManager(new InMemoryTaskStore());
        AgentExecutor executor = new AgentExecutor() {
            @Override
            public Mono<Void> execute(RequestContext context, EventQueue queue) {
                return Mono.fromRunnable(() -> {
                    queue.enqueueEvent(new TaskStatusUpdateEvent(context.getTaskId(), context.getContextId(),
                            TaskStatus.builder().state(TaskState.COMPLETED).build(), Map.of()));
                    queue.close();
                });
            }

            @Override
            public Mono<Void> cancel(String taskId) {
                return Mono.empty();
            }
        };
        DefaultA2AServer server = new DefaultA2AServer(taskManager, executor, new InMemoryQueueManager(), null);
        MessageSendParams params = new ObjectMapper().readValue("{\"message\":{\"messageId\":\"m-stream\","
                + "\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"hello\"}]}}", MessageSendParams.class);

        StepVerifier.create(server.handleMessageStream(params))
                .assertNext(first -> {
                    Task task = assertInstanceOf(Task.class, first);
                    assertNotNull(task.getId());
                    assertNotNull(task.getContextId());
                })
                .assertNext(next -> {
                    TaskStatusUpdateEvent update = assertInstanceOf(TaskStatusUpdateEvent.class, next);
                    assertEquals(TaskState.COMPLETED, update.getStatus().getState());
                })
                .verifyComplete();
    }

    @Test
    void returnsThePersistedTaskBeforeBackgroundExecutionCompletes() throws Exception {
        InMemoryTaskManager taskManager = new InMemoryTaskManager(new InMemoryTaskStore());
        AgentExecutor executor = new AgentExecutor() {
            @Override
            public Mono<Void> execute(RequestContext context, EventQueue queue) {
                return Mono.delay(Duration.ofMillis(500)).doOnNext(ignored -> {
                    queue.enqueueEvent(new TaskStatusUpdateEvent(context.getTaskId(), context.getContextId(),
                            TaskStatus.builder().state(TaskState.COMPLETED).build(), Map.of()));
                    queue.close();
                }).then();
            }

            @Override
            public Mono<Void> cancel(String taskId) {
                return Mono.empty();
            }
        };
        DefaultA2AServer server = new DefaultA2AServer(taskManager, executor, new InMemoryQueueManager(), null);
        MessageSendParams params = new ObjectMapper().readValue("{\"message\":{\"messageId\":\"m-1\","
                + "\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"hello\"}]},"
                + "\"configuration\":{\"returnImmediately\":true}}", MessageSendParams.class);

        long started = System.nanoTime();
        SendMessageResponse response = server.handleMessage(params);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        Task task = assertInstanceOf(Task.class, response);
        assertTrue(elapsedMillis < 350, "returnImmediately took " + elapsedMillis + " ms");
        assertNotNull(server.getTask(task.getId()));
        assertEquals(Boolean.TRUE, params.getConfiguration().getReturnImmediately());

        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (server.getTask(task.getId()).getStatus().getState() != TaskState.COMPLETED
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(TaskState.COMPLETED, server.getTask(task.getId()).getStatus().getState());
    }
}
