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

package io.github.a2ap.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PartA2aV1SerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesA2aV1TextPart() {
        Map<String, Object> params = Map.of("message", Map.of("role", "ROLE_USER",
                "parts", List.of(Map.of("text", "hello"))));

        MessageSendParams decoded = objectMapper.convertValue(params, MessageSendParams.class);

        TextPart part = assertInstanceOf(TextPart.class, decoded.getMessage().getParts().get(0));
        assertEquals("hello", part.getText());
    }

    @Test
    void serializesA2aV1TextPart() throws Exception {
        String json = objectMapper.writeValueAsString(new TextPart("hello"));
        String messageJson = objectMapper.writeValueAsString(Message.builder().role("ROLE_USER")
                .parts(List.of(new TextPart("hello"))).build());

        assertFalse(json.contains("\"kind\""), json);
        assertFalse(messageJson.contains("\"kind\""), messageJson);
    }

}
