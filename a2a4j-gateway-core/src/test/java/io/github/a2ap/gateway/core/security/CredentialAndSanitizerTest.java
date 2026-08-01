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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.OutboundCredentials;
import io.github.a2ap.gateway.api.model.PrincipalContext;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CredentialAndSanitizerTest {

    @Test
    void resolvesEnvironmentCredentialsWithoutLeakingSecret() {
        EnvironmentCredentialProvider provider = new EnvironmentCredentialProvider(Map.of("AGENT_TOKEN", "secret"));
        OutboundCredentials credentials = provider.resolve("tenant-a", "env://AGENT_TOKEN",
                new AgentInstance("instance-1", "https://agent.example.test/card", java.util.List.of(), 1, null,
                        AgentInstance.HealthStatus.HEALTHY, null, null)).block();
        assertTrue(credentials.toString().contains("[REDACTED]"));
        assertTrue(!credentials.toString().contains("secret"));
    }

    @Test
    void redactsCredentialHeadersAndAuthenticatesApiKeysConstantTime() {
        assertEquals("[REDACTED]", GatewaySecuritySanitizer.redactHeader("Authorization", "Bearer secret"));
        assertEquals("visible", GatewaySecuritySanitizer.redactHeader("X-Request-Id", "visible"));
        PrincipalContext principal = new PrincipalContext("tenant-a", "key-user", Set.of("agent:discover"),
                Map.of(), "fingerprint");
        ApiKeyAuthenticator authenticator = new ApiKeyAuthenticator(java.util.List.of(
                new ApiKeyCredential("key-1", "top-secret", principal)));
        assertEquals(principal, authenticator.authenticate("top-secret").block());
        assertEquals(null, authenticator.authenticate("wrong").block());
    }

}
