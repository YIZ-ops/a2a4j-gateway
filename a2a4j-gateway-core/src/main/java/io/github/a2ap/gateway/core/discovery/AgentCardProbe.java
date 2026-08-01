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

import io.github.a2ap.gateway.api.model.AgentDefinition;
import io.github.a2ap.gateway.api.model.AgentInstanceRegistration;
import io.github.a2ap.gateway.api.model.AgentRegistration;
import java.time.Instant;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Probes configured Agent Cards and atomically publishes complete logical-Agent snapshots. */
public final class AgentCardProbe {

    private final AgentCardFetcher fetcher;

    private final AgentCardNormalizer normalizer;

    private final InMemoryAgentRegistry registry;

    private final int unhealthyAfterFailures;

    /** Creates a card probe with a consecutive failure threshold. */
    public AgentCardProbe(AgentCardFetcher fetcher, AgentCardNormalizer normalizer,
            InMemoryAgentRegistry registry, int unhealthyAfterFailures) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.registry = Objects.requireNonNull(registry, "registry");
        if (unhealthyAfterFailures < 1) {
            throw new IllegalArgumentException("unhealthyAfterFailures must be positive");
        }
        this.unhealthyAfterFailures = unhealthyAfterFailures;
    }

    /** Fetches, validates and publishes one logical Agent snapshot. */
    public Mono<AgentDefinition> refresh(AgentRegistration registration) {
        return Flux.fromIterable(registration.instances())
                .flatMap(instance -> fetchOne(registration, instance))
                .collectMap(FetchedCard::instanceId, FetchedCard::json)
                .flatMap(cards -> {
                    if (cards.size() != registration.instances().size()) {
                        return Mono.error(new IllegalStateException("Agent Card refresh was incomplete"));
                    }
                    try {
                        AgentDefinition definition = normalizer.normalize(registration, cards, Instant.now());
                        registry.replace(definition);
                        return Mono.just(definition);
                    }
                    catch (RuntimeException error) {
                        cards.keySet().forEach(instanceId -> registry.recordProbeFailure(registration.tenantId(),
                                registration.agentId(), instanceId, unhealthyAfterFailures));
                        return Mono.error(error);
                    }
                });
    }

    private Mono<FetchedCard> fetchOne(AgentRegistration registration, AgentInstanceRegistration instance) {
        return fetcher.fetch(instance.cardUrl())
                .map(json -> new FetchedCard(instance.instanceId(), json))
                .doOnSuccess(value -> {
                    if (value != null) {
                        registry.recordProbeSuccess(registration.tenantId(), registration.agentId(),
                                instance.instanceId());
                    }
                })
                .onErrorResume(error -> {
                    registry.recordProbeFailure(registration.tenantId(), registration.agentId(),
                            instance.instanceId(), unhealthyAfterFailures);
                    return Mono.empty();
                });
    }

    private record FetchedCard(String instanceId, String json) {
    }

}
