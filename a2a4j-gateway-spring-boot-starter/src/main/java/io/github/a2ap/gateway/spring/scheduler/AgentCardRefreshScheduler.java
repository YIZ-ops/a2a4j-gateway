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

package io.github.a2ap.gateway.spring.scheduler;

import io.github.a2ap.gateway.spring.autoconfigure.GatewayProperties;
import io.github.a2ap.gateway.api.model.AgentRegistration;
import io.github.a2ap.gateway.core.discovery.AgentCardProbe;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Schedules initial and periodic Agent Card refreshes outside Reactor event loops. */
public final class AgentCardRefreshScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentCardRefreshScheduler.class);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "a2a-gateway-card-refresh");
        thread.setDaemon(true);
        return thread;
    });

    private final List<AgentRegistration> registrations;

    private final AgentCardProbe probe;

    /** Starts the refresh loop for the configured registrations. */
    public AgentCardRefreshScheduler(GatewayProperties properties, AgentCardProbe probe) {
        this.registrations = List.copyOf(properties.toRegistrations());
        this.probe = probe;
        Duration interval = properties.getRefreshInterval();
        executor.scheduleWithFixedDelay(this::refreshAll, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void refreshAll() {
        for (AgentRegistration registration : registrations) {
            try {
                probe.refresh(registration).block();
            }
            catch (RuntimeException ex) {
                log.warn("Agent Card refresh failed for tenant={} agent={}: {}",
                        registration.tenantId(), registration.agentId(), ex.getMessage());
            }
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

}
