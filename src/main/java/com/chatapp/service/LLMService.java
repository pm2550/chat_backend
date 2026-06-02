package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.service.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
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

    // DashScope chat is routed through an OpenAI-compatible endpoint (the owner's
    // self-hosted dashscope-proxy by default, which is keyless). DashScope image
    // generation is unaffected — it keeps using its own per-user credential path.
    @Value("${llm.dashscope.api-key:}")
    private String dashscopeApiKey;
    @Value("${llm.dashscope.base-url:http://localhost/dashscope/v1}")
    private String dashscopeBaseUrl;
    @Value("${llm.dashscope.model:qwen-plus}")
    private String dashscopeModel;

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
        return chat(botConfig, messages, List.of());
    }

    public BotDto.LLMResponse chat(BotConfig botConfig, List<BotDto.ChatMessage> messages, List<Tool> tools) {
        return switch (botConfig.getLlmProvider()) {
            case OPENAI -> callOpenAICompatible(
                    requireApiKey(resolveApiKey(botConfig, openaiApiKey), "OpenAI"),
                    resolveBaseUrl(botConfig, openaiBaseUrl),
                    resolveModel(botConfig, openaiModel),
                    messages, botConfig, tools);
            case CLAUDE -> callClaude(
                    requireApiKey(resolveApiKey(botConfig, claudeApiKey), "Claude"),
                    resolveBaseUrl(botConfig, claudeBaseUrl),
                    resolveModel(botConfig, claudeModel),
                    messages, botConfig, tools);
            case DEEPSEEK -> callOpenAICompatible(
                    requireApiKey(resolveApiKey(botConfig, deepseekApiKey), "DeepSeek"),
                    resolveBaseUrl(botConfig, deepseekBaseUrl),
                    resolveModel(botConfig, deepseekModel),
                    messages, botConfig, tools);
            case OLLAMA -> callOllama(
                    resolveBaseUrl(botConfig, ollamaBaseUrl),
                    resolveModel(botConfig, ollamaModel),
                    messages, botConfig, tools);
            case HERMES -> callOpenAICompatible(
                    requireApiKey(resolveApiKey(botConfig, hermesApiKey), "Hermes"),
                    resolveBaseUrl(botConfig, hermesBaseUrl),
                    resolveModel(botConfig, hermesModel),
                    messages, botConfig, tools);
            // DashScope chat goes through the OpenAI-compatible proxy. The proxy is
            // typically keyless, so a blank key is allowed (Authorization is omitted).
            case DASHSCOPE -> callOpenAICompatible(
                    resolveApiKey(botConfig, dashscopeApiKey),
                    resolveBaseUrl(botConfig, dashscopeBaseUrl),
                    resolveModel(botConfig, dashscopeModel),
                    messages, botConfig, tools);
        };
    }

    private BotDto.LLMResponse callOpenAICompatible(String apiKey, String baseUrl, String model,
                                                     List<BotDto.ChatMessage> messages, BotConfig config, List<Tool> tools) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("temperature", config.getTemperature() != null ? config.getTemperature() : 0.7);
            requestBody.put("max_tokens", config.getMaxTokens() != null ? config.getMaxTokens() : 2048);

            ArrayNode messagesArray = requestBody.putArray("messages");
            for (BotDto.ChatMessage msg : messages) {
                ObjectNode msgNode = messagesArray.addObject();
                msgNode.put("role", msg.getRole());
                if (msg.getContent() != null) {
                    msgNode.put("content", msg.getContent());
                } else {
                    msgNode.putNull("content");
                }
                if (msg.getToolCallId() != null) {
                    msgNode.put("tool_call_id", msg.getToolCallId());
                }
                if (msg.getName() != null) {
                    msgNode.put("name", msg.getName());
                }
                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    ArrayNode toolCalls = msgNode.putArray("tool_calls");
                    for (BotDto.ToolCall toolCall : msg.getToolCalls()) {
                        toolCalls.add(openAiToolCall(toolCall));
                    }
                }
            }

            if (tools != null && !tools.isEmpty()) {
                putToolDefinitions(requestBody, tools);
                requestBody.put("tool_choice", "auto");
            }

            Request.Builder requestBuilder = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(objectMapper.writeValueAsString(requestBody),
                            MediaType.parse("application/json")));
            if (apiKey != null && !apiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }
            Request request = requestBuilder.build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                    log.error("OpenAI API error: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("LLM API调用失败: " + response.code());
                }

                JsonNode responseJson = objectMapper.readTree(response.body().string());
                JsonNode messageNode = responseJson.path("choices").path(0).path("message");
                String content = messageNode.path("content").isMissingNode() || messageNode.path("content").isNull()
                        ? ""
                        : messageNode.path("content").asText();
                int tokens = responseJson.path("usage").path("total_tokens").asInt(0);

                BotDto.LLMResponse llmResponse = new BotDto.LLMResponse();
                llmResponse.setContent(content);
                llmResponse.setTokensUsed(tokens);
                llmResponse.setModel(model);
                llmResponse.setToolCalls(parseToolCalls(messageNode.path("tool_calls")));
                return llmResponse;
            }
        } catch (IOException e) {
            log.error("LLM API call failed: {}", e.getMessage());
            throw new RuntimeException("LLM服务调用失败", e);
        }
    }

    /**
     * Serializes the tool list into the OpenAI-style {@code tools} array shared by
     * the OpenAI-compatible providers and Ollama ({@code /api/chat} accepts the same shape).
     */
    private void putToolDefinitions(ObjectNode requestBody, List<Tool> tools) {
        ArrayNode toolsArray = requestBody.putArray("tools");
        for (Tool tool : tools) {
            ObjectNode toolNode = toolsArray.addObject();
            toolNode.put("type", "function");
            ObjectNode functionNode = toolNode.putObject("function");
            functionNode.put("name", tool.name());
            functionNode.put("description", tool.description());
            functionNode.set("parameters", tool.parametersSchema());
        }
    }

    private ObjectNode openAiToolCall(BotDto.ToolCall toolCall) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", toolCall.getId());
        node.put("type", "function");
        ObjectNode function = node.putObject("function");
        function.put("name", toolCall.getName());
        function.put("arguments", toolCall.getArgumentsJson() != null ? toolCall.getArgumentsJson() : "{}");
        return node;
    }

    private List<BotDto.ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
            return List.of();
        }
        ArrayList<BotDto.ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode toolCallNode : toolCallsNode) {
            JsonNode function = toolCallNode.path("function");
            toolCalls.add(new BotDto.ToolCall(
                    toolCallNode.path("id").asText(""),
                    function.path("name").asText(""),
                    function.path("arguments").asText("{}")));
        }
        return toolCalls;
    }

    private BotDto.LLMResponse callClaude(String apiKey, String baseUrl, String model,
                                           List<BotDto.ChatMessage> messages, BotConfig config, List<Tool> tools) {
        if (tools != null && !tools.isEmpty()) {
            throw new UnsupportedOperationException("Claude tool calls are not implemented in PM chat yet");
        }
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

    private BotDto.LLMResponse callOllama(String baseUrl, String model, List<BotDto.ChatMessage> messages, BotConfig config, List<Tool> tools) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("stream", false);

            ArrayNode messagesArray = requestBody.putArray("messages");
            for (BotDto.ChatMessage msg : messages) {
                ObjectNode msgNode = messagesArray.addObject();
                msgNode.put("role", msg.getRole());
                msgNode.put("content", msg.getContent() != null ? msg.getContent() : "");
                if (msg.getName() != null) {
                    // Ollama identifies the tool a result belongs to via tool_name.
                    msgNode.put("tool_name", msg.getName());
                }
                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    ArrayNode toolCalls = msgNode.putArray("tool_calls");
                    for (BotDto.ToolCall toolCall : msg.getToolCalls()) {
                        ObjectNode callNode = toolCalls.addObject();
                        ObjectNode function = callNode.putObject("function");
                        function.put("name", toolCall.getName());
                        // Ollama expects arguments as a JSON object, not a string.
                        function.set("arguments", parseArgumentsToNode(toolCall.getArgumentsJson()));
                    }
                }
            }

            if (tools != null && !tools.isEmpty()) {
                putToolDefinitions(requestBody, tools);
            }

            ObjectNode options = requestBody.putObject("options");
            options.put("temperature", config.getTemperature() != null ? config.getTemperature() : 0.7);

            Request request = new Request.Builder()
                    .url(baseUrl + "/api/chat")
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(objectMapper.writeValueAsString(requestBody),
                            MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("Ollama API调用失败: " + response.code());
                }

                JsonNode responseJson = objectMapper.readTree(response.body().string());
                JsonNode messageNode = responseJson.path("message");
                String content = messageNode.path("content").asText("");
                int tokens = responseJson.path("prompt_eval_count").asInt(0)
                        + responseJson.path("eval_count").asInt(0);

                BotDto.LLMResponse llmResponse = new BotDto.LLMResponse();
                llmResponse.setContent(content);
                llmResponse.setTokensUsed(tokens);
                llmResponse.setModel(model);
                llmResponse.setToolCalls(parseOllamaToolCalls(messageNode.path("tool_calls")));
                return llmResponse;
            }
        } catch (IOException e) {
            log.error("Ollama API call failed: {}", e.getMessage());
            throw new RuntimeException("Ollama服务调用失败", e);
        }
    }

    private JsonNode parseArgumentsToNode(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(argumentsJson);
        } catch (IOException e) {
            return objectMapper.createObjectNode();
        }
    }

    /**
     * Ollama returns tool_calls under {@code message.tool_calls} with an
     * {@code arguments} object (not a JSON string) and frequently without an id.
     * Normalize both into {@link BotDto.ToolCall}.
     */
    private List<BotDto.ToolCall> parseOllamaToolCalls(JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
            return List.of();
        }
        ArrayList<BotDto.ToolCall> toolCalls = new ArrayList<>();
        int index = 0;
        for (JsonNode toolCallNode : toolCallsNode) {
            JsonNode function = toolCallNode.path("function");
            JsonNode argumentsNode = function.path("arguments");
            String argumentsJson = argumentsNode.isMissingNode() || argumentsNode.isNull()
                    ? "{}"
                    : (argumentsNode.isTextual() ? argumentsNode.asText() : argumentsNode.toString());
            String id = toolCallNode.path("id").asText("");
            if (id.isBlank()) {
                id = "ollama-call-" + (++index);
            }
            toolCalls.add(new BotDto.ToolCall(
                    id,
                    function.path("name").asText(""),
                    argumentsJson));
        }
        return toolCalls;
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
        if (config.getProviderCredential() != null) {
            String override = config.getProviderCredential().getModelOverride();
            if (override != null && !override.isBlank()) {
                return override.trim();
            }
        }
        return defaultModel;
    }

    /**
     * A vault credential may carry a base_url that overrides the server default,
     * letting a user point an OpenAI-compatible key at any gateway (OpenRouter /
     * dashscope-proxy / Ollama). Falls back to the server-configured endpoint.
     */
    String resolveBaseUrl(BotConfig config, String defaultBaseUrl) {
        if (config.getProviderCredential() != null) {
            String baseUrl = config.getProviderCredential().getBaseUrl();
            if (baseUrl != null && !baseUrl.isBlank()) {
                return baseUrl.trim();
            }
        }
        return defaultBaseUrl;
    }
}
