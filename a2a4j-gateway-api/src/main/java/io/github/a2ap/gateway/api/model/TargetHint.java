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

import java.util.Map;

/** Immutable deterministic routing hints supplied by an inbound request. */
public record TargetHint(String agentId, String skillId, Map<String, String> labels) {

    /** Creates an immutable target hint. */
    public TargetHint {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }

    /** Returns an empty hint that delegates selection to configured policy. */
    public static TargetHint empty() {
        return new TargetHint(null, null, Map.of());
    }

}
