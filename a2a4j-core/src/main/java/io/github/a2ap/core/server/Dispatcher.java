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

package io.github.a2ap.core.server;

import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * Protocol-boundary dispatcher for JSON-RPC requests.
 *
 * <p>The envelope is deliberately represented as a map at this boundary. Core
 * domain contracts use the official A2A SDK types; JSON-RPC request/response
 * DTOs remain implementation details of the transport adapter.</p>
 */
public interface Dispatcher {

    /**
     * Dispatches a JSON-RPC request for synchronous processing.
     *
     * @param request The decoded JSON-RPC request envelope
     * @return A JSON-RPC response envelope containing the result or error
     */
    Map<String, Object> dispatch(Map<String, Object> request);

    /**
     * Dispatches a JSON-RPC request for streaming/asynchronous processing.
     * This method is used for operations that return multiple responses over time,
     * such as streaming updates or event subscriptions.
     *
     * @param request The decoded JSON-RPC request envelope
     * @return A Flux of JSON-RPC response envelopes for streaming results
     */
    Flux<Map<String, Object>> dispatchStream(Map<String, Object> request);
}
