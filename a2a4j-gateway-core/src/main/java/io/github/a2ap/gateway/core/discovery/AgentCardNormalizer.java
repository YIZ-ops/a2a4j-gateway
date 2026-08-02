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
import io.github.a2ap.gateway.api.model.AgentInstance;
import io.github.a2ap.gateway.api.model.AgentInstanceRegistration;
import io.github.a2ap.gateway.api.model.AgentInterface;
import io.github.a2ap.gateway.api.model.AgentRegistration;
import io.github.a2ap.gateway.api.model.AgentSkillDefinition;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentSkill;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Converts validated A2A 1.0 Agent Cards into gateway-owned immutable snapshots. */
public final class AgentCardNormalizer {

    private final AgentCardUrlPolicy urlPolicy;

    /** Creates a normalizer using the official SDK JSON codec and the supplied URL policy. */
    public AgentCardNormalizer(AgentCardUrlPolicy urlPolicy) {
        this.urlPolicy = Objects.requireNonNull(urlPolicy, "urlPolicy");
    }

    /** Creates a normalizer with the official SDK codec and production URL policy. */
    public AgentCardNormalizer() {
        this(AgentCardUrlPolicy.productionDefault());
    }

    /**
     * Normalizes one complete logical Agent snapshot.
     *
     * @param registration configured gateway facts
     * @param cardJsonByInstanceId fetched card JSON keyed by configured instance id
     * @param checkedAt time at which the snapshot was assembled
     * @return normalized immutable Agent definition
     */
    public AgentDefinition normalize(AgentRegistration registration, Map<String, String> cardJsonByInstanceId,
            Instant checkedAt) {
        Objects.requireNonNull(registration, "registration");
        if (cardJsonByInstanceId == null) {
            throw new IllegalArgumentException("cardJsonByInstanceId must not be null");
        }
        Instant effectiveCheckedAt = checkedAt == null ? Instant.now() : checkedAt;
        Map<String, AgentCard> cards = new LinkedHashMap<>();
        for (AgentInstanceRegistration instance : registration.instances()) {
            String cardJson = cardJsonByInstanceId.get(instance.instanceId());
            if (cardJson == null) {
                throw new IllegalArgumentException("missing Agent Card for " + instance.instanceId());
            }
            urlPolicy.validateConfiguredUrl(instance.cardUrl());
            try {
                AgentCard card = JsonUtil.fromJson(cardJson, AgentCard.class);
                cards.put(instance.instanceId(), card);
            }
            catch (Exception ex) {
                if (ex instanceof IllegalArgumentException illegalArgumentException) {
                    throw illegalArgumentException;
                }
                throw new IllegalArgumentException("invalid Agent Card for " + instance.instanceId(), ex);
            }
        }

        AgentCard firstCard = cards.values().iterator().next();
        String displayName = registration.configuredDisplayName();
        if (displayName.isBlank()) {
            displayName = firstCard.name();
        }
        List<AgentSkillDefinition> skills = normalizeSkills(firstCard.skills());
        List<AgentInstance> instances = new ArrayList<>();
        for (AgentInstanceRegistration configured : registration.instances()) {
            AgentCard card = cards.get(configured.instanceId());
            List<AgentInterface> interfaces = normalizeInterfaces(configured.instanceId(), card.supportedInterfaces(),
                    registration);
            instances.add(new AgentInstance(configured.instanceId(), configured.cardUrl(), interfaces,
                    configured.weight(), configured.credentialRef(), AgentInstance.HealthStatus.HEALTHY,
                    sha256(cardJsonByInstanceId.get(configured.instanceId())), effectiveCheckedAt));
        }
        return new AgentDefinition(registration.tenantId(), registration.agentId(), displayName,
                registration.enabled(), skills, registration.routingLabels(), registration.protocolPolicy(), instances);
    }

    private List<AgentInterface> normalizeInterfaces(String instanceId,
            List<org.a2aproject.sdk.spec.AgentInterface> interfaces,
            AgentRegistration registration) {
        List<AgentInterface> normalized = new ArrayList<>();
        for (org.a2aproject.sdk.spec.AgentInterface value : interfaces) {
            String binding = value.protocolBinding();
            String version = value.protocolVersion();
            if (!registration.protocolPolicy().allows(version, binding)) {
                throw new IllegalArgumentException("Agent Card interface is outside configured protocol policy");
            }
            String endpoint = value.url();
            urlPolicy.validateConfiguredUrl(endpoint);
            normalized.add(new AgentInterface(instanceId + "-" + binding, endpoint, binding, version,
                    value.tenant()));
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Agent Card must expose at least one supported interface");
        }
        return normalized;
    }

    private List<AgentSkillDefinition> normalizeSkills(List<AgentSkill> skills) {
        List<AgentSkillDefinition> normalized = new ArrayList<>();
        for (AgentSkill skill : skills) {
            normalized.add(new AgentSkillDefinition(skill.id(), skill.name(), skill.description(), skill.tags(),
                    skill.inputModes(), skill.outputModes()));
        }
        return normalized;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JDK", ex);
        }
    }

}
