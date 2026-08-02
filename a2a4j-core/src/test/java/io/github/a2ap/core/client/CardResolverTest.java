/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.a2ap.core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.Test;

class CardResolverTest {

    @Test
    void resolvesOfficialSdkCard() {
        AgentCard card = new MockCardResolver().resolveCard();
        assertNotNull(card);
        assertEquals("Mock Agent", card.name());
        assertEquals("https://mock.com/agent", card.url());
        assertTrue(card.capabilities().streaming());
        assertEquals("mock-skill", card.skills().get(0).id());
    }

    @Test
    void resolvesCustomOfficialSdkCard() {
        AgentCard card = new CustomCardResolver("custom-id", "Custom Agent").resolveCard();
        assertEquals("Custom Agent", card.name());
        assertEquals("2.0.0", card.version());
    }

    @Test
    void allowsResolverToReturnNull() {
        assertNull(new NullCardResolver().resolveCard());
    }

    private static final class MockCardResolver implements CardResolver {

        @Override
        public AgentCard resolveCard() {
            return AgentCard.builder().name("Mock Agent").description("A mock agent for testing")
                    .url("https://mock.com/agent").version("1.0.0")
                    .capabilities(new AgentCapabilities(true, false, false, List.of()))
                    .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text"))
                    .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "https://mock.com/agent", null, "1.0")))
                    .skills(List.of(AgentSkill.builder().id("mock-skill").name("Mock skill")
                            .description("A mock skill").tags(List.of()).examples(List.of())
                            .inputModes(List.of("text")).outputModes(List.of("text")).build()))
                    .build();
        }

    }

    private static final class CustomCardResolver implements CardResolver {

        private final String id;
        private final String name;

        private CustomCardResolver(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public AgentCard resolveCard() {
            return AgentCard.builder().name(name).description("A custom agent for testing")
                    .url("https://custom.com/agent").version("2.0.0")
                    .capabilities(new AgentCapabilities(false, false, false, List.of()))
                    .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text"))
                    .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "https://custom.com/agent", null, "1.0")))
                    .skills(List.of(AgentSkill.builder().id(id).name(name).description("A custom skill")
                            .tags(List.of()).examples(List.of())
                            .inputModes(List.of("text")).outputModes(List.of("text")).build()))
                    .build();
        }

    }

    private static final class NullCardResolver implements CardResolver {

        @Override
        public AgentCard resolveCard() {
            return null;
        }

    }

}
