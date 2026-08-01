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

package io.github.a2ap.core.protocol.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2AProtocolV1ContractTest {

    private static final String PROTO_SHA256 =
            "1A8915C1B9FBA11A27E8138469658FEDE0838D6B1BBEB3D40EC659DFABE7426E";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void validatesPinnedAgentCardFixture() throws IOException {
        JsonNode card = readJson("a2a/v1/agent-card.json");

        A2AProtocolV1Validator.validateAgentCard(card);

        assertEquals(2, card.get("supportedInterfaces").size());
        assertEquals(A2AProtocolV1.VERSION,
                card.get("supportedInterfaces").get(0).get("protocolVersion").asText());
    }

    @Test
    void validatesAllMvpJsonRpcOperationFixtures() throws IOException {
        List<String> fixtures = List.of(
                "jsonrpc-send-message.json",
                "jsonrpc-send-streaming-message.json",
                "jsonrpc-get-task.json",
                "jsonrpc-list-tasks.json",
                "jsonrpc-cancel-task.json",
                "jsonrpc-subscribe-task.json");

        for (String fixture : fixtures) {
            A2AProtocolV1Validator.validateJsonRpcRequest(readJson("a2a/v1/" + fixture));
        }

        assertTrue(A2AProtocolV1.JSON_RPC_METHODS.contains("SendMessage"));
        assertTrue(A2AProtocolV1.JSON_RPC_METHODS.contains("SubscribeToTask"));
    }

    @Test
    void rejectsLegacyCardShapeAndUnknownMethod() throws IOException {
        ObjectNode legacyCard = (ObjectNode) readJson("a2a/v1/agent-card.json");
        legacyCard.remove("supportedInterfaces");
        assertThrows(IllegalArgumentException.class,
                () -> A2AProtocolV1Validator.validateAgentCard(legacyCard));

        ObjectNode unknownMethod = (ObjectNode) readJson("a2a/v1/jsonrpc-send-message.json");
        unknownMethod.put("method", "message/send");
        assertThrows(IllegalArgumentException.class,
                () -> A2AProtocolV1Validator.validateJsonRpcRequest(unknownMethod));
    }

    @Test
    void validatesCheckedInProtoAgainstItsLockFile() throws IOException {
        byte[] proto = readPinnedProto();
        String actualHash = sha256(proto);

        assertEquals(PROTO_SHA256, actualHash.toUpperCase());
    }

    private static JsonNode readJson(String resource) throws IOException {
        return OBJECT_MAPPER.readTree(readClasspathResource(resource));
    }

    private static byte[] readClasspathResource(String resource) throws IOException {
        try (InputStream input = A2AProtocolV1ContractTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + resource);
            }
            return input.readAllBytes();
        }
    }

    private static byte[] readPinnedProto() throws IOException {
        List<Path> candidates = List.of(
                Path.of("specification", "a2a.proto"),
                Path.of("..", "specification", "a2a.proto"));
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return Files.readAllBytes(candidate);
            }
        }
        throw new IOException("Missing checked-in specification/a2a.proto");
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JDK", ex);
        }
    }

}
