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

import io.github.a2ap.core.protocol.v1.A2AProtocolV1;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

/** Reactor Netty Agent Card fetcher with timeout, URL, DNS and response-size guards. */
public final class ReactorNettyAgentCardFetcher implements AgentCardFetcher {

    private final AgentCardUrlPolicy urlPolicy;

    private final Duration timeout;

    /** Creates a fetcher with a controlled URL policy and timeout. */
    public ReactorNettyAgentCardFetcher(AgentCardUrlPolicy urlPolicy, Duration timeout) {
        this.urlPolicy = Objects.requireNonNull(urlPolicy, "urlPolicy");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public Mono<String> fetch(String cardUrl) {
        return Mono.fromCallable(() -> urlPolicy.validateConfiguredUrl(cardUrl))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(uri -> fetchResolved(uri));
    }

    private Mono<String> fetchResolved(URI uri) {
        return Mono.fromCallable(() -> {
            urlPolicy.validateResolved(uri);
            return uri;
        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(this::request)
                .timeout(timeout);
    }

    private Mono<String> request(URI uri) {
        return HttpClient.create()
                .responseTimeout(timeout)
                .followRedirect(false)
                .headers(headers -> {
                    headers.set("A2A-Version", A2AProtocolV1.VERSION);
                    headers.set("Accept", "application/json");
                })
                .get()
                .uri(uri.toString())
                .responseSingle((response, content) -> {
                    if (response.status().code() < 200 || response.status().code() >= 300) {
                        return content.asByteArray().then(Mono.error(new IllegalArgumentException(
                                "Agent Card endpoint returned HTTP " + response.status().code())));
                    }
                    String contentLength = response.responseHeaders().get("Content-Length");
                    if (contentLength != null) {
                        try {
                            if (Long.parseLong(contentLength) > urlPolicy.maxResponseBytes()) {
                                return content.asByteArray().then(Mono.error(new IllegalArgumentException(
                                        "Agent Card exceeds configured response size")));
                            }
                        }
                        catch (NumberFormatException ignored) {
                            // Fall through to the byte-count check below.
                        }
                    }
                    return content.asByteArray().flatMap(bytes -> {
                        if (bytes.length > urlPolicy.maxResponseBytes()) {
                            return Mono.error(new IllegalArgumentException(
                                    "Agent Card exceeds configured response size"));
                        }
                        return Mono.just(new String(bytes, StandardCharsets.UTF_8));
                    });
                });
    }

}
