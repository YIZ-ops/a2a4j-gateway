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
import java.util.Objects;

/** Internal API-key entry; its string representation never includes the secret. */
public record ApiKeyCredential(String keyId, String secret, PrincipalContext principal) {

    /** Creates a validated API-key credential. */
    public ApiKeyCredential {
        if (keyId == null || keyId.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("API-key id and secret must not be blank");
        }
        Objects.requireNonNull(principal, "principal");
    }

    @Override
    public String toString() {
        return "ApiKeyCredential[keyId=" + keyId + ", secret=[REDACTED], principal="
                + principal.subject() + "]";
    }

}
