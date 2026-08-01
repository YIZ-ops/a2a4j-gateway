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

package io.github.a2ap.gateway.core.routing;

import io.github.a2ap.core.protocol.v1.A2AProtocolV1;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.ProtocolDescriptor;
import io.github.a2ap.gateway.api.spi.AgentInterfaceSelector;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/** Deterministically chooses the first supported A2A 1.0 interface advertised by an Agent. */
public final class DefaultAgentInterfaceSelector implements AgentInterfaceSelector {

    private final Map<String, Integer> bindingPriority;

    /** Creates a selector preferring JSON-RPC and falling back to HTTP+JSON. */
    public DefaultAgentInterfaceSelector() {
        this(List.of(A2AProtocolV1.JSON_RPC_BINDING, A2AProtocolV1.HTTP_JSON_BINDING));
    }

    /** Creates a selector with an explicit binding preference order. */
    public DefaultAgentInterfaceSelector(Collection<String> preferredBindings) {
        Objects.requireNonNull(preferredBindings, "preferredBindings");
        if (preferredBindings.isEmpty()) {
            throw new IllegalArgumentException("preferredBindings must not be empty");
        }
        this.bindingPriority = java.util.stream.IntStream.range(0, preferredBindings.size()).boxed()
                .collect(Collectors.toUnmodifiableMap(index -> preferredBindings.stream().toList().get(index),
                        Function.identity(), Math::min));
    }

    @Override
    public Mono<AgentInterface> choose(AgentInstance instance, ProtocolDescriptor inbound,
            GatewayCommand command) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(inbound, "inbound");
        Objects.requireNonNull(command, "command");
        return instance.interfaces().stream()
                .filter(candidate -> A2AProtocolV1.VERSION.equals(candidate.protocolVersion()))
                .filter(candidate -> bindingPriority.containsKey(candidate.protocolBinding()))
                .sorted(Comparator.comparingInt((AgentInterface candidate) ->
                        bindingPriority.get(candidate.protocolBinding())).thenComparing(AgentInterface::interfaceKey))
                .findFirst().map(Mono::just).orElseGet(Mono::empty);
    }

}
