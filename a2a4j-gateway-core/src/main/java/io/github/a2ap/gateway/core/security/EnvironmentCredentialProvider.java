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

import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.OutboundCredentials;
import io.github.a2ap.gateway.api.spi.CredentialProvider;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * Resolves only {@code env://NAME} credential references and never exposes the secret in errors.
 */
public final class EnvironmentCredentialProvider implements CredentialProvider {

    private final Function<String, String> environment;

    /** Creates a provider backed by the process environment. */
    public EnvironmentCredentialProvider() {
        this(System::getenv);
    }

    /** Creates a provider with an injectable environment lookup for tests and controlled runtimes. */
    public EnvironmentCredentialProvider(Function<String, String> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /** Creates a provider backed by a fixed, immutable environment map. */
    public EnvironmentCredentialProvider(Map<String, String> environment) {
        this(Objects.requireNonNull(environment, "environment")::get);
    }

    @Override
    public Mono<OutboundCredentials> resolve(String tenantId, String credentialRef, AgentInstance target) {
        if (credentialRef == null || credentialRef.isBlank() || !credentialRef.startsWith("env://")) {
            return Mono.error(new IllegalArgumentException("credentialRef must use env:// scheme"));
        }
        String name = credentialRef.substring("env://".length());
        if (name.isBlank() || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return Mono.error(new IllegalArgumentException("credentialRef contains an invalid environment name"));
        }
        String value = environment.apply(name);
        if (value == null || value.isBlank()) {
            return Mono.error(new IllegalArgumentException("configured credential is unavailable"));
        }
        return Mono.just(new OutboundCredentials("Bearer", value));
    }

}
