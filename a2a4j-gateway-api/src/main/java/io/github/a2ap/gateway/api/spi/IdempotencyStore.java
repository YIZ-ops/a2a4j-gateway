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

import io.github.a2ap.gateway.api.model.GatewayResult;
import io.github.a2ap.gateway.api.model.IdempotencyRecord;
import reactor.core.publisher.Mono;

/** Asynchronous boundary for idempotent task creation and safe outcome replay. */
public interface IdempotencyStore {

    /** Finds a tenant-scoped idempotency record. */
    Mono<IdempotencyRecord> find(String tenantId, String key);

    /** Atomically reserves a key for a request hash. */
    Mono<IdempotencyRecord> begin(String tenantId, String key, String requestHash);

    /** Stores a replayable result for a reserved key. */
    Mono<Void> complete(String tenantId, String key, GatewayResult result);

    /** Marks a key as outcome-unknown so automatic retry is prevented. */
    Mono<Void> markOutcomeUnknown(String tenantId, String key);

}
