package com.chatapp.config;

import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component("llm")
@RequiredArgsConstructor
public class LlmHealthIndicator implements HealthIndicator {

    @Value("${llm.required:false}")
    private boolean required;

    @Value("${llm.openai.api-key:}")
    private String openaiApiKey;

    @Value("${llm.claude.api-key:}")
    private String claudeApiKey;

    @Value("${llm.deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${llm.ollama.base-url:}")
    private String ollamaBaseUrl;

    @Value("${llm.hermes.api-key:}")
    private String hermesApiKey;

    @Value("${llm.hermes.base-url:}")
    private String hermesBaseUrl;

    @Override
    public Health health() {
        boolean openaiConfigured = hasText(openaiApiKey);
        boolean claudeConfigured = hasText(claudeApiKey);
        boolean deepseekConfigured = hasText(deepseekApiKey);
        boolean ollamaConfigured = hasText(ollamaBaseUrl);
        boolean hermesConfigured = hasText(hermesApiKey) && hasText(hermesBaseUrl);
        Health.Builder builder;
        boolean ollamaReachable = true;
        if (ollamaConfigured) {
            ollamaReachable = probeOllama();
        }

        boolean availableProvider = openaiConfigured
                || claudeConfigured
                || deepseekConfigured
                || (ollamaConfigured && ollamaReachable)
                || hermesConfigured;

        if (required && !availableProvider) {
            builder = Health.down();
        } else {
            builder = Health.up();
        }

        return builder
                .withDetail("required", required)
                .withDetail("openaiConfigured", openaiConfigured)
                .withDetail("claudeConfigured", claudeConfigured)
                .withDetail("deepseekConfigured", deepseekConfigured)
                .withDetail("ollamaConfigured", ollamaConfigured)
                .withDetail("ollamaReachable", ollamaReachable)
                .withDetail("hermesConfigured", hermesConfigured)
                .build();
    }

    private boolean probeOllama() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(1500, TimeUnit.MILLISECONDS)
                .build();
        String url = ollamaBaseUrl.replaceAll("/+$", "") + "/api/tags";
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (IOException e) {
            return false;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
