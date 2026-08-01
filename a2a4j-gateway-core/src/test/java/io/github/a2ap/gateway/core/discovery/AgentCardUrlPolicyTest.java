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

package io.github.a2ap.gateway.core.discovery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentCardUrlPolicyTest {

    @Test
    void productionPolicyRequiresHttpsAndBlocksPrivateDestinations() {
        AgentCardUrlPolicy policy = AgentCardUrlPolicy.productionDefault();

        assertThrows(IllegalArgumentException.class, () -> policy.validateConfiguredUrl("http://agent.example.test/card"));
        assertThrows(IllegalArgumentException.class, () -> policy.validateConfiguredUrl("https://localhost/card"));
        assertThrows(IllegalArgumentException.class, () -> policy.validateResolved(URI.create("https://127.0.0.1/card")));
        assertDoesNotThrow(() -> policy.validateConfiguredUrl("https://agent.example.test/card"));
    }

    @Test
    void developmentPolicyCanAllowHttpAndAnExplicitCidr() {
        AgentCardUrlPolicy policy = new AgentCardUrlPolicy(true, false, Set.of("127.0.0.0/8"), 4096);

        assertDoesNotThrow(() -> policy.validateConfiguredUrl("http://127.0.0.1/card"));
        assertDoesNotThrow(() -> policy.validateResolved(URI.create("http://127.0.0.1/card")));
    }

}
