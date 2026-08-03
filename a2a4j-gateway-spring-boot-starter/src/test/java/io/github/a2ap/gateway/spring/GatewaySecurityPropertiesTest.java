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

package io.github.a2ap.gateway.spring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.a2ap.gateway.spring.autoconfigure.GatewaySecurityProperties;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GatewaySecurityPropertiesTest {

    @Test
    void keepsAuthenticationAndApiKeyDisabledByDefault() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();

        assertFalse(properties.isEnabled());
        assertFalse(properties.getApiKey().isEnabled());
        properties.validate();
    }

    @Test
    void validatesJwtIssuerAndAudienceConfiguration() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setEnabled(true);
        properties.setMode("jwt");
        properties.getJwt().setIssuerUri("https://issuer.example.test");
        properties.getJwt().setAudiences(Set.of("a2a-gateway"));

        properties.validate();
        properties.getJwt().setAudiences(Set.of());
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void validatesApiKeyMetadataButNeverRequiresASecretValueInProperties() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setEnabled(true);
        properties.setMode("api-key");
        properties.getApiKey().setEnabled(true);
        GatewaySecurityProperties.ApiKeyProperties.Entry entry = new GatewaySecurityProperties.ApiKeyProperties.Entry();
        entry.setKeyId("local");
        entry.setSecretEnv("A2A_LOCAL_KEY");
        entry.setTenantId("tenant-a");
        entry.setSubject("developer");
        entry.setAuthorities(Set.of("agent:discover"));
        properties.getApiKey().setEntries(List.of(entry));

        properties.validate();
        assertTrue(!properties.toString().contains("A2A_LOCAL_KEY"));
    }

}
