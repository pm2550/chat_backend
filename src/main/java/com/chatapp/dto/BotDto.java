package com.chatapp.dto;

import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoomBot;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BotDto {

    private Long id;
    private String botName;
    private String botAvatar;
    private BotConfig.LLMProvider llmProvider;
    private String modelName;
    private String systemPrompt;
    private Double temperature;
    private Integer maxTokens;
    private Boolean isActive;
    private Long providerCredentialId;
    private String providerCredentialLabel;
    private String providerCredentialLast4;
    private Boolean hasCredential;
    private LocalDateTime createdAt;
    private ChatRoomBot.TriggerMode triggerMode;
    private String triggerKeywords;
    private String roomNickname;
    private String roomPromptSuffix;
    private Boolean enabledInRoom;
    private Boolean hasCharacterCard;
    private String characterPersona;
    private String characterScenario;
    private String characterFirstMes;
    private List<String> characterAlternateGreetings;
    private Integer characterBookEntryCount;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CharacterCardImportRequest {
        @NotNull(message = "角色卡不能为空")
        private Map<String, Object> card;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @NotBlank(message = "机器人名称不能为空")
        private String botName;
        private String botAvatar;

        @NotNull(message = "LLM提供者不能为空")
        private BotConfig.LLMProvider llmProvider;

        private String apiKey;
        private Long providerCredentialId;
        private String modelName;
        private String systemPrompt;
        private Double temperature;
        private Integer maxTokens;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String botName;
        private String botAvatar;
        private String apiKey;
        private Long providerCredentialId;
        private String modelName;
        private String systemPrompt;
        private Double temperature;
        private Integer maxTokens;
        private Boolean isActive;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddToChatRoomRequest {
        private ChatRoomBot.TriggerMode triggerMode;
        private String triggerKeywords;
        private String roomNickname;
        private String roomPromptSuffix;
        private Boolean enabledInRoom;
    }

    @Data
    @NoArgsConstructor
    public static class ChatMessage {
        private String role; // "system", "user", "assistant", "tool"
        private String content;
        private String toolCallId;
        private String name;
        private List<ToolCall> toolCalls;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public ChatMessage(String role, String content, String toolCallId, String name, List<ToolCall> toolCalls) {
            this.role = role;
            this.content = content;
            this.toolCallId = toolCallId;
            this.name = name;
            this.toolCalls = toolCalls;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCall {
        private String id;
        private String name;
        private String argumentsJson;
    }

    @Data
    @NoArgsConstructor
    public static class LLMResponse {
        private String content;
        private Integer tokensUsed;
        private String model;
        private List<ToolCall> toolCalls;

        public LLMResponse(String content, Integer tokensUsed, String model) {
            this.content = content;
            this.tokensUsed = tokensUsed;
            this.model = model;
        }

        public LLMResponse(String content, Integer tokensUsed, String model, List<ToolCall> toolCalls) {
            this.content = content;
            this.tokensUsed = tokensUsed;
            this.model = model;
            this.toolCalls = toolCalls;
        }
    }
}
