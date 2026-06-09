package com.chatapp.service.tool;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.Message;
import com.chatapp.repository.MessageRepository;
import com.chatapp.service.AgentVisionAttachmentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InspectRoomImageToolTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final AgentVisionAttachmentService visionAttachmentService = mock(AgentVisionAttachmentService.class);
    private final InspectRoomImageTool tool = new InspectRoomImageTool(
            messageRepository,
            visionAttachmentService,
            objectMapper);

    @Test
    void latestImageUsesCurrentRoomAndReturnsHiddenLlmAttachment() {
        Message image = imageMessage(123L, 10L);
        when(messageRepository.findFileMessagesInChatRoom(
                10L,
                Message.MessageType.IMAGE,
                PageRequest.of(0, 1)))
                .thenReturn(new PageImpl<>(List.of(image)));
        when(visionAttachmentService.isImageMessage(image)).thenReturn(true);
        when(visionAttachmentService.resolve(image, true))
                .thenReturn(new AgentVisionAttachmentService.ImageContext(
                        List.of(new BotDto.ImageAttachment(
                                "photo.jpg",
                                "image/jpeg",
                                "data:image/jpeg;base64,OK")),
                        "[图片: photo.jpg]",
                        false));

        JsonNode result = tool.execute(objectMapper.createObjectNode(), new ToolContext(10L, 42L, 77L, 99L));

        assertEquals(123L, result.path("messageId").asLong());
        assertTrue(result.path("image_selected_for_next_llm_call").asBoolean());
        assertEquals("data:image/jpeg;base64,OK", result.path("llm_image_attachment").path("dataUrl").asText());
        verify(visionAttachmentService).resolve(image, true);
    }

    @Test
    void messageIdOutsideCurrentRoomIsRejected() {
        Message image = imageMessage(123L, 11L);
        when(messageRepository.findWithSenderById(123L)).thenReturn(Optional.of(image));

        ToolExecutionException error = assertThrows(ToolExecutionException.class,
                () -> tool.execute(
                        objectMapper.createObjectNode().put("messageId", 123L),
                        new ToolContext(10L, 42L, 77L, 99L)));

        assertEquals("image_outside_room", error.getCode());
    }

    @Test
    void oversizedImageReturnsToolErrorInsteadOfCrashingLoop() {
        Message image = imageMessage(123L, 10L);
        when(messageRepository.findWithSenderById(123L)).thenReturn(Optional.of(image));
        when(visionAttachmentService.isImageMessage(image)).thenReturn(true);
        when(visionAttachmentService.resolve(image, true))
                .thenReturn(new AgentVisionAttachmentService.ImageContext(
                        List.of(new BotDto.ImageAttachment(
                                "huge.jpg",
                                "image/jpeg",
                                "data:image/jpeg;base64," + "A".repeat(900_001))),
                        "[图片: huge.jpg]",
                        false));

        JsonNode result = tool.execute(
                objectMapper.createObjectNode().put("messageId", 123L),
                new ToolContext(10L, 42L, 77L, 99L));

        assertEquals("image_too_large", result.path("error").path("code").asText());
        assertTrue(result.path("llm_image_attachment").isMissingNode());
    }

    private Message imageMessage(Long id, Long roomId) {
        ChatRoom room = new ChatRoom();
        room.setId(roomId);
        Message message = new Message();
        message.setId(id);
        message.setChatRoom(room);
        message.setMessageType(Message.MessageType.IMAGE);
        message.setFileName("photo.jpg");
        message.setFileType("image/jpeg");
        message.setContent("photo.jpg");
        return message;
    }
}
