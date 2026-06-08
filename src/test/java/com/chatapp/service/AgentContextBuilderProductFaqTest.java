package com.chatapp.service;

import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentContextBuilderProductFaqTest {
    private AgentContextBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new AgentContextBuilder(
                mock(MessageRepository.class),
                mock(ChatRoomRepository.class),
                mock(MemoryService.class),
                mock(AgentVisionAttachmentService.class));
    }

    @Test
    void configuredProductFaqIsIncludedInSystemPrompt() {
        ReflectionTestUtils.setField(builder, "productFaq",
                "PM chat supports points, files, workspaces, and Agent tools.");

        String prompt = builder.assembleSystemPrompt(envelope("Base identity", null));

        assertTrue(prompt.contains("[PM CHAT PRODUCT FAQ]"));
        assertTrue(prompt.contains("PM chat supports points"));
    }

    @Test
    void emptyProductFaqIsOmitted() {
        ReflectionTestUtils.setField(builder, "productFaq", "   ");

        String prompt = builder.assembleSystemPrompt(envelope("Base identity", null));

        assertFalse(prompt.contains("[PM CHAT PRODUCT FAQ]"));
    }

    @Test
    void productFaqAppearsBeforeBaseSystemPrompt() {
        ReflectionTestUtils.setField(builder, "productFaq", "PM chat product reference.");

        String prompt = builder.assembleSystemPrompt(envelope("Base identity marker", null));

        assertTrue(prompt.indexOf("PM chat product reference.") < prompt.indexOf("Base identity marker"));
    }

    @Test
    void productFaqIsPrependedToTemplateSystemPrompt() {
        ReflectionTestUtils.setField(builder, "productFaq", "PM chat product reference.");

        String prompt = builder.assembleSystemPrompt(envelope("Base identity", "Template task={{task}}"));

        assertTrue(prompt.startsWith("[PM CHAT PRODUCT FAQ]"));
        assertTrue(prompt.contains("Template task=help"));
    }

    private AgentContextBuilder.AgentContextEnvelope envelope(String basePrompt, String template) {
        return new AgentContextBuilder.AgentContextEnvelope(
                new AgentContextBuilder.AgentIdentity("Agent", "", basePrompt, template),
                new AgentContextBuilder.RoomMetadata(true, "Room", "", 1, List.of("Alice"), null, true),
                List.of(),
                new AgentContextBuilder.InitiatorInfo("Alice", "member", false),
                List.of("Be concise"),
                "help",
                6000,
                20);
    }
}
