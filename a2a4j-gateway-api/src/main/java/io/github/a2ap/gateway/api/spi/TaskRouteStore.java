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

import io.github.a2ap.gateway.api.model.TaskRoute;
import io.github.a2ap.gateway.api.model.TaskRoutePage;
import io.github.a2ap.gateway.api.model.TaskRouteQuery;
import java.time.Instant;
import reactor.core.publisher.Mono;

/** Asynchronous persistence boundary for gateway task affinity and lifecycle. */
public interface TaskRouteStore {

    /** Finds one route by tenant and gateway task id. */
    Mono<TaskRoute> find(String tenantId, String gatewayTaskId);

    /** Lists routes using tenant-scoped filters and bounded pagination. */
    Mono<TaskRoutePage> list(TaskRouteQuery query);

    /** Saves a new or updated route atomically. */
    Mono<Void> save(TaskRoute route);

    /** Extends route retention without changing its upstream affinity. */
    Mono<Void> touch(String tenantId, String gatewayTaskId, Instant expiresAt);

}
