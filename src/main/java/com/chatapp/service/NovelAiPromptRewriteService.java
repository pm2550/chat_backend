package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.BotConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class NovelAiPromptRewriteService {
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
    private static final int MAX_PROMPT_LENGTH = 1000;
    private static final String SYSTEM_PROMPT = """
            You adapt Chinese image requests into a single high-quality English prompt for NovelAI Diffusion.
            Preserve every explicit subject, count, appearance, action, relationship, pose, clothing or exposure
            level, setting, camera direction, mood, and requested style. Never sanitize, euphemize, omit, reverse,
            or contradict those details. Do not add clothing or censorship. Do not infer or add a young age.
            You may add compatible composition, lighting, color, camera, texture, and aesthetic details to improve
            visual quality. Prefer concise NovelAI-friendly English tags plus short natural-language composition.
            Return only the final prompt, with no heading, explanation, quotes, or Markdown.
            """;

    private final LLMService llmService;

    public String rewriteIfNeeded(BotConfig bot, String prompt) {
        if (prompt == null || prompt.isBlank()
                || bot == null
                || bot.getImageGenerationProvider() != BotConfig.ImageGenerationProvider.NOVELAI
                || bot.getImagePromptMode() == BotConfig.ImagePromptMode.VERBATIM
                || !HAN.matcher(prompt).find()) {
            return prompt;
        }
        try {
            BotDto.LLMResponse response = llmService.chat(bot, List.of(
                    new BotDto.ChatMessage("system", SYSTEM_PROMPT),
                    new BotDto.ChatMessage("user", prompt.trim())));
            String rewritten = clean(response != null ? response.getContent() : null);
            if (rewritten.isBlank()) {
                log.warn("NovelAI prompt rewrite returned empty output for bot={}; using source prompt", bot.getId());
                return prompt;
            }
            log.info("NovelAI prompt rewritten bot={} sourceChars={} outputChars={}",
                    bot.getId(), prompt.length(), rewritten.length());
            return rewritten;
        } catch (RuntimeException error) {
            log.warn("NovelAI prompt rewrite failed for bot={}; using source prompt: {}",
                    bot.getId(), error.getMessage());
            return prompt;
        }
    }

    private String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```[a-zA-Z]*\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }
        value = value.replaceFirst("(?i)^prompt\\s*:\\s*", "").trim();
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value.length() <= MAX_PROMPT_LENGTH
                ? value
                : value.substring(0, MAX_PROMPT_LENGTH).trim();
    }
}
