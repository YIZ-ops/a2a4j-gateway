/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.a2ap.core.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithUri;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendConfiguration;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

class OfficialSdkModelContractTest {

    @Test
    void supportsOfficialPartVariantsAndRoundTrip() throws Exception {
        Message message = new Message(Message.Role.ROLE_USER,
                List.of(new TextPart("hello"), new DataPart(Map.of("answer", 42)),
                        new FilePart(new FileWithUri("text/plain", "hello.txt", "https://example.test/hello.txt"))),
                "message-1", "context-1", "task-1", List.of(), Map.of("tenant", "tenant-a"), List.of());

        Message parsed = JsonUtil.fromJson(JsonUtil.toJson(message), Message.class);
        assertEquals("message-1", parsed.messageId());
        assertEquals(3, parsed.parts().size());
        assertInstanceOf(TextPart.class, parsed.parts().get(0));
        assertInstanceOf(DataPart.class, parsed.parts().get(1));
        assertInstanceOf(FilePart.class, parsed.parts().get(2));
    }

    @Test
    void modelsTaskArtifactAndStreamingEvents() throws Exception {
        TaskStatus status = new TaskStatus(TaskState.TASK_STATE_WORKING, null, OffsetDateTime.now());
        Artifact artifact = Artifact.builder().artifactId("artifact-1").name("answer")
                .parts(new TextPart("chunk")).build();
        Task task = Task.builder().id("task-1").contextId("context-1").status(status)
                .artifacts(List.of(artifact)).history(List.of()).metadata(Map.of()).build();
        Task parsedTask = JsonUtil.fromJson(JsonUtil.toJson(task), Task.class);
        assertEquals("task-1", parsedTask.id());
        assertEquals("artifact-1", parsedTask.artifacts().get(0).artifactId());

        TaskStatusUpdateEvent statusEvent = new TaskStatusUpdateEvent("task-1", status, "context-1", Map.of());
        TaskArtifactUpdateEvent artifactEvent = new TaskArtifactUpdateEvent("task-1", artifact, "context-1",
                false, true, Map.of());
        StreamingEventKind parsedStatus = JsonUtil.fromJson(JsonUtil.toJson(statusEvent), StreamingEventKind.class);
        StreamingEventKind parsedArtifact = JsonUtil.fromJson(JsonUtil.toJson(artifactEvent), StreamingEventKind.class);
        assertNotNull(parsedStatus);
        assertNotNull(parsedArtifact);
    }

    @Test
    void modelsOfficialRequestParametersAndPushConfig() {
        Message message = new Message(Message.Role.ROLE_USER, List.of(new TextPart("hello")), "message-1", null,
                "task-1", List.of(), Map.of(), List.of());
        MessageSendConfiguration configuration = MessageSendConfiguration.builder()
                .acceptedOutputModes(List.of("text/plain")).historyLength(10).returnImmediately(true).build();
        MessageSendParams params = new MessageSendParams(message, configuration, Map.of("trace", "t"), "tenant-a");
        TaskPushNotificationConfig push = TaskPushNotificationConfig.builder().id("push-1").taskId("task-1")
                .url("https://example.test/callback").token("opaque").tenant("tenant-a").build();
        assertEquals("tenant-a", params.tenant());
        assertEquals(true, params.configuration().returnImmediately());
        assertEquals("task-1", push.taskId());
    }

}
