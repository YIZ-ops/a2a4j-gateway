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
import io.github.a2ap.gateway.api.model.AuthorizationDecision;
import io.github.a2ap.gateway.api.model.GatewayCommand;
import io.github.a2ap.gateway.api.model.PrincipalContext;
import reactor.core.publisher.Mono;

/** Tenant-aware authorization boundary for Agent, Skill, and Task operations. */
public interface AuthorizationPolicy {

    /** Authorizes a principal to execute a command against a logical Agent. */
    Mono<AuthorizationDecision> authorize(PrincipalContext principal, GatewayCommand command,
            AgentDefinition agent);

}
