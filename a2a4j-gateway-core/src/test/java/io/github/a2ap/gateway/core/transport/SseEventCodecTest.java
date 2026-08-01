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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.a2ap.gateway.api.model.GatewayEvent;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

class SseEventCodecTest {

    @Test
    void parsesEventsAcrossChunksAndFormatsThemAgain() {
        SseEventCodec codec = new SseEventCodec(1024);
        SseEventCodec.Parser parser = codec.parser();

        assertEquals(List.of(), parser.feed("id: e-1\nevent: task\ndata: {\"a\":"));
        List<SseEvent> events = parser.feed("1}\ndata: next\n\n: heartbeat\n\n");
        assertEquals(1, events.size());
        assertEquals("e-1", events.get(0).id());
        assertEquals("task", events.get(0).event());
        assertEquals("{\"a\":1}\nnext", events.get(0).data());
        assertEquals("id: e-1\nevent: task\ndata: {\"a\":1}\ndata: next\n\n",
                codec.encode(events.get(0)));
    }

    @Test
    void rejectsAnOversizedEvent() {
        SseEventCodec.Parser parser = new SseEventCodec(8).parser();
        assertThrows(AgentTransportException.class, () -> parser.feed("data: 123456789\n"));
    }

    @Test
    void encodesNormalizedGatewayEventWithSseMetadata() {
        GatewayEvent event = new GatewayEvent(GatewayEvent.Type.TASK_STATUS, "tenant-a", "gw-task",
                Map.of("result", "ok"), Instant.now(), Map.of("sseEventId", "e-2"));
        SseEvent encoded = new GatewayEventSseEncoder().encode(event);
        assertEquals("e-2", encoded.id());
        assertEquals("task-status", encoded.event());
        assertEquals("{\"result\":\"ok\"}", encoded.data());
    }

}
