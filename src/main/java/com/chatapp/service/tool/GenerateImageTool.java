package com.chatapp.service.tool;

import com.chatapp.dto.ImageGenerationDto;
import com.chatapp.dto.MessageDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomBot;
import com.chatapp.entity.User;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.ImageGenerationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class GenerateImageTool implements Tool {
    private final ImageGenerationService imageGenerationService;
    private final BotConfigRepository botConfigRepository;
    private final ChatRoomBotRepository chatRoomBotRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public GenerateImageTool(
            @Lazy ImageGenerationService imageGenerationService,
            BotConfigRepository botConfigRepository,
            ChatRoomBotRepository chatRoomBotRepository,
            ChatRoomRepository chatRoomRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper) {
        this.imageGenerationService = imageGenerationService;
        this.botConfigRepository = botConfigRepository;
        this.chatRoomBotRepository = chatRoomBotRepository;
        this.chatRoomRepository = chatRoomRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "generate_image";
    }

    @Override
    public String description() {
        return "Generate an AI image in the current PM chat room. Use this only when the user asks you to draw, "
                + "create an illustration, make an image, or otherwise produce visual artwork. "
                + "The tool charges the initiating user's AI image points and posts an image-generation message "
                + "as this bot in the room.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("prompt")
                .put("type", "string")
                .put("description", "The visual prompt to draw. Preserve the user's subject and important details.");
        properties.putObject("size")
                .put("type", "string")
                .put("description", "Optional output size, for example 1024*1024, 1024*1536, or 1536*1024.");
        properties.putObject("ratio")
                .put("type", "string")
                .put("description", "Optional aspect ratio. Supported hints: 1:1, 3:4, 4:3, 9:16, 16:9.");
        properties.putObject("expand")
                .put("type", "boolean")
                .put("description", "For the platform Hermes provider only: whether Grok should expand the prompt before drawing. Default true.");
        schema.putArray("required").add("prompt");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params, ToolContext context) {
        if (context == null || context.roomId() == null) {
            return ToolErrors.node(objectMapper, "no_room", "generate_image requires a current room");
        }
        if (context.userId() == null) {
            return ToolErrors.node(objectMapper, "no_user", "generate_image requires an initiating user");
        }
        if (context.botConfigId() == null) {
            return ToolErrors.node(objectMapper, "no_bot", "generate_image must be called by a configured bot");
        }

        String prompt = params.path("prompt").asText("").trim();
        if (prompt.isBlank()) {
            return ToolErrors.node(objectMapper, "invalid_params", "prompt is required");
        }

        BotConfig bot = botConfigRepository.findById(context.botConfigId())
                .orElseThrow(() -> new ToolExecutionException("no_bot", "bot not found"));
        ChatRoomBot roomBot = chatRoomBotRepository
                .findByChatRoomIdAndBotConfigId(context.roomId(), context.botConfigId())
                .orElseThrow(() -> new ToolExecutionException("bot_not_in_room", "bot is not bound to this room"));
        ChatRoom room = chatRoomRepository.findById(context.roomId())
                .orElseThrow(() -> new ToolExecutionException("no_room", "room not found"));

        Long senderId = bot.getCreatedBy() != null
                ? bot.getCreatedBy().getId()
                : room.getCreatedBy().getId();
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new ToolExecutionException("no_sender", "bot sender user not found"));

        ImageGenerationDto.GenerateRequest request = new ImageGenerationDto.GenerateRequest(
                context.roomId(),
                prompt,
                1,
                resolveSize(params),
                params.has("expand") && !params.path("expand").isNull()
                        ? params.path("expand").asBoolean()
                        : true);
        ImageGenerationDto.GenerateResponse response = imageGenerationService.submitAsBot(
                context.userId(),
                sender,
                bot,
                roomDisplayName(roomBot),
                request);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("submitted", true);
        root.put("messageId", response.getMessageId());
        root.put("status", response.getStatus() != null ? response.getStatus().name() : "QUEUED");
        root.put("pointsCharged", response.getPointsCharged() == null ? 0 : response.getPointsCharged());
        root.put("prompt", prompt);
        MessageDto message = response.getMessage();
        if (message != null) {
            root.put("botName", message.getBotName() == null ? roomDisplayName(roomBot) : message.getBotName());
            root.put("roomId", message.getChatRoomId());
        }
        root.put("note", "The image generation message has been posted. It will update in-place when drawing finishes.");
        return root;
    }

    private String resolveSize(JsonNode params) {
        String size = params.path("size").asText("").trim();
        if (!size.isBlank()) {
            return size;
        }
        String ratio = params.path("ratio").asText("1:1").trim();
        return switch (ratio) {
            case "3:4", "768:1024" -> "1024*1536";
            case "4:3", "1024:768" -> "1536*1024";
            case "9:16" -> "1024*1792";
            case "16:9" -> "1792*1024";
            default -> "1024*1024";
        };
    }

    private String roomDisplayName(ChatRoomBot roomBot) {
        if (roomBot.getRoomNickname() != null && !roomBot.getRoomNickname().isBlank()) {
            return roomBot.getRoomNickname();
        }
        return roomBot.getBotConfig().getBotName();
    }
}
