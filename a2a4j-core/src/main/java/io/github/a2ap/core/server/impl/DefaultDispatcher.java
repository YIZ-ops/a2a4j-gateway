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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.a2ap.core.exception.A2AError;
import io.github.a2ap.core.jsonrpc.JSONRPCError;
import io.github.a2ap.core.jsonrpc.JSONRPCRequest;
import io.github.a2ap.core.jsonrpc.JSONRPCResponse;
import io.github.a2ap.core.model.Message;
import io.github.a2ap.core.model.MessageSendParams;
import io.github.a2ap.core.model.SendMessageResponse;
import io.github.a2ap.core.model.Task;
import io.github.a2ap.core.model.TaskArtifactUpdateEvent;
import io.github.a2ap.core.model.TaskIdParams;
import io.github.a2ap.core.model.TaskPushNotificationConfig;
import io.github.a2ap.core.model.TaskStatusUpdateEvent;
import io.github.a2ap.core.server.A2AServer;
import io.github.a2ap.core.server.Dispatcher;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Default implementation of the Dispatcher interface.
 * This implementation routes JSON-RPC requests to the appropriate A2A server methods
 * and handles both synchronous and streaming operations.
 * <p>
 * Supported methods include:
 * - SendMessage: Send a message and create a task
 * - SendStreamingMessage: Send a message and subscribe to streaming updates
 * - GetTask: Retrieve task information
 * - CancelTask: Cancel a running task
 * - CreateTaskPushNotificationConfig: Set push notification configuration
 * - GetTaskPushNotificationConfig: Get push notification configuration
 * - SubscribeToTask: Resubscribe to task updates
 */
public class DefaultDispatcher implements Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(DefaultDispatcher.class);

    private final A2AServer a2aServer;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a new DefaultDispatcher.
     *
     * @param a2aServer    The A2A server instance to delegate operations to
     * @param objectMapper The Jackson ObjectMapper for parameter conversion
     */
    public DefaultDispatcher(A2AServer a2aServer, ObjectMapper objectMapper) {
        this.a2aServer = a2aServer;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation supports the following methods:
     * - SendMessage
     * - GetTask
     * - CancelTask
     * - CreateTaskPushNotificationConfig
     * - GetTaskPushNotificationConfig
     */
    @Override
    public JSONRPCResponse dispatch(JSONRPCRequest request) {
        JSONRPCResponse response = new JSONRPCResponse();
        response.setId(request.getId());
        String method = request.getMethod();
        Object params = request.getParams();

        try {
            switch (method) {
                case "SendMessage" -> {
                    MessageSendParams taskSendParams = objectMapper.convertValue(params, MessageSendParams.class);
                    SendMessageResponse messageResponse = a2aServer.handleMessage(taskSendParams);
                    response.setResult(sendMessageResponse(messageResponse));
                }
                case "GetTask" -> {
                    TaskIdParams taskIdParamsGet = objectMapper.convertValue(params, TaskIdParams.class);
                    Task task = a2aServer.getTask(taskIdParamsGet.getId());
                    response.setResult(task);
                }
                case "CancelTask" -> {
                    TaskIdParams taskIdParamsCancel = objectMapper.convertValue(params, TaskIdParams.class);
                    Task cancelledTask = a2aServer.cancelTask(taskIdParamsCancel.getId());
                    response.setResult(cancelledTask);
                }
                case "CreateTaskPushNotificationConfig" -> {
                    TaskPushNotificationConfig configToSet = objectMapper.convertValue(params,
                            TaskPushNotificationConfig.class);
                    TaskPushNotificationConfig setResult = a2aServer.setTaskPushNotification(configToSet);
                    response.setResult(setResult);
                }
                case "GetTaskPushNotificationConfig" -> {
                    TaskIdParams taskIdParamsGetConfig = objectMapper.convertValue(params, TaskIdParams.class);
                    TaskPushNotificationConfig getConfigResult = a2aServer
                            .getTaskPushNotification(taskIdParamsGetConfig.getId());
                    response.setResult(getConfigResult);
                }
                default -> {
                    log.warn("Unsupported method: {}", method);
                    response.setError(new JSONRPCError(JSONRPCError.METHOD_NOT_FOUND, "Method not found",
                            "Method '" + method + "' not supported"));
                }
            }
        } catch (IllegalArgumentException e) {
            response.setError(new JSONRPCError(JSONRPCError.INVALID_PARAMS, "Invalid params", e.getMessage()));
        } catch (Exception e) {
            response.setError(new JSONRPCError(JSONRPCError.INTERNAL_ERROR, "Internal error", e.getMessage()));
            log.error("Internal error processing method {}.", method, e);
        }
        return response;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation supports the following streaming methods:
     * - SendStreamingMessage
     * - SubscribeToTask
     */
    @Override
    public Flux<JSONRPCResponse> dispatchStream(JSONRPCRequest request) {
        JSONRPCResponse errorResponse = newResponse(request);
        String method = request.getMethod();
        Object params = request.getParams();

        try {
            switch (method) {
                case "SendStreamingMessage" -> {
                    MessageSendParams taskSendParams = objectMapper.convertValue(params, MessageSendParams.class);
                    return a2aServer.handleMessageStream(taskSendParams).map(event -> {
                        JSONRPCResponse response = newResponse(request);
                        response.setResult(streamResponse(event));
                        return response;
                    });
                }
                case "SubscribeToTask" -> {
                    TaskIdParams taskIdParamsGet = objectMapper.convertValue(params, TaskIdParams.class);
                    return a2aServer.subscribeToTaskUpdates(taskIdParamsGet.getId())
                            .map(event -> {
                                JSONRPCResponse response = newResponse(request);
                                response.setResult(streamResponse(event));
                                return response;
                            })
                            .onErrorResume(A2AError.class,
                                    error -> Flux.just(protocolErrorResponse(request, error)));
                }
                default -> {
                    log.warn("Unsupported method: {}", method);
                    errorResponse.setError(new JSONRPCError(JSONRPCError.METHOD_NOT_FOUND, "Method not found",
                            "Method '" + method + "' not supported"));
                }
            }
        } catch (IllegalArgumentException e) {
            log.error("Invalid argue error processing request: {}", method, e);
            errorResponse.setError(new JSONRPCError(JSONRPCError.INVALID_REQUEST, "Invalid params", e.getMessage()));
        } catch (Exception e) {
            errorResponse.setError(new JSONRPCError(JSONRPCError.INTERNAL_ERROR, "Internal error", e.getMessage()));
            log.error("Internal error processing request {}.", method, e);
        }
        return Flux.just(errorResponse);
    }

    private JSONRPCResponse newResponse(JSONRPCRequest request) {
        JSONRPCResponse response = new JSONRPCResponse();
        response.setId(request.getId());
        return response;
    }

    private JSONRPCResponse protocolErrorResponse(JSONRPCRequest request, A2AError error) {
        JSONRPCResponse response = newResponse(request);
        response.setError(new JSONRPCError(error.getCode(), error.getMessage(), error.getData()));
        return response;
    }

    private Map<String, Object> streamResponse(Object event) {
        if (event instanceof Task task) {
            return Map.of("task", task);
        }
        if (event instanceof TaskStatusUpdateEvent statusUpdate) {
            return Map.of("statusUpdate", statusUpdate);
        }
        if (event instanceof TaskArtifactUpdateEvent artifactUpdate) {
            return Map.of("artifactUpdate", artifactUpdate);
        }
        if (event instanceof Message message) {
            return Map.of("message", message);
        }
        throw new IllegalArgumentException("unsupported A2A 1.0 stream response: "
                + event.getClass().getName());
    }

    private Map<String, Object> sendMessageResponse(SendMessageResponse response) {
        if (response instanceof Task task) {
            return Map.of("task", task);
        }
        if (response instanceof Message message) {
            return Map.of("message", message);
        }
        throw new IllegalArgumentException("unsupported A2A 1.0 send response: "
                + (response == null ? "null" : response.getClass().getName()));
    }
}
