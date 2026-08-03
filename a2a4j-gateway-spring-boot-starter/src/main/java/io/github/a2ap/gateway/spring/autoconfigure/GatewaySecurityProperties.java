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

package io.github.a2ap.gateway.spring.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * YAML-bound inbound authentication and claim-mapping configuration.
 */
@ConfigurationProperties(prefix = "a2a.gateway.security")
public class GatewaySecurityProperties {

    private boolean enabled;

    private String mode = "jwt";

    private JwtProperties jwt = new JwtProperties();

    private ApiKeyProperties apiKey = new ApiKeyProperties();

    /** Returns whether gateway authentication auto-configuration is enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Sets whether gateway authentication auto-configuration is enabled. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the authentication mode, either {@code jwt} or {@code api-key}.
     */
    public String getMode() {
        return mode;
    }

    /** Sets the authentication mode. */
    public void setMode(String mode) {
        this.mode = mode;
    }

    /** Returns JWT/OIDC settings. */
    public JwtProperties getJwt() {
        return jwt;
    }

    /** Sets JWT/OIDC settings. */
    public void setJwt(JwtProperties jwt) {
        this.jwt = jwt == null ? new JwtProperties() : jwt;
    }

    /** Returns development API-key settings. */
    public ApiKeyProperties getApiKey() {
        return apiKey;
    }

    /** Sets development API-key settings. */
    public void setApiKey(ApiKeyProperties apiKey) {
        this.apiKey = apiKey == null ? new ApiKeyProperties() : apiKey;
    }

    /** Validates enabled authentication configuration without reading any secret value. */
    public void validate() {
        if (!enabled) {
            return;
        }
        String normalizedMode = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (!normalizedMode.equals("jwt") && !normalizedMode.equals("api-key")) {
            throw new IllegalArgumentException("a2a.gateway.security.mode must be jwt or api-key");
        }
        if (normalizedMode.equals("jwt")) {
            jwt.validate();
        }
        else {
            apiKey.validate();
        }
    }

    /** JWT issuer, audience and claim mapping settings. */
    public static class JwtProperties {

        private String issuerUri;

        private String jwkSetUri;

        private Set<String> audiences = new LinkedHashSet<>();

        private String tenantClaim = "tenant_id";

        private String subjectClaim = "sub";

        private List<String> authorityClaims = new ArrayList<>(List.of("scope", "scp", "roles", "authorities"));

        private Duration clockSkew = Duration.ofSeconds(30);

        /** Returns the OIDC issuer URI. */
        public String getIssuerUri() {
            return issuerUri;
        }

        /** Sets the OIDC issuer URI. */
        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        /** Returns the explicit JWK set URI, when issuer discovery is not used. */
        public String getJwkSetUri() {
            return jwkSetUri;
        }

        /** Sets the explicit JWK set URI. */
        public void setJwkSetUri(String jwkSetUri) {
            this.jwkSetUri = jwkSetUri;
        }

        /** Returns accepted audience values. */
        public Set<String> getAudiences() {
            return audiences;
        }

        /** Sets accepted audience values. */
        public void setAudiences(Set<String> audiences) {
            this.audiences = audiences == null ? new LinkedHashSet<>() : new LinkedHashSet<>(audiences);
        }

        /** Returns the trusted tenant claim name. */
        public String getTenantClaim() {
            return tenantClaim;
        }

        /** Sets the trusted tenant claim name. */
        public void setTenantClaim(String tenantClaim) {
            this.tenantClaim = tenantClaim;
        }

        /** Returns the trusted subject claim name. */
        public String getSubjectClaim() {
            return subjectClaim;
        }

        /** Sets the trusted subject claim name. */
        public void setSubjectClaim(String subjectClaim) {
            this.subjectClaim = subjectClaim;
        }

        /** Returns claim names that may contribute authorities. */
        public List<String> getAuthorityClaims() {
            return authorityClaims;
        }

        /** Sets claim names that may contribute authorities. */
        public void setAuthorityClaims(List<String> authorityClaims) {
            this.authorityClaims = authorityClaims == null ? new ArrayList<>() : new ArrayList<>(authorityClaims);
        }

        /** Returns accepted clock skew. */
        public Duration getClockSkew() {
            return clockSkew;
        }

        /** Sets accepted clock skew. */
        public void setClockSkew(Duration clockSkew) {
            this.clockSkew = clockSkew;
        }

        private void validate() {
            if ((issuerUri == null || issuerUri.isBlank()) && (jwkSetUri == null || jwkSetUri.isBlank())) {
                throw new IllegalArgumentException("JWT issuer-uri or jwk-set-uri must be configured");
            }
            if (audiences == null || audiences.isEmpty()) {
                throw new IllegalArgumentException("JWT audience must be configured");
            }
            if (tenantClaim == null || tenantClaim.isBlank() || subjectClaim == null || subjectClaim.isBlank()) {
                throw new IllegalArgumentException("JWT tenant and subject claims must be configured");
            }
            if (authorityClaims == null || authorityClaims.isEmpty()) {
                throw new IllegalArgumentException("JWT authority claims must not be empty");
            }
            if (clockSkew == null || clockSkew.isNegative()) {
                throw new IllegalArgumentException("JWT clock-skew must not be negative");
            }
        }
    }

    /** Development API-key settings. Disabled by default and never stores secret values. */
    public static class ApiKeyProperties {

        private boolean enabled;

        private String headerName = "X-A2A-API-Key";

        private List<Entry> entries = new ArrayList<>();

        /** Returns whether API-key authentication is enabled. */
        public boolean isEnabled() {
            return enabled;
        }

        /** Sets whether API-key authentication is enabled. */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** Returns the inbound API-key header name. */
        public String getHeaderName() {
            return headerName;
        }

        /** Sets the inbound API-key header name. */
        public void setHeaderName(String headerName) {
            this.headerName = headerName;
        }

        /** Returns configured API-key entries. */
        public List<Entry> getEntries() {
            return entries;
        }

        /** Sets configured API-key entries. */
        public void setEntries(List<Entry> entries) {
            this.entries = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
        }

        private void validate() {
            if (!enabled) {
                throw new IllegalArgumentException("API-key mode requires a2a.gateway.security.api-key.enabled=true");
            }
            if (headerName == null || headerName.isBlank()) {
                throw new IllegalArgumentException("API-key header name must not be blank");
            }
            if (entries == null || entries.isEmpty()) {
                throw new IllegalArgumentException("at least one API-key entry must be configured");
            }
            Set<String> ids = new LinkedHashSet<>();
            for (Entry entry : entries) {
                entry.validate();
                if (!ids.add(entry.keyId)) {
                    throw new IllegalArgumentException("duplicate API-key id");
                }
            }
        }

        /** API-key metadata; the secret is read from the named environment variable. */
        public static class Entry {

            private String keyId;

            private String secretEnv;

            private String tenantId;

            private String subject;

            private Set<String> authorities = new LinkedHashSet<>();

            /** Returns the non-secret key id. */
            public String getKeyId() {
                return keyId;
            }

            /** Sets the non-secret key id. */
            public void setKeyId(String keyId) {
                this.keyId = keyId;
            }

            /** Returns the environment variable name containing the secret. */
            public String getSecretEnv() {
                return secretEnv;
            }

            /** Sets the environment variable name containing the secret. */
            public void setSecretEnv(String secretEnv) {
                this.secretEnv = secretEnv;
            }

            /** Returns the tenant assigned to this key. */
            public String getTenantId() {
                return tenantId;
            }

            /** Sets the tenant assigned to this key. */
            public void setTenantId(String tenantId) {
                this.tenantId = tenantId;
            }

            /** Returns the subject assigned to this key. */
            public String getSubject() {
                return subject;
            }

            /** Sets the subject assigned to this key. */
            public void setSubject(String subject) {
                this.subject = subject;
            }

            /** Returns authorities assigned to this key. */
            public Set<String> getAuthorities() {
                return authorities;
            }

            /** Sets authorities assigned to this key. */
            public void setAuthorities(Set<String> authorities) {
                this.authorities = authorities == null ? new LinkedHashSet<>() : new LinkedHashSet<>(authorities);
            }

            private void validate() {
                if (keyId == null || keyId.isBlank() || secretEnv == null || secretEnv.isBlank()
                        || tenantId == null || tenantId.isBlank() || subject == null || subject.isBlank()) {
                    throw new IllegalArgumentException("API-key entry metadata must not be blank");
                }
                if (!secretEnv.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("API-key secret-env is invalid");
                }
            }
        }
    }

}
