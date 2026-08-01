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
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Constant-time API-key authenticator for explicitly enabled development mode. */
public final class ApiKeyAuthenticator {

    private final List<ApiKeyCredential> credentials;

    /** Creates an authenticator with the supplied immutable credential entries. */
    public ApiKeyAuthenticator(List<ApiKeyCredential> credentials) {
        this.credentials = credentials == null ? List.of() : List.copyOf(credentials);
    }

    /** Authenticates a presented API key without revealing which configured key matched. */
    public Mono<PrincipalContext> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Mono.empty();
        }
        for (ApiKeyCredential credential : credentials) {
            if (MessageDigest.isEqual(presentedKey.getBytes(StandardCharsets.UTF_8),
                    credential.secret().getBytes(StandardCharsets.UTF_8))) {
                return Mono.just(Objects.requireNonNull(credential.principal(), "principal"));
            }
        }
        return Mono.empty();
    }

}
