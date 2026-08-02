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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.a2ap.core.exception.A2AError;
import io.github.a2ap.core.jsonrpc.JSONRPCRequest;
import io.github.a2ap.core.jsonrpc.JSONRPCResponse;
import io.github.a2ap.core.model.RequestContext;
import io.github.a2ap.core.model.Task;
import io.github.a2ap.core.model.TaskState;
import io.github.a2ap.core.model.TaskStatus;
import io.github.a2ap.core.model.TaskStatusUpdateEvent;
import io.github.a2ap.core.server.AgentExecutor;
import io.github.a2ap.core.server.EventQueue;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DefaultA2AServerSubscribeToTaskTest {

    @Test
    void emitsCurrentTaskFirstAndBackfillsContextIdOnUpdates() {
        Fixture fixture = new Fixture();
        Task task = fixture.saveTask(TaskState.WORKING);
        EventQueue queue = fixture.queueManager.create(task.getId());

        StepVerifier.create(fixture.server.subscribeToTaskUpdates(task.getId()))
                .assertNext(first -> {
                    Task current = assertInstanceOf(Task.class, first);
                    assertEquals(task.getId(), current.getId());
                    assertEquals(task.getContextId(), current.getContextId());
                    assertEquals(TaskState.WORKING, current.getStatus().getState());
                })
                .then(() -> queue.enqueueEvent(TaskStatusUpdateEvent.builder()
                        .taskId(task.getId())
                        .status(TaskStatus.builder().state(TaskState.COMPLETED).build())
                        .build()))
                .then(queue::close)
                .assertNext(next -> {
                    TaskStatusUpdateEvent update = assertInstanceOf(TaskStatusUpdateEvent.class, next);
                    assertEquals(task.getContextId(), update.getContextId());
                })
                .verifyComplete();
    }

    @Test
    void rejectsSubscriptionToTerminalTaskWithA2AError() {
        Fixture fixture = new Fixture();
        Task task = fixture.saveTask(TaskState.COMPLETED);

        StepVerifier.create(fixture.server.subscribeToTaskUpdates(task.getId()))
                .expectErrorSatisfies(error -> {
                    A2AError a2aError = assertInstanceOf(A2AError.class, error);
                    assertEquals(A2AError.UNSUPPORTED_OPERATION, a2aError.getCode());
                })
                .verify();
    }

    @Test
    void dispatcherSerializesTerminalSubscriptionAsUnsupportedOperation() {
        Fixture fixture = new Fixture();
        Task task = fixture.saveTask(TaskState.COMPLETED);
        DefaultDispatcher dispatcher = new DefaultDispatcher(fixture.server, new ObjectMapper());
        JSONRPCRequest request = new JSONRPCRequest();
        request.setId("subscribe-1");
        request.setMethod("SubscribeToTask");
        request.setParams(Map.of("id", task.getId()));

        JSONRPCResponse response = dispatcher.dispatchStream(request).blockFirst();

        assertEquals(A2AError.UNSUPPORTED_OPERATION, response.getError().getCode());
    }

    private static final class Fixture {
        private final InMemoryTaskManager taskManager = new InMemoryTaskManager(new InMemoryTaskStore());
        private final InMemoryQueueManager queueManager = new InMemoryQueueManager();
        private final DefaultA2AServer server = new DefaultA2AServer(taskManager, new NoOpExecutor(), queueManager,
                null);

        private Task saveTask(TaskState state) {
            Task task = Task.builder()
                    .id("task-1")
                    .contextId("context-1")
                    .status(TaskStatus.builder().state(state).build())
                    .build();
            taskManager.saveTask(task).block();
            return task;
        }
    }

    private static final class NoOpExecutor implements AgentExecutor {
        @Override
        public Mono<Void> execute(RequestContext context, EventQueue eventQueue) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> cancel(String taskId) {
            return Mono.empty();
        }
    }
}
