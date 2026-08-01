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

package io.github.a2ap.gateway.api.model;

/** Immutable protocol binding descriptor used by adapters and selectors. */
public record ProtocolDescriptor(String protocolBinding, String protocolVersion,
        String mediaType, boolean streaming) {

    /** Creates a validated protocol descriptor. */
    public ProtocolDescriptor {
        requireText(protocolBinding, "protocolBinding");
        requireText(protocolVersion, "protocolVersion");
        requireText(mediaType, "mediaType");
    }

    /** Returns the A2A 1.0 JSON-RPC descriptor. */
    public static ProtocolDescriptor jsonRpc() {
        return new ProtocolDescriptor("JSONRPC", "1.0", "application/json", false);
    }

    /** Returns the A2A 1.0 JSON-RPC descriptor for an SSE response stream. */
    public static ProtocolDescriptor jsonRpcStreaming() {
        return new ProtocolDescriptor("JSONRPC", "1.0", "text/event-stream", true);
    }

    /** Returns the A2A 1.0 HTTP+JSON descriptor. */
    public static ProtocolDescriptor httpJson(boolean streaming) {
        return new ProtocolDescriptor("HTTP+JSON", "1.0",
                streaming ? "text/event-stream" : "application/json", streaming);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
