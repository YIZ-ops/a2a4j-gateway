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
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.GatewayEvent;
import io.github.a2ap.gateway.api.model.InboundExchange;
import io.github.a2ap.gateway.api.model.OutboundRequest;
import io.github.a2ap.gateway.api.model.OutboundResponse;
import io.github.a2ap.gateway.api.model.ProtocolDescriptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Converts a concrete protocol binding to and from the protocol-neutral gateway model. */
public interface ProtocolAdapter {

    /** Returns the binding descriptor handled by this adapter. */
    ProtocolDescriptor descriptor();

    /** Decodes an inbound exchange into a protocol-neutral command. */
    Mono<GatewayCommand> decode(InboundExchange exchange);

    /** Encodes a command for a selected Agent instance. */
    Mono<OutboundRequest> encode(GatewayCommand command, AgentInstance target);

    /** Decodes one or more upstream response chunks into normalized events. */
    Flux<GatewayEvent> decodeResponse(OutboundResponse response);

}
