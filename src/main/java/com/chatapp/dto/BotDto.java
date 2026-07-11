package com.chatapp.dto;

import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoomBot;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    private Integer maxHistoryMessages;
    private Boolean includeRoomMetadata;
    private BotConfig.ReplyMode replyMode;
    private BotConfig.WorkflowMode workflowMode;
    private BotConfig.ImageGenerationProvider imageGenerationProvider;
    private Long imageProviderCredentialId;
    private String imageProviderCredentialLabel;
    private String imageProviderCredentialLast4;
    private Boolean hasImageProviderCredential;
    private String imageModel;
    private String imageNegativePrompt;
    private Boolean isActive;
    private Long providerCredentialId;
    private String providerCredentialLabel;
    private String providerCredentialLast4;
    private Boolean hasCredential;
    private Long createdById;
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
    private List<String> enabledTools;
    private BotConfig.AccessPolicy accessPolicy;
    private List<AllowedUser> allowedUsers;
    private String inboundTokenLast4;
    private List<String> inboundTokenScopes;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllowedUser {
        private Long id;
        private String username;
        private String displayName;
    }

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
        private Integer maxHistoryMessages;
        private Boolean includeRoomMetadata;
        private BotConfig.ReplyMode replyMode;
        private BotConfig.WorkflowMode workflowMode;
        private BotConfig.ImageGenerationProvider imageGenerationProvider;
        private Long imageProviderCredentialId;
        private String imageApiKey;
        private String imageBaseUrl;
        private String imageModel;
        private String imageNegativePrompt;
        private List<String> enabledTools;
        private BotConfig.AccessPolicy accessPolicy;
        private List<Long> allowedUserIds;
        private List<String> allowedUsernames;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String botName;
        private String botAvatar;
        private BotConfig.LLMProvider llmProvider;
        private String apiKey;
        private Long providerCredentialId;
        private String modelName;
        private String systemPrompt;
        private Double temperature;
        private Integer maxTokens;
        private Integer maxHistoryMessages;
        private Boolean includeRoomMetadata;
        private BotConfig.ReplyMode replyMode;
        private BotConfig.WorkflowMode workflowMode;
        private BotConfig.ImageGenerationProvider imageGenerationProvider;
        private Long imageProviderCredentialId;
        private String imageApiKey;
        private String imageBaseUrl;
        private String imageModel;
        private String imageNegativePrompt;
        private Boolean isActive;
        private List<String> enabledTools;
        private BotConfig.AccessPolicy accessPolicy;
        private List<Long> allowedUserIds;
        private List<String> allowedUsernames;
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
        private Object content;
        private String toolCallId;
        private String name;
        private List<ToolCall> toolCalls;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public ChatMessage(String role, Object content) {
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

        public ChatMessage(String role, Object content, String toolCallId, String name, List<ToolCall> toolCalls) {
            this.role = role;
            this.content = content;
            this.toolCallId = toolCallId;
            this.name = name;
            this.toolCalls = toolCalls;
        }

        public static ChatMessage userWithImages(String text, List<ImageAttachment> attachments) {
            if (attachments == null || attachments.isEmpty()) {
                return new ChatMessage("user", text);
            }
            java.util.ArrayList<ContentPart> parts = new java.util.ArrayList<>();
            if (text != null && !text.isBlank()) {
                parts.add(ContentPart.text(text));
            }
            for (ImageAttachment attachment : attachments) {
                if (attachment != null && attachment.dataUrl() != null && !attachment.dataUrl().isBlank()) {
                    parts.add(ContentPart.imageUrl(attachment.dataUrl()));
                }
            }
            return new ChatMessage("user", parts.isEmpty() ? text : parts);
        }

        public String textContent() {
            if (content == null) {
                return "";
            }
            if (content instanceof String text) {
                return text;
            }
            if (content instanceof List<?> parts) {
                StringBuilder text = new StringBuilder();
                for (Object part : parts) {
                    if (part instanceof ContentPart contentPart) {
                        if ("text".equals(contentPart.getType()) && contentPart.getText() != null) {
                            if (!text.isEmpty()) text.append(' ');
                            text.append(contentPart.getText());
                        } else if ("image_url".equals(contentPart.getType())) {
                            if (!text.isEmpty()) text.append(' ');
                            text.append("[image]");
                        }
                    }
                }
                return text.toString();
            }
            return String.valueOf(content);
        }

        public boolean hasImageContent() {
            if (!(content instanceof List<?> parts)) {
                return false;
            }
            for (Object part : parts) {
                if (part instanceof ContentPart contentPart && "image_url".equals(contentPart.getType())) {
                    return true;
                }
            }
            return false;
        }

        public List<String> imageDataUrls() {
            if (!(content instanceof List<?> parts)) {
                return List.of();
            }
            java.util.ArrayList<String> urls = new java.util.ArrayList<>();
            for (Object part : parts) {
                if (part instanceof ContentPart contentPart
                        && contentPart.getImageUrl() != null
                        && contentPart.getImageUrl().getUrl() != null) {
                    urls.add(contentPart.getImageUrl().getUrl());
                }
            }
            return urls;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentPart {
        private String type;
        private String text;
        @JsonProperty("image_url")
        private ImageUrlPart imageUrl;

        public static ContentPart text(String text) {
            return new ContentPart("text", text, null);
        }

        public static ContentPart imageUrl(String url) {
            return new ContentPart("image_url", null, new ImageUrlPart(url));
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageUrlPart {
        private String url;
    }

    public record ImageAttachment(String fileName, String mediaType, String dataUrl) {
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
