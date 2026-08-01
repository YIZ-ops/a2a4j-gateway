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

/** Immutable normalized skill metadata used by routing and authorization. */
public record AgentSkillDefinition(String skillId, String name, String description, List<String> tags,
        List<String> inputModes, List<String> outputModes) {

    /** Creates a validated immutable skill definition. */
    public AgentSkillDefinition {
        requireText(skillId, "skillId");
        requireText(name, "name");
        requireText(description, "description");
        tags = tags == null ? List.of() : List.copyOf(tags);
        inputModes = inputModes == null ? List.of() : List.copyOf(inputModes);
        outputModes = outputModes == null ? List.of() : List.copyOf(outputModes);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
