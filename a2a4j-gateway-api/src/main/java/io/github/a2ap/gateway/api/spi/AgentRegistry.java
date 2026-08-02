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
import io.github.a2ap.gateway.api.model.TargetHint;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Asynchronous source of tenant-scoped logical Agent definitions. */
public interface AgentRegistry {

    /** Lists enabled Agents that may be used for unauthenticated Card discovery. */
    default Flux<AgentDefinition> listAll() {
        return Flux.empty();
    }

    /** Lists Agents visible to a tenant and optional routing hint. */
    Flux<AgentDefinition> list(String tenantId, TargetHint targetHint);

    /** Resolves one logical Agent by tenant and stable gateway identifier. */
    Mono<AgentDefinition> get(String tenantId, String agentId);

    /** Finds logical Agents that advertise a normalized skill. */
    Flux<AgentDefinition> findBySkill(String tenantId, String skillId);

}
