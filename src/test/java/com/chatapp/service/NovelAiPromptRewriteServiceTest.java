package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.BotConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NovelAiPromptRewriteServiceTest {
    private LLMService llmService;
    private NovelAiPromptRewriteService service;
    private BotConfig bot;

    @BeforeEach
    void setUp() {
        llmService = mock(LLMService.class);
        service = new NovelAiPromptRewriteService(llmService);
        bot = new BotConfig();
        bot.setId(96L);
        bot.setImageGenerationProvider(BotConfig.ImageGenerationProvider.NOVELAI);
        bot.setImagePromptMode(BotConfig.ImagePromptMode.FAITHFUL_CREATIVE);
    }

    @Test
    void rewritesChineseToFaithfulCreativeEnglishPrompt() {
        when(llmService.chat(org.mockito.ArgumentMatchers.eq(bot), anyList()))
                .thenReturn(new BotDto.LLMResponse(
                        "Prompt: 1girl, silver hair, dynamic composition, cinematic lighting",
                        32,
                        "grok-4.3"));

        String result = service.rewriteIfNeeded(bot, "银发少女站在雨夜街头");

        assertThat(result).isEqualTo(
                "1girl, silver hair, dynamic composition, cinematic lighting");
    }

    @Test
    void leavesEnglishPromptVerbatim() {
        String prompt = "1girl, silver hair, cinematic lighting";
        assertThat(service.rewriteIfNeeded(bot, prompt)).isEqualTo(prompt);
        verify(llmService, never()).chat(org.mockito.ArgumentMatchers.any(), anyList());
    }

    @Test
    void respectsVerbatimModeForChinese() {
        bot.setImagePromptMode(BotConfig.ImagePromptMode.VERBATIM);
        assertThat(service.rewriteIfNeeded(bot, "银发少女")).isEqualTo("银发少女");
        verify(llmService, never()).chat(org.mockito.ArgumentMatchers.any(), anyList());
    }

    @Test
    void fallsBackToSourcePromptWhenRewriteFails() {
        when(llmService.chat(org.mockito.ArgumentMatchers.eq(bot), anyList()))
                .thenThrow(new IllegalStateException("provider unavailable"));
        assertThat(service.rewriteIfNeeded(bot, "银发少女")).isEqualTo("银发少女");
    }
}
