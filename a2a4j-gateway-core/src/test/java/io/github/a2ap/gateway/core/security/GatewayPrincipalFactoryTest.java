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

package io.github.a2ap.gateway.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.a2ap.gateway.api.model.PrincipalContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayPrincipalFactoryTest {

    @Test
    void mapsTrustedTenantSubjectAndAuthoritiesWithoutUsingTokenValue() {
        GatewayPrincipalFactory factory = new GatewayPrincipalFactory();
        PrincipalContext principal = factory.fromClaims(Map.of("tenant_id", "tenant-a", "sub", "user-a",
                "iss", "https://issuer.example.test", "scope", "agent:discover agent:invoke:agent-a",
                "roles", List.of("task:read")));

        assertEquals("tenant-a", principal.tenantId());
        assertTrue(principal.authorities().contains("agent:discover"));
        assertTrue(principal.authorities().contains("task:read"));
        assertEquals(64, principal.fingerprint().length());
        assertTrue(!principal.fingerprint().contains("token"));
    }

    @Test
    void rejectsUntrustedMissingTenantClaim() {
        assertThrows(IllegalArgumentException.class, () -> new GatewayPrincipalFactory().fromClaims(
                Map.of("sub", "user-a")));
    }

}
