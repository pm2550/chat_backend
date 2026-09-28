package com.chatapp.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 默认画图渠道（聊天里画图、AI 助手画图、渠道设为默认的机器人）：
 * image-generation.provider=pixai 走 PixAI，否则走 Hermes（Grok 扩写 + 出图）。
 */
@Component
@Primary
public class ConfiguredImageGenerationClient implements ImageGenerationClient {
    private final HermesImageGenerationClient hermes;
    private final PixaiImageGenerationClient pixai;
    private final String provider;

    public ConfiguredImageGenerationClient(HermesImageGenerationClient hermes,
                                           PixaiImageGenerationClient pixai,
                                           @Value("${image-generation.provider:hermes}") String provider) {
        this.hermes = hermes;
        this.pixai = pixai;
        this.provider = provider == null ? "hermes" : provider.trim().toLowerCase(Locale.ROOT);
    }

    ImageGenerationClient active() {
        return "pixai".equals(provider) ? pixai : hermes;
    }

    @Override
    public SubmitResult submit(String apiKey, String prompt, int count, String size) {
        return active().submit(apiKey, prompt, count, size);
    }

    @Override
    public SubmitResult submit(String apiKey, String prompt, int count, String size, boolean expand) {
        return active().submit(apiKey, prompt, count, size, expand);
    }

    @Override
    public PollResult poll(String apiKey, String taskId) {
        return active().poll(apiKey, taskId);
    }

    @Override
    public byte[] download(String imageUrl) {
        return active().download(imageUrl);
    }
}
