package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.service.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class HermesProvider {
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${llm.hermes.chat-url:http://127.0.0.1:8765/chat}")
    private String chatUrl;

    @Value("${llm.hermes.vision-model:grok-4.3}")
    private String visionModel;

    public HermesProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(130, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public BotDto.LLMResponse chat(BotConfig config, List<BotDto.ChatMessage> messages, List<Tool> tools) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", visionModel);
            requestBody.put("temperature", config.getTemperature() != null ? config.getTemperature() : 0.7);
            requestBody.put("max_tokens", config.getMaxTokens() != null ? config.getMaxTokens() : 2048);

            ArrayNode messagesArray = requestBody.putArray("messages");
            for (BotDto.ChatMessage msg : messages) {
                ObjectNode msgNode = messagesArray.addObject();
                msgNode.put("role", msg.getRole());
                if (msg.getContent() == null) {
                    msgNode.putNull("content");
                } else {
                    msgNode.set("content", objectMapper.valueToTree(msg.getContent()));
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

            Request request = new Request.Builder()
                    .url(chatUrl)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(objectMapper.writeValueAsString(requestBody),
                            MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("Hermes /chat failed: {} - {}", response.code(), responseBody);
                    throw new RuntimeException("Hermes /chat failed: HTTP " + response.code() + " " + responseBody);
                }
                return parseResponse(responseBody);
            }
        } catch (IOException e) {
            throw new RuntimeException("Hermes /chat unreachable or returned invalid JSON", e);
        }
    }

    private BotDto.LLMResponse parseResponse(String responseBody) throws IOException {
        JsonNode responseJson = objectMapper.readTree(responseBody);
        JsonNode messageNode = responseJson.path("choices").path(0).path("message");
        BotDto.LLMResponse llmResponse = new BotDto.LLMResponse();
        llmResponse.setContent(messageNode.path("content").isMissingNode() || messageNode.path("content").isNull()
                ? ""
                : messageNode.path("content").asText());
        llmResponse.setTokensUsed(responseJson.path("usage").path("total_tokens").asInt(0));
        llmResponse.setModel(responseJson.path("model").asText(visionModel));
        llmResponse.setToolCalls(parseToolCalls(messageNode.path("tool_calls")));
        return llmResponse;
    }

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
}
