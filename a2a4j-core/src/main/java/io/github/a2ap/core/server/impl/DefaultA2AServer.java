/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.a2ap.core.server.impl;

import io.github.a2ap.core.server.A2AServer;
import io.github.a2ap.core.server.AgentExecutor;
import io.github.a2ap.core.server.EventQueue;
import io.github.a2ap.core.server.QueueManager;
import io.github.a2ap.core.server.RequestContext;
import io.github.a2ap.core.server.TaskManager;
import java.time.OffsetDateTime;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Event;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A2A server runtime using the official SDK domain model. */
public class DefaultA2AServer implements A2AServer {

    private static final Logger log = LoggerFactory.getLogger(DefaultA2AServer.class);

    private final TaskManager taskManager;
    private final AgentExecutor agentExecutor;
    private final QueueManager queueManager;
    private final AgentCard selfCard;

    public DefaultA2AServer(TaskManager taskManager, AgentExecutor agentExecutor, QueueManager queueManager,
            AgentCard selfCard) {
        this.taskManager = taskManager;
        this.agentExecutor = agentExecutor;
        this.queueManager = queueManager;
        this.selfCard = selfCard;
    }

    @Override
    public Event handleMessage(MessageSendParams params) {
        validate(params);
        RequestContext context = taskManager.loadOrCreateContext(params);
        Task task = context.getTask();
        EventQueue queue = queueManager.create(context.getTaskId());
        queue.enqueueEvent(task);
        Event response = agentExecutor.execute(context, queue)
                .then(queue.asFlux()
                        .flatMap(event -> applyUpdate(task, event).thenReturn((Event) event))
                        .filter(event -> !(event instanceof Task))
                        .next())
                .doFinally(signal -> queueManager.remove(context.getTaskId()))
                .block();
        return response == null ? task : response;
    }

    @Override
    public Flux<StreamingEventKind> handleMessageStream(MessageSendParams params) {
        validate(params);
        RequestContext context = taskManager.loadOrCreateContext(params);
        Task task = context.getTask();
        EventQueue queue = queueManager.create(context.getTaskId());
        return Flux.merge(
                agentExecutor.execute(context, queue).thenMany(Flux.empty()),
                queue.asFlux().doOnNext(event -> applyUpdate(task, event).block()))
                .doFinally(signal -> queueManager.remove(context.getTaskId()));
    }

    @Override
    public Task getTask(String taskId) {
        return taskManager.getTask(taskId);
    }

    @Override
    public Task cancelTask(String taskId) {
        Task task = taskManager.getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Cancel Task id not found for cancellation");
        }
        TaskStatus status = new TaskStatus(TaskState.TASK_STATE_CANCELED, null, OffsetDateTime.now());
        EventQueue queue = queueManager.get(taskId);
        if (queue != null) {
            queue.enqueueEvent(new TaskStatusUpdateEvent(taskId, status, task.contextId(), null));
            queue.close();
        }
        agentExecutor.cancel(taskId).block();
        return taskManager.applyStatusUpdate(task,
                new TaskStatusUpdateEvent(taskId, status, task.contextId(), null)).block();
    }

    @Override
    public TaskPushNotificationConfig setTaskPushNotification(TaskPushNotificationConfig config) {
        if (config == null || config.taskId() == null || config.taskId().isBlank()) {
            return null;
        }
        taskManager.registerTaskNotification(config);
        return config;
    }

    @Override
    public TaskPushNotificationConfig getTaskPushNotification(String taskId) {
        return taskManager.getTaskNotification(taskId);
    }

    @Override
    public Flux<StreamingEventKind> subscribeToTaskUpdates(String taskId) {
        Task task = taskManager.getTask(taskId);
        if (task == null) {
            return Flux.error(new IllegalArgumentException("Task not found: " + taskId));
        }
        if (task.status() != null && isFinal(task.status().state())) {
            return Flux.just(new TaskStatusUpdateEvent(taskId, task.status(), task.contextId(), null));
        }
        EventQueue queue = queueManager.tap(taskId);
        return queue == null ? Flux.empty() : queue.asFlux();
    }

    @Override
    public AgentCard getSelfAgentCard() {
        return selfCard;
    }

    @Override
    public AgentCard getAuthenticatedExtendedCard() {
        return selfCard;
    }

    private Mono<Task> applyUpdate(Task task, StreamingEventKind event) {
        if (event instanceof TaskStatusUpdateEvent statusUpdate) {
            return taskManager.applyStatusUpdate(task, statusUpdate);
        }
        if (event instanceof TaskArtifactUpdateEvent artifactUpdate) {
            return taskManager.applyArtifactUpdate(task, artifactUpdate);
        }
        return Mono.just(task);
    }

    private static void validate(MessageSendParams params) {
        if (params == null || params.message() == null || params.message().parts() == null
                || params.message().parts().isEmpty()) {
            throw new IllegalArgumentException("Task params must have at least one message part");
        }
    }

    private static boolean isFinal(TaskState state) {
        return state == TaskState.TASK_STATE_COMPLETED || state == TaskState.TASK_STATE_FAILED
                || state == TaskState.TASK_STATE_CANCELED || state == TaskState.TASK_STATE_REJECTED;
    }

}
