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

import io.github.a2ap.core.server.RequestContext;
import io.github.a2ap.core.server.TaskManager;
import io.github.a2ap.core.server.TaskStore;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.UpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** In-memory task lifecycle implementation backed by official SDK domain records. */
public class InMemoryTaskManager implements TaskManager {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTaskManager.class);

    private final TaskStore taskStore;
    private final Map<String, TaskPushNotificationConfig> notificationConfigMap = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> contextTaskIdMap = new ConcurrentHashMap<>();

    public InMemoryTaskManager(TaskStore taskStore) {
        this.taskStore = taskStore;
    }

    @Override
    public RequestContext loadOrCreateContext(MessageSendParams params) {
        Message message = Objects.requireNonNull(params, "params").message();
        String taskId = message.taskId() == null ? UUID.randomUUID().toString() : message.taskId();
        String contextId = message.contextId() == null ? UUID.randomUUID().toString() : message.contextId();
        Task currentTask = taskStore.load(taskId);
        if (currentTask == null) {
            currentTask = Task.builder()
                    .id(taskId)
                    .contextId(contextId)
                    .status(new TaskStatus(TaskState.TASK_STATE_SUBMITTED, null, OffsetDateTime.now()))
                    .metadata(params.metadata())
                    .artifacts(new ArrayList<>())
                    .history(new ArrayList<>())
                    .build();
            taskStore.save(currentTask);
            log.info("Created new message task: {}", currentTask.id());
        }
        else if (isFinal(currentTask.status().state())
                || currentTask.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED
                || currentTask.status().state() == TaskState.TASK_STATE_AUTH_REQUIRED) {
            TaskState next = isFinal(currentTask.status().state())
                    ? TaskState.TASK_STATE_SUBMITTED : TaskState.TASK_STATE_WORKING;
            currentTask = withStatus(currentTask, new TaskStatus(next, null, OffsetDateTime.now()));
            taskStore.save(currentTask);
        }
        contextTaskIdMap.computeIfAbsent(contextId, ignored -> new HashSet<>()).add(taskId);
        List<Task> relatedTasks = contextTaskIdMap.get(contextId).stream()
                .filter(id -> !Objects.equals(id, taskId))
                .map(taskStore::load)
                .filter(Objects::nonNull)
                .toList();
        return RequestContext.builder()
                .taskId(taskId)
                .contextId(contextId)
                .request(params)
                .task(currentTask)
                .relatedTasks(relatedTasks)
                .build();
    }

    @Override
    public Task getTask(String taskId) {
        return taskStore.load(taskId);
    }

    @Override
    public Mono<Task> applyTaskUpdate(Task task, List<UpdateEvent> taskUpdates) {
        Task current = task;
        if (taskUpdates != null) {
            for (UpdateEvent update : taskUpdates) {
                current = applyUpdate(current, update);
            }
        }
        taskStore.save(current);
        return Mono.just(current);
    }

    @Override
    public Mono<Task> applyTaskUpdate(Task task, UpdateEvent update) {
        return applyTaskUpdate(task, update == null ? List.of() : List.of(update));
    }

    @Override
    public Mono<Task> applyStatusUpdate(Task task, TaskStatusUpdateEvent event) {
        return applyTaskUpdate(task, event);
    }

    @Override
    public Mono<Task> applyArtifactUpdate(Task task, TaskArtifactUpdateEvent event) {
        return applyTaskUpdate(task, event);
    }

    @Override
    public void registerTaskNotification(TaskPushNotificationConfig config) {
        notificationConfigMap.put(config.taskId(), config);
    }

    @Override
    public TaskPushNotificationConfig getTaskNotification(String taskId) {
        return notificationConfigMap.get(taskId);
    }

    private Task applyUpdate(Task task, UpdateEvent update) {
        if (update instanceof TaskStatusUpdateEvent statusUpdate) {
            return withStatus(task, statusUpdate.status());
        }
        if (update instanceof TaskArtifactUpdateEvent artifactUpdate) {
            Artifact incoming = artifactUpdate.artifact();
            List<Artifact> artifacts = new ArrayList<>(nullToEmpty(task.artifacts()));
            int existingIndex = indexOf(artifacts, incoming.artifactId());
            if (Boolean.TRUE.equals(artifactUpdate.append()) && existingIndex >= 0) {
                Artifact existing = artifacts.get(existingIndex);
                List<Part<?>> parts = new ArrayList<>(nullToEmpty(existing.parts()));
                parts.addAll(nullToEmpty(incoming.parts()));
                artifacts.set(existingIndex, Artifact.builder(existing).parts(parts).build());
            }
            else if (existingIndex >= 0) {
                artifacts.set(existingIndex, incoming);
            }
            else {
                artifacts.add(incoming);
            }
            return Task.builder(task).artifacts(artifacts).build();
        }
        throw new IllegalArgumentException("unsupported SDK task update: " + update);
    }

    private Task withStatus(Task task, TaskStatus status) {
        TaskStatus timestamped = new TaskStatus(status.state(), status.message(), OffsetDateTime.now());
        List<Message> history = new ArrayList<>(nullToEmpty(task.history()));
        if (status.message() != null && status.message().role() == Message.Role.ROLE_AGENT) {
            history.add(status.message());
        }
        return Task.builder(task).status(timestamped).history(history).build();
    }

    private static int indexOf(List<Artifact> artifacts, String artifactId) {
        for (int index = 0; index < artifacts.size(); index++) {
            if (Objects.equals(artifactId, artifacts.get(index).artifactId())) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isFinal(TaskState state) {
        return state == TaskState.TASK_STATE_COMPLETED || state == TaskState.TASK_STATE_FAILED
                || state == TaskState.TASK_STATE_CANCELED || state == TaskState.TASK_STATE_REJECTED;
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

}
