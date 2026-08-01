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
import reactor.core.publisher.Mono;

/** Resolves an outbound credential without exposing inbound caller tokens. */
public interface CredentialProvider {

    /** Resolves a configured credential reference for an Agent instance. */
    Mono<OutboundCredentials> resolve(String tenantId, String credentialRef, AgentInstance target);

}
