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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.a2ap.core.server.A2AServer;
import io.github.a2ap.core.server.Dispatcher;
import io.github.a2ap.core.util.SdkModelCodec;
import java.util.LinkedHashMap;
import java.util.Map;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Event;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/** JSON-RPC protocol adapter backed by the official SDK domain model. */
public class DefaultDispatcher implements Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(DefaultDispatcher.class);

    private final A2AServer a2aServer;
    private final ObjectMapper objectMapper;

    public DefaultDispatcher(A2AServer a2aServer, ObjectMapper objectMapper) {
        this.a2aServer = a2aServer;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> dispatch(Map<String, Object> request) {
        Object id = id(request);
        try {
            String method = requiredText(request, "method");
            Object result = switch (method) {
                case "SendMessage" -> payload(a2aServer.handleMessage(
                        decode(params(request), MessageSendParams.class)));
                case "GetTask" -> a2aServer.getTask(
                        decode(params(request), TaskQueryParams.class).id());
                case "CancelTask" -> a2aServer.cancelTask(
                        decode(params(request), TaskIdParams.class).id());
                case "CreateTaskPushNotificationConfig" -> a2aServer.setTaskPushNotification(
                        decode(params(request), TaskPushNotificationConfig.class));
                case "GetTaskPushNotificationConfig" -> a2aServer.getTaskPushNotification(
                        decode(params(request), TaskIdParams.class).id());
                default -> throw new MethodNotFoundError(null, "Method not found",
                        Map.of("method", method));
            };
            return success(id, result);
        }
        catch (A2AError ex) {
            return failure(id, ex);
        }
        catch (IllegalArgumentException ex) {
            return failure(id, new InvalidParamsError(null, "Invalid parameters",
                    Map.of("cause", message(ex))));
        }
        catch (Exception ex) {
            log.error("Internal error processing JSON-RPC request", ex);
            return failure(id, new InternalError("Internal error"));
        }
    }

    @Override
    public Flux<Map<String, Object>> dispatchStream(Map<String, Object> request) {
        Object id = id(request);
        try {
            String method = requiredText(request, "method");
            Flux<StreamingEventKind> events = switch (method) {
                case "SendStreamingMessage" -> a2aServer.handleMessageStream(
                        decode(params(request), MessageSendParams.class));
                case "SubscribeToTask" -> a2aServer.subscribeToTaskUpdates(
                        decode(params(request), TaskIdParams.class).id());
                default -> throw new MethodNotFoundError(null, "Method not found",
                        Map.of("method", method));
            };
            return events.map(event -> success(id, payload(event)))
                    .onErrorResume(ex -> {
                        log.error("Asynchronous error processing streaming JSON-RPC request", ex);
                        return Flux.just(failure(id, protocolError(ex)));
                    });
        }
        catch (A2AError ex) {
            return Flux.just(failure(id, ex));
        }
        catch (IllegalArgumentException ex) {
            return Flux.just(failure(id, new InvalidParamsError(null, "Invalid parameters",
                    Map.of("cause", message(ex)))));
        }
        catch (Exception ex) {
            log.error("Internal error processing streaming JSON-RPC request", ex);
            return Flux.just(failure(id, new InternalError("Internal error")));
        }
    }

    private <T> T decode(Object value, Class<T> type) {
        try {
            return SdkModelCodec.fromJson(objectMapper.writeValueAsString(value), type);
        }
        catch (Exception ex) {
            throw new IllegalArgumentException("invalid " + type.getSimpleName(), ex);
        }
    }

    private Object payload(Event event) {
        if (event instanceof Task task) {
            return SdkModelCodec.toMap(task);
        }
        if (event instanceof Message message) {
            return Map.of("message", SdkModelCodec.messageMap(message));
        }
        if (event instanceof TaskStatusUpdateEvent statusUpdate) {
            return SdkModelCodec.toMap(statusUpdate);
        }
        if (event instanceof TaskArtifactUpdateEvent artifactUpdate) {
            return SdkModelCodec.toMap(artifactUpdate);
        }
        throw new IllegalArgumentException("unsupported A2A SDK event: " + event.getClass().getName());
    }

    private Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = envelope(id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> failure(Object id, A2AError error) {
        Map<String, Object> response = envelope(id);
        Map<String, Object> errorBody = new LinkedHashMap<>();
        errorBody.put("code", error.getCode());
        errorBody.put("message", error.getMessage());
        if (!error.getDetails().isEmpty()) {
            errorBody.put("data", error.getDetails());
        }
        response.put("error", errorBody);
        return response;
    }

    private Map<String, Object> envelope(Object id) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        return response;
    }

    private Object id(Map<String, Object> request) {
        return request == null ? null : request.get("id");
    }

    private Object params(Map<String, Object> request) {
        return request == null ? null : request.get("params");
    }

    private String requiredText(Map<String, Object> request, String field) {
        Object value = request == null ? null : request.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("missing " + field);
        }
        return text;
    }

    private String message(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? "Invalid parameters" : ex.getMessage();
    }

    private A2AError protocolError(Throwable ex) {
        if (ex instanceof A2AError error) {
            return error;
        }
        if (ex instanceof IllegalArgumentException argumentException) {
            return new InvalidParamsError(null, "Invalid parameters",
                    Map.of("cause", message(argumentException)));
        }
        return new InternalError("Internal error");
    }

}
