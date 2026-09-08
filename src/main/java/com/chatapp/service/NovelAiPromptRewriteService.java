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
    private static final String FAITHFUL_SYSTEM_PROMPT = """
            You adapt Chinese image requests into a single high-quality English prompt for NovelAI Diffusion.
            Preserve every explicit subject, count, appearance, action, relationship, pose, clothing or exposure
            level, setting, camera direction, mood, and requested style. Never sanitize, euphemize, omit, reverse,
            or contradict those details. Do not add clothing or censorship. Do not infer or add a young age.
            You may add compatible composition, lighting, color, camera, texture, and aesthetic details to improve
            visual quality. Prefer concise NovelAI-friendly English tags plus short natural-language composition.
            Return only the final prompt, with no heading, explanation, quotes, or Markdown.
            """;
    private static final String ANIME_SYSTEM_PROMPT = """
            You adapt an image request into a single high-quality English prompt for NovelAI Diffusion.
            Preserve every explicit subject, count, appearance, action, relationship, pose, clothing or exposure
            level, setting, camera direction, mood, and requested style. Never sanitize, euphemize, omit, reverse,
            or contradict those details. Do not add clothing or censorship. Do not infer or add a young age.

            Unless the source explicitly asks for photorealism, a real-person photo, live action, 3D, or another
            non-anime style, make the result unmistakably polished 2D anime artwork. Use compatible tags such as
            anime illustration, 2D, clean line art, anime coloring, cel shading, expressive eyes, very aesthetic,
            masterpiece, and no text. Add tasteful composition, lighting, color harmony, fabric detail, and a
            strong focal point. Do not add film grain, realistic skin texture, photographic language, or 3D-render
            language unless the source asks for it. Explicitly requested visual style always wins over this default.

            Return only the final NovelAI-friendly prompt, with no heading, explanation, quotes, or Markdown.
            """;

    private final LLMService llmService;

    public String rewriteIfNeeded(BotConfig bot, String prompt) {
        if (prompt == null || prompt.isBlank()
                || bot == null
                || bot.getImageGenerationProvider() != BotConfig.ImageGenerationProvider.NOVELAI
                || bot.getImagePromptMode() == BotConfig.ImagePromptMode.VERBATIM
                || (bot.getImagePromptMode() != BotConfig.ImagePromptMode.ANIME_CREATIVE
                    && !HAN.matcher(prompt).find())) {
            return prompt;
        }
        try {
            String systemPrompt = bot.getImagePromptMode() == BotConfig.ImagePromptMode.ANIME_CREATIVE
                    ? ANIME_SYSTEM_PROMPT
                    : FAITHFUL_SYSTEM_PROMPT;
            BotDto.LLMResponse response = llmService.chat(bot, List.of(
                    new BotDto.ChatMessage("system", systemPrompt),
                    new BotDto.ChatMessage("user", prompt.trim())));
            String rewritten = clean(response != null ? response.getContent() : null);
            if (rewritten.isBlank()) {
                return onRewriteFailure(bot, prompt, "returned empty output", null);
            }
            log.info("NovelAI prompt rewritten bot={} sourceChars={} outputChars={}",
                    bot.getId(), prompt.length(), rewritten.length());
            return rewritten;
        } catch (RuntimeException error) {
            return onRewriteFailure(bot, prompt, error.getMessage(), error);
        }
    }

    private String onRewriteFailure(BotConfig bot, String prompt, String reason, RuntimeException cause) {
        if (bot.getImageRewriteFailurePolicy() == BotConfig.ImageRewriteFailurePolicy.FAIL) {
            throw new IllegalStateException("图片提示词润色不可用: " + reason, cause);
        }
        log.warn("NovelAI prompt rewrite failed for bot={}; continuing with source prompt: {}",
                bot.getId(), reason);
        return prompt;
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
