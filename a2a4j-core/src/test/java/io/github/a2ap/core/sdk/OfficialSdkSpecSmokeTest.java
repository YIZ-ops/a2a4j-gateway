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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P0 probe that locks the official SDK model names and wire codec used by the migration. */
class OfficialSdkSpecSmokeTest {

    @Test
    void readsAgentCardFixtureWithOfficialModel() throws Exception {
        AgentCard card = JsonUtil.fromJson(read("a2a/v1/agent-card.json"), AgentCard.class);

        assertEquals("Gateway Test Agent", card.name());
        assertEquals(2, card.supportedInterfaces().size());
        assertEquals("JSONRPC", card.supportedInterfaces().get(0).protocolBinding());
        assertEquals("echo", card.skills().get(0).id());
        assertEquals(1, card.skills().get(0).inputModes().size());
        assertEquals("text/plain", card.skills().get(0).inputModes().get(0));
    }

    @Test
    void readsMessageSendParamsWithOfficialModel() throws Exception {
        JsonObject request = JsonParser.parseString(read("a2a/v1/jsonrpc-send-message.json")).getAsJsonObject();
        MessageSendParams params = JsonUtil.fromJson(request.get("params").toString(), MessageSendParams.class);

        assertEquals("message-1", params.message().messageId());
        assertEquals(1, params.message().parts().size());
        assertInstanceOf(org.a2aproject.sdk.spec.TextPart.class, params.message().parts().get(0));
    }

    @Test
    void serializesOfficialModelThroughSdkCodec() throws Exception {
        AgentCard card = JsonUtil.fromJson(read("a2a/v1/agent-card.json"), AgentCard.class);

        String json = JsonUtil.toJson(card);

        assertNotNull(JsonParser.parseString(json).getAsJsonObject().get("supportedInterfaces"));
        assertTrue(json.contains("\"protocolBinding\":\"JSONRPC\""));
    }

    private static String read(String resource) throws IOException {
        try (InputStream stream = OfficialSdkSpecSmokeTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Missing test resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
