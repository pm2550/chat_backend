package com.chatapp.service.tool;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.Message;
import com.chatapp.repository.MessageRepository;
import com.chatapp.service.AgentVisionAttachmentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class InspectRoomImageTool implements Tool {
    private static final int MAX_DATA_URL_CHARS = 900_000;

    private final MessageRepository messageRepository;
    private final AgentVisionAttachmentService visionAttachmentService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "inspect_room_image";
    }

    @Override
    public String description() {
        return "Inspect one image from the current room only when the user's request requires visual understanding. "
                + "Use messageId for a known image message, or latestIndex=1 for the newest room image. "
                + "Do not call this for text-only questions such as points, settings, or summaries.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("roomId")
                .put("type", "integer")
                .put("description", "Ignored; server forces current room.");
        properties.putObject("messageId")
                .put("type", "integer")
                .put("description", "Image message id to inspect.");
        properties.putObject("latestIndex")
                .put("type", "integer")
                .put("minimum", 1)
                .put("maximum", 20)
                .put("description", "1 = newest image in the current room, 2 = second newest, used when messageId is absent.");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params, ToolContext context) {
        Message message = resolveMessage(params, context);
        if (message == null) {
            throw new ToolExecutionException("image_not_found", "No image message was found in the current room.");
        }
        if (!visionAttachmentService.isImageMessage(message)) {
            throw new ToolExecutionException("not_image_message", "The selected message is not an image.");
        }

        AgentVisionAttachmentService.ImageContext image = visionAttachmentService.resolve(message, true);
        if (image.attachments().isEmpty()) {
            ObjectNode root = baseResult(message);
            ObjectNode error = root.putObject("error");
            error.put("code", image.damaged() ? "image_read_failed" : "image_unavailable");
            error.put("message", image.annotation());
            return root;
        }

        BotDto.ImageAttachment attachment = image.attachments().get(0);
        if (attachment.dataUrl() != null && attachment.dataUrl().length() > MAX_DATA_URL_CHARS) {
            ObjectNode root = baseResult(message);
            ObjectNode error = root.putObject("error");
            error.put("code", "image_too_large");
            error.put("message", "Image is too large for inline vision routing. Ask the user to resend a smaller image.");
            error.put("dataUrlChars", attachment.dataUrl().length());
            return root;
        }

        ObjectNode root = baseResult(message);
        root.put("image_selected_for_next_llm_call", true);
        ObjectNode payload = root.putObject("llm_image_attachment");
        payload.put("name", attachment.fileName());
        payload.put("mimeType", attachment.mediaType());
        payload.put("dataUrl", attachment.dataUrl());
        return root;
    }

    private Message resolveMessage(JsonNode params, ToolContext context) {
        long requestedId = params.path("messageId").asLong(0);
        if (requestedId > 0) {
            Message message = messageRepository.findWithSenderById(requestedId).orElse(null);
            if (message == null || Boolean.TRUE.equals(message.getIsDeleted())) {
                return null;
            }
            Long roomId = message.getChatRoom() != null ? message.getChatRoom().getId() : null;
            if (!Objects.equals(roomId, context.roomId())) {
                throw new ToolExecutionException("image_outside_room", "Image message does not belong to the current room.");
            }
            return message;
        }

        int latestIndex = Math.max(1, Math.min(params.path("latestIndex").asInt(1), 20));
        List<Message> images = messageRepository
                .findFileMessagesInChatRoom(context.roomId(), Message.MessageType.IMAGE, PageRequest.of(0, latestIndex))
                .getContent();
        if (images.size() < latestIndex) {
            return null;
        }
        return images.get(latestIndex - 1);
    }

    private ObjectNode baseResult(Message message) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("messageId", message.getId());
        root.put("sender", displayName(message));
        root.put("messageType", message.getMessageType() != null ? message.getMessageType().name() : "TEXT");
        root.put("content", message.getContent() != null ? message.getContent() : "");
        if (message.getFileName() != null) root.put("fileName", message.getFileName());
        if (message.getFileType() != null) root.put("fileType", message.getFileType());
        if (message.getCreatedAt() != null) root.put("timestamp", message.getCreatedAt().toString());
        return root;
    }

    private String displayName(Message message) {
        if (message.getBotDisplayName() != null && !message.getBotDisplayName().isBlank()) {
            return message.getBotDisplayName();
        }
        if (message.getBotConfig() != null && message.getBotConfig().getBotName() != null) {
            return message.getBotConfig().getBotName();
        }
        if (message.getAnonymousIdentity() != null && message.getAnonymousIdentity().getAnonymousName() != null) {
            return message.getAnonymousIdentity().getAnonymousName();
        }
        if (message.getSender() == null) {
            return "Unknown";
        }
        if (message.getSender().getDisplayName() != null && !message.getSender().getDisplayName().isBlank()) {
            return message.getSender().getDisplayName();
        }
        return message.getSender().getUsername();
    }
}
