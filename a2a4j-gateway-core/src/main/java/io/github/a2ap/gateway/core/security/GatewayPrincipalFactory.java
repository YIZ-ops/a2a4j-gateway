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

import io.github.a2ap.gateway.api.model.PrincipalContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HexFormat;

/** Builds a tenant-scoped principal from claims trusted by the authentication layer. */
public final class GatewayPrincipalFactory {

    private final String tenantClaim;

    private final String subjectClaim;

    private final List<String> authorityClaims;

    /** Creates a principal factory with explicit claim names. */
    public GatewayPrincipalFactory(String tenantClaim, String subjectClaim, Collection<String> authorityClaims) {
        this.tenantClaim = requireText(tenantClaim, "tenantClaim");
        this.subjectClaim = requireText(subjectClaim, "subjectClaim");
        this.authorityClaims = authorityClaims == null ? List.of() : authorityClaims.stream()
                .map(value -> requireText(value, "authorityClaim"))
                .toList();
    }

    /** Creates a principal factory using the standard tenant, subject and scope claims. */
    public GatewayPrincipalFactory() {
        this("tenant_id", "sub", List.of("scope", "scp", "roles", "authorities"));
    }

    /** Creates an immutable principal context from a trusted claim set. */
    public PrincipalContext fromClaims(Map<String, Object> claims) {
        Objects.requireNonNull(claims, "claims");
        String tenantId = textClaim(claims, tenantClaim);
        String subject = textClaim(claims, subjectClaim);
        Set<String> authorities = new LinkedHashSet<>();
        for (String claimName : authorityClaims) {
            appendAuthorities(authorities, claims.get(claimName));
        }
        Map<String, Object> safeClaims = claims.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> right));
        return new PrincipalContext(tenantId, subject, authorities, safeClaims, fingerprint(tenantId, subject, claims));
    }

    /** Creates a principal context with authorities supplied by a trusted authentication provider. */
    public PrincipalContext fromClaims(Map<String, Object> claims, Collection<String> trustedAuthorities) {
        PrincipalContext base = fromClaims(claims);
        Set<String> authorities = new LinkedHashSet<>(base.authorities());
        if (trustedAuthorities != null) {
            trustedAuthorities.stream().filter(Objects::nonNull).map(String::trim)
                    .filter(value -> !value.isBlank()).forEach(authorities::add);
        }
        return new PrincipalContext(base.tenantId(), base.subject(), authorities, base.claims(), base.fingerprint());
    }

    private void appendAuthorities(Set<String> authorities, Object value) {
        if (value instanceof String text) {
            for (String part : text.split("[\\s,]+")) {
                if (!part.isBlank()) {
                    authorities.add(part);
                }
            }
        }
        else if (value instanceof Collection<?> collection) {
            collection.stream().filter(Objects::nonNull).map(Object::toString).map(String::trim)
                    .filter(part -> !part.isBlank()).forEach(authorities::add);
        }
        else if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<String> values = new ArrayList<>();
            for (int index = 0; index < length; index++) {
                Object element = java.lang.reflect.Array.get(value, index);
                if (element != null) {
                    values.add(element.toString());
                }
            }
            appendAuthorities(authorities, values);
        }
    }

    private String textClaim(Map<String, Object> claims, String claimName) {
        Object value = claims.get(claimName);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("trusted claim " + claimName + " must not be blank");
        }
        return value.toString();
    }

    private String fingerprint(String tenantId, String subject, Map<String, Object> claims) {
        String issuer = String.valueOf(claims.getOrDefault("iss", ""));
        // jti identifies one token, not the stable owner of a gateway task. A refresh
        // must therefore keep the same resource-ownership fingerprint.
        String canonical = tenantId + "\u0000" + issuer + "\u0000" + subject;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JDK", ex);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

}
