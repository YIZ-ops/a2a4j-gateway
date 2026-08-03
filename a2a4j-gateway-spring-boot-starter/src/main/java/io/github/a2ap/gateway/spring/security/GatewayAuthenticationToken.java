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

package io.github.a2ap.gateway.spring.security;

import io.github.a2ap.gateway.api.model.PrincipalContext;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Spring Security authentication whose principal is the gateway-owned context, not a raw token. */
public final class GatewayAuthenticationToken extends AbstractAuthenticationToken {

    private final PrincipalContext principalContext;

    /** Creates an authenticated gateway token from a trusted principal context. */
    public GatewayAuthenticationToken(PrincipalContext principalContext) {
        super(principalContext.authorities().stream().map(SimpleGrantedAuthority::new).toList());
        this.principalContext = principalContext;
        super.setAuthenticated(true);
    }

    /** Returns the immutable gateway principal context. */
    public PrincipalContext principalContext() {
        return principalContext;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return principalContext;
    }

    @Override
    public String getName() {
        return principalContext.subject();
    }

    @Override
    public String toString() {
        return "GatewayAuthenticationToken[tenantId=" + principalContext.tenantId()
                + ", subject=" + principalContext.subject() + ", authorities="
                + List.copyOf(principalContext.authorities()) + "]";
    }

}
