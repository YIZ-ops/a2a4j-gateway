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

package io.github.a2ap.gateway.api.model;

import java.util.List;

/** Immutable page returned by a task route store. */
public record TaskRoutePage(List<TaskRoute> routes, String nextPageToken, int totalSize) {

    /** Preserves the original page shape for SPI callers. */
    public TaskRoutePage(List<TaskRoute> routes, String nextPageToken) {
        this(routes, nextPageToken, routes == null ? 0 : routes.size());
    }

    /** Creates a defensive copy of route results. */
    public TaskRoutePage {
        routes = routes == null ? List.of() : List.copyOf(routes);
        if (totalSize < 0) {
            throw new IllegalArgumentException("totalSize must not be negative");
        }
    }

}
