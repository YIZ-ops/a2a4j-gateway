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

package io.github.a2ap.gateway.api.spi;

import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.OutboundCredentials;
import io.github.a2ap.gateway.api.model.OutboundRequest;
import io.github.a2ap.gateway.api.model.OutboundResponse;
import reactor.core.publisher.Flux;

/** Asynchronous network boundary for exchanging requests with an Agent instance. */
public interface AgentTransport {

    /** Exchanges one outbound request and emits response chunks in order. */
    Flux<OutboundResponse> exchange(AgentInstance target, OutboundRequest request,
            OutboundCredentials credentials);

    /**
     * Exchanges one outbound streaming request and emits response events in order.
     *
     * <p>The default keeps existing transports source-compatible by delegating to the
     * regular exchange method. Native transports should override this method to avoid
     * aggregating an SSE response.</p>
     */
    default Flux<OutboundResponse> exchangeStream(AgentInstance target, OutboundRequest request,
            OutboundCredentials credentials) {
        return exchange(target, request, credentials);
    }

}
