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

import io.github.a2ap.gateway.api.model.OutboundResponse;
import io.github.a2ap.gateway.api.model.ProtocolDescriptor;
import io.github.a2ap.gateway.core.exception.AgentTransportException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Incremental SSE parser and formatter with a bounded per-event buffer. */
public final class SseEventCodec {

    private final int maxEventBytes;

    /** Creates a codec with the supplied maximum UTF-8 event size. */
    public SseEventCodec(int maxEventBytes) {
        if (maxEventBytes < 1) {
            throw new IllegalArgumentException("maxEventBytes must be positive");
        }
        this.maxEventBytes = maxEventBytes;
    }

    /** Creates a new incremental parser. */
    public Parser parser() {
        return new Parser(maxEventBytes);
    }

    /** Formats an event according to the W3C SSE wire representation. */
    public String encode(SseEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        StringBuilder result = new StringBuilder();
        if (event.id() != null && !event.id().isBlank()) {
            result.append("id: ").append(event.id()).append('\n');
        }
        if (event.event() != null && !event.event().isBlank() && !"message".equals(event.event())) {
            result.append("event: ").append(event.event()).append('\n');
        }
        if (event.retry() != null) {
            result.append("retry: ").append(event.retry()).append('\n');
        }
        String[] lines = event.data().split("\\n", -1);
        for (String line : lines) {
            result.append("data: ").append(line).append('\n');
        }
        return result.append('\n').toString();
    }

    /** Converts one parsed SSE event into the transport-neutral response shape. */
    public OutboundResponse toResponse(SseEvent event, ProtocolDescriptor protocol, int statusCode,
            Map<String, String> responseHeaders, boolean terminal) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (responseHeaders != null) {
            headers.putAll(responseHeaders);
        }
        if (event.id() != null && !event.id().isBlank()) {
            headers.put("SSE-Id", event.id());
        }
        if (event.event() != null && !event.event().isBlank()) {
            headers.put("SSE-Event", event.event());
        }
        if (event.retry() != null) {
            headers.put("SSE-Retry", Long.toString(event.retry()));
        }
        return new OutboundResponse(protocol, statusCode, event.data(), headers, terminal);
    }

    /** Incremental parser that preserves event order across network chunks. */
    public static final class Parser {

        private final int maxEventBytes;

        private final StringBuilder lineBuffer = new StringBuilder();

        private final StringBuilder data = new StringBuilder();

        private String id;

        private String event;

        private Long retry;

        private Parser(int maxEventBytes) {
            this.maxEventBytes = maxEventBytes;
        }

        /** Feeds a UTF-8 chunk and returns all complete events found in it. */
        public List<SseEvent> feed(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return List.of();
            }
            lineBuffer.append(chunk);
            List<SseEvent> events = new ArrayList<>();
            int newline;
            while ((newline = indexOfNewline(lineBuffer)) >= 0) {
                String line = lineBuffer.substring(0, newline);
                lineBuffer.delete(0, newline + 1);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                parseLine(line, events);
            }
            ensureBounded();
            return List.copyOf(events);
        }

        /** Flushes a final unterminated event when the upstream connection closes. */
        public List<SseEvent> complete() {
            if (lineBuffer.length() > 0) {
                parseLine(lineBuffer.toString(), new ArrayList<>());
                lineBuffer.setLength(0);
            }
            List<SseEvent> events = new ArrayList<>();
            dispatch(events);
            return List.copyOf(events);
        }

        private void parseLine(String line, List<SseEvent> events) {
            if (line.isEmpty()) {
                dispatch(events);
                return;
            }
            if (line.charAt(0) == ':') {
                return;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "id" -> id = value;
                case "event" -> event = value;
                case "data" -> {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(value);
                }
                case "retry" -> {
                    try {
                        retry = Long.valueOf(value);
                    }
                    catch (NumberFormatException ignored) {
                        retry = null;
                    }
                }
                default -> {
                    // Ignore unknown fields as required by the SSE specification.
                }
            }
            ensureBounded();
        }

        private void dispatch(List<SseEvent> events) {
            if (data.length() == 0) {
                id = null;
                event = null;
                retry = null;
                return;
            }
            events.add(new SseEvent(id, event, data.toString(), retry));
            data.setLength(0);
            event = null;
            retry = null;
        }

        private int indexOfNewline(StringBuilder value) {
            for (int index = 0; index < value.length(); index++) {
                if (value.charAt(index) == '\n') {
                    return index;
                }
            }
            return -1;
        }

        private void ensureBounded() {
            if (lineBuffer.length() + data.length() > maxEventBytes) {
                throw new AgentTransportException(AgentTransportException.Code.RESPONSE_TOO_LARGE,
                        "upstream SSE event exceeds configured size");
            }
        }

    }

}
