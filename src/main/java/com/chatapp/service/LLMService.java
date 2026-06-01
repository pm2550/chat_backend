package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.BotConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class LLMService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ProviderCredentialService providerCredentialService;

    @Value("${llm.openai.api-key:}")
    private String openaiApiKey;
    @Value("${llm.openai.base-url:https://api.openai.com/v1}")
    private String openaiBaseUrl;
    @Value("${llm.openai.model:gpt-4o}")
    private String openaiModel;

    @Value("${llm.claude.api-key:}")
    private String claudeApiKey;
    @Value("${llm.claude.base-url:https://api.anthropic.com/v1}")
    private String claudeBaseUrl;
    @Value("${llm.claude.model:claude-sonnet-4-20250514}")
    private String claudeModel;

    @Value("${llm.deepseek.api-key:}")
    private String deepseekApiKey;
    @Value("${llm.deepseek.base-url:https://api.deepseek.com/v1}")
    private String deepseekBaseUrl;
    @Value("${llm.deepseek.model:deepseek-chat}")
    private String deepseekModel;

    @Value("${llm.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;
    @Value("${llm.ollama.model:llama3}")
    private String ollamaModel;

    @Value("${llm.hermes.api-key:}")
    private String hermesApiKey;
    @Value("${llm.hermes.base-url:http://127.0.0.1:8642/v1}")
    private String hermesBaseUrl;
    @Value("${llm.hermes.model:hermes-agent}")
    private String hermesModel;

    public LLMService(ObjectMapper objectMapper, ProviderCredentialService providerCredentialService) {
        this.objectMapper = objectMapper;
        this.providerCredentialService = providerCredentialService;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public BotDto.LLMResponse chat(BotConfig botConfig, List<BotDto.ChatMessage> messages) {
        return switch (botConfig.getLlmProvider()) {
            case OPENAI -> callOpenAICompatible(
                    requireApiKey(resolveApiKey(botConfig, openaiApiKey), "OpenAI"),
                    openaiBaseUrl,
                    resolveModel(botConfig, openaiModel),
                    messages, botConfig);
            case CLAUDE -> callClaude(
                    requireApiKey(resolveApiKey(botConfig, claudeApiKey), "Claude"),
                    claudeBaseUrl,
                    resolveModel(botConfig, claudeModel),
                    messages, botConfig);
            case DEEPSEEK -> callOpenAICompatible(
                    requireApiKey(resolveApiKey(botConfig, deepseekApiKey), "DeepSeek"),
                    deepseekBaseUrl,
                    resolveModel(botConfig, deepseekModel),
                    messages, botConfig);
            case OLLAMA -> callOllama(
                    resolveModel(botConfig, ollamaModel),
                    messages, botConfig);
            case HERMES -> callOpenAICompatible(
                    requireApiKey(resolveApiKey(botConfig, hermesApiKey), "Hermes"),
                    hermesBaseUrl,
                    resolveModel(botConfig, hermesModel),
                    messages, botConfig);
            case DASHSCOPE -> throw new IllegalArgumentException("DashScope 当前仅用于图片生成凭据");
        };
    }

    private BotDto.LLMResponse callOpenAICompatible(String apiKey, String baseUrl, String model,
                                                     List<BotDto.ChatMessage> messages, BotConfig config) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("temperature", config.getTemperature() != null ? config.getTemperature() : 0.7);
            requestBody.put("max_tokens", config.getMaxTokens() != null ? config.getMaxTokens() : 2048);

            ArrayNode messagesArray = requestBody.putArray("messages");
            for (BotDto.ChatMessage msg : messages) {
                ObjectNode msgNode = messagesArray.addObject();
                msgNode.put("role", msg.getRole());
                msgNode.put("content", msg.getContent());
            }

            Request request = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(objectMapper.writeValueAsString(requestBody),
                            MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                    log.error("OpenAI API error: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("LLM API调用失败: " + response.code());
                }

                JsonNode responseJson = objectMapper.readTree(response.body().string());
                String content = responseJson.path("choices").path(0).path("message").path("content").asText();
                int tokens = responseJson.path("usage").path("total_tokens").asInt(0);

                BotDto.LLMResponse llmResponse = new BotDto.LLMResponse();
                llmResponse.setContent(content);
                llmResponse.setTokensUsed(tokens);
                llmResponse.setModel(model);
                return llmResponse;
            }
        } catch (IOException e) {
            log.error("LLM API call failed: {}", e.getMessage());
            throw new RuntimeException("LLM服务调用失败", e);
        }
    }

    private BotDto.LLMResponse callClaude(String apiKey, String baseUrl, String model,
                                           List<BotDto.ChatMessage> messages, BotConfig config) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("max_tokens", config.getMaxTokens() != null ? config.getMaxTokens() : 2048);

            // Extract system message if present
            String systemMessage = null;
            ArrayNode messagesArray = requestBody.putArray("messages");
            for (BotDto.ChatMessage msg : messages) {
                if ("system".equals(msg.getRole())) {
                    systemMessage = msg.getContent();
                } else {
                    ObjectNode msgNode = messagesArray.addObject();
                    msgNode.put("role", msg.getRole());
                    msgNode.put("content", msg.getContent());
                }
            }
            if (systemMessage != null) {
                requestBody.put("system", systemMessage);
            }

            Request request = new Request.Builder()
                    .url(baseUrl + "/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(objectMapper.writeValueAsString(requestBody),
                            MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                    log.error("Claude API error: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("Claude API调用失败: " + response.code());
                }

                JsonNode responseJson = objectMapper.readTree(response.body().string());
                String content = responseJson.path("content").path(0).path("text").asText();
                int inputTokens = responseJson.path("usage").path("input_tokens").asInt(0);
                int outputTokens = responseJson.path("usage").path("output_tokens").asInt(0);

                BotDto.LLMResponse llmResponse = new BotDto.LLMResponse();
                llmResponse.setContent(content);
                llmResponse.setTokensUsed(inputTokens + outputTokens);
                llmResponse.setModel(model);
                return llmResponse;
            }
        } catch (IOException e) {
            log.error("Claude API call failed: {}", e.getMessage());
            throw new RuntimeException("Claude服务调用失败", e);
        }
    }

    private BotDto.LLMResponse callOllama(String model, List<BotDto.ChatMessage> messages, BotConfig config) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("stream", false);

            ArrayNode messagesArray = requestBody.putArray("messages");
            for (BotDto.ChatMessage msg : messages) {
                ObjectNode msgNode = messagesArray.addObject();
                msgNode.put("role", msg.getRole());
                msgNode.put("content", msg.getContent());
            }

            ObjectNode options = requestBody.putObject("options");
            options.put("temperature", config.getTemperature() != null ? config.getTemperature() : 0.7);

            Request request = new Request.Builder()
                    .url(ollamaBaseUrl + "/api/chat")
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(objectMapper.writeValueAsString(requestBody),
                            MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("Ollama API调用失败: " + response.code());
                }

                JsonNode responseJson = objectMapper.readTree(response.body().string());
                String content = responseJson.path("message").path("content").asText();

                BotDto.LLMResponse llmResponse = new BotDto.LLMResponse();
                llmResponse.setContent(content);
                llmResponse.setTokensUsed(0);
                llmResponse.setModel(model);
                return llmResponse;
            }
        } catch (IOException e) {
            log.error("Ollama API call failed: {}", e.getMessage());
            throw new RuntimeException("Ollama服务调用失败", e);
        }
    }

    String resolveApiKey(BotConfig config, String defaultKey) {
        if (config.getProviderCredential() != null) {
            String credentialKey = providerCredentialService.decrypt(config.getProviderCredential());
            if (credentialKey != null && !credentialKey.isBlank()) {
                return credentialKey;
            }
        }
        if (config.getApiKeyEncrypted() != null && !config.getApiKeyEncrypted().isEmpty()) {
            return providerCredentialService.decryptLegacyBotKey(config.getApiKeyEncrypted());
        }
        return defaultKey;
    }

    private String requireApiKey(String apiKey, String provider) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(provider + " API key is not configured");
        }
        return apiKey;
    }

    private String resolveModel(BotConfig config, String defaultModel) {
        if (config.getModelName() != null && !config.getModelName().isEmpty()) {
            return config.getModelName();
        }
        return defaultModel;
    }
}
