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

package io.github.a2ap.gateway.core.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.a2ap.gateway.api.model.GatewayEvent;
import java.util.Objects;

/** Converts normalized gateway events to downstream SSE frames without exposing secrets. */
public final class GatewayEventSseEncoder {

    private final ObjectMapper objectMapper;

    /** Creates an encoder with a fresh Jackson mapper. */
    public GatewayEventSseEncoder() {
        this(new ObjectMapper());
    }

    /** Creates an encoder with the supplied thread-safe mapper. */
    public GatewayEventSseEncoder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Encodes one normalized event as an SSE frame. */
    public SseEvent encode(GatewayEvent event) {
        Objects.requireNonNull(event, "event");
        try {
            String id = event.metadata().get("sseEventId") == null ? null
                    : event.metadata().get("sseEventId").toString();
            String type = switch (event.type()) {
                case TASK_ACCEPTED, TASK_STATUS -> "task-status";
                case TASK_ARTIFACT -> "task-artifact";
                case TASK_COMPLETED -> "task-completed";
                case ERROR -> "error";
            };
            return new SseEvent(id, type, objectMapper.writeValueAsString(event.payload()), null);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("could not encode gateway event as SSE", ex);
        }
    }

}
