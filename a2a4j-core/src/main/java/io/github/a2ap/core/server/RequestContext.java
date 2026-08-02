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

import java.util.List;
import java.util.Objects;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;

/** Execution context owned by the server runtime rather than the A2A wire model. */
public final class RequestContext {

    private final String taskId;
    private final String contextId;
    private final MessageSendParams request;
    private final Task task;
    private final List<Task> relatedTasks;

    public RequestContext(String taskId, String contextId, MessageSendParams request, Task task,
            List<Task> relatedTasks) {
        this.taskId = taskId;
        this.contextId = contextId;
        this.request = request;
        this.task = task;
        this.relatedTasks = relatedTasks;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTaskId() {
        return taskId;
    }

    public String getContextId() {
        return contextId;
    }

    public MessageSendParams getRequest() {
        return request;
    }

    public Task getTask() {
        return task;
    }

    public List<Task> getRelatedTasks() {
        return relatedTasks;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RequestContext that)) {
            return false;
        }
        return Objects.equals(taskId, that.taskId) && Objects.equals(contextId, that.contextId)
                && Objects.equals(request, that.request) && Objects.equals(task, that.task)
                && Objects.equals(relatedTasks, that.relatedTasks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, contextId, request, task, relatedTasks);
    }

    @Override
    public String toString() {
        return "RequestContext{" + "taskId='" + taskId + '\'' + ", contextId='" + contextId + '\''
                + ", request=" + request + ", task=" + task + ", relatedTasks=" + relatedTasks + '}';
    }

    /** Builder for an execution context. */
    public static final class Builder {

        private String taskId;
        private String contextId;
        private MessageSendParams request;
        private Task task;
        private List<Task> relatedTasks;

        public Builder taskId(String value) {
            taskId = value;
            return this;
        }

        public Builder contextId(String value) {
            contextId = value;
            return this;
        }

        public Builder request(MessageSendParams value) {
            request = value;
            return this;
        }

        public Builder task(Task value) {
            task = value;
            return this;
        }

        public Builder relatedTasks(List<Task> value) {
            relatedTasks = value;
            return this;
        }

        public RequestContext build() {
            return new RequestContext(taskId, contextId, request, task, relatedTasks);
        }

    }

}
