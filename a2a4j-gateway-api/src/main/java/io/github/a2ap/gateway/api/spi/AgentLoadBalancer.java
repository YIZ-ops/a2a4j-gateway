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

import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.RoutingContext;
import reactor.core.publisher.Mono;

/** Asynchronous boundary for selecting an eligible Agent instance. */
public interface AgentLoadBalancer {

    /** Selects one instance without changing task affinity for existing tasks. */
    Mono<AgentInstance> choose(AgentDefinition agent, GatewayCommand command, RoutingContext context);

    /** Selects and reserves a specific instance for an existing sticky task route. */
    default Mono<AgentInstance> choosePinned(AgentDefinition agent, String instanceId,
            GatewayCommand command, RoutingContext context) {
        return choose(agent, command, context)
                .flatMap(instance -> instance.instanceId().equals(instanceId)
                        ? Mono.just(instance)
                        : Mono.defer(() -> {
                            release(agent, instance);
                            return Mono.error(new IllegalStateException("pinned Agent instance is unavailable"));
                        }));
    }

    /**
     * Releases the in-flight reservation made by {@link #choose} after completion, error or cancellation.
     *
     * @param agent logical Agent owning the instance
     * @param instance reserved instance
     */
    default void release(AgentDefinition agent, AgentInstance instance) {
        // Optional for stateless implementations; stateful implementations override this hook.
    }

}
