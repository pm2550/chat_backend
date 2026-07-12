package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.Message;
import com.chatapp.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotVisionAttachmentSelectorTest {
    private MessageRepository messageRepository;
    private AgentVisionAttachmentService visionService;
    private BotVisionAttachmentSelector selector;
    private BotConfig bot;

    @BeforeEach
    void setUp() {
        messageRepository = mock(MessageRepository.class);
        visionService = mock(AgentVisionAttachmentService.class);
        selector = new BotVisionAttachmentSelector(messageRepository, visionService);
        bot = new BotConfig();
        bot.setId(9L);
        bot.setLlmProvider(BotConfig.LLMProvider.OPENAI);
        bot.setVisionInputEnabled(true);
        bot.setHistoryImageInspectionEnabled(true);
    }

    @Test
    void directImageWinsWithoutHistoryQuery() {
        Message source = image(30L, 7L, "direct.png");
        when(visionService.isImageMessage(source)).thenReturn(true);
        when(visionService.resolve(source, true)).thenReturn(imageContext("direct.png"));

        BotVisionAttachmentSelector.Selection selected = selector.select(bot, 7L, source, "@Bot 看看");

        assertEquals(30L, selected.messageId());
        assertEquals("current_message", selected.reason());
        assertTrue(selected.image().attachments().size() == 1);
        verify(messageRepository, never()).findFileMessagesInChatRoom(any(), any(), any());
    }

    @Test
    void repliedImageWinsAndCannotCrossRooms() {
        Message replied = image(29L, 7L, "reply.png");
        Message source = text(30L, 7L, "@Bot 这个呢");
        source.setReplyToMessage(replied);
        when(visionService.isImageMessage(source)).thenReturn(false);
        when(visionService.isImageMessage(replied)).thenReturn(true);
        when(visionService.resolve(replied, true)).thenReturn(imageContext("reply.png"));

        BotVisionAttachmentSelector.Selection selected = selector.select(bot, 7L, source, source.getContent());
        assertEquals(29L, selected.messageId());
        assertEquals("reply_target", selected.reason());

        replied.setChatRoom(room(99L));
        when(messageRepository.findFileMessagesInChatRoom(eq(7L), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of()));
        selected = selector.select(bot, 7L, source, source.getContent());
        assertTrue(selected.image().attachments().isEmpty());
    }

    @Test
    void visualReferenceSelectsLatestPriorRoomImage() {
        Message source = text(50L, 7L, "@Bot 评价一下我的佳作");
        Message latest = image(49L, 7L, "latest.png");
        Message older = image(48L, 7L, "older.png");
        when(visionService.isImageMessage(source)).thenReturn(false);
        when(visionService.isImageMessage(latest)).thenReturn(true);
        when(visionService.isImageMessage(older)).thenReturn(true);
        when(messageRepository.findFileMessagesInChatRoom(eq(7L), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(latest, older)));
        when(visionService.resolve(latest, true)).thenReturn(imageContext("latest.png"));

        BotVisionAttachmentSelector.Selection selected = selector.select(bot, 7L, source, source.getContent());

        assertEquals(49L, selected.messageId());
        assertEquals("recent_room_image", selected.reason());
        assertFalse(selected.image().attachments().isEmpty());
    }

    @Test
    void ordinaryTextDoesNotSilentlyAttachRoomImage() {
        Message source = text(50L, 7L, "@Bot 今天天气怎么样");
        when(visionService.isImageMessage(source)).thenReturn(false);

        BotVisionAttachmentSelector.Selection selected = selector.select(bot, 7L, source, source.getContent());

        assertTrue(selected.image().attachments().isEmpty());
        verify(messageRepository, never()).findFileMessagesInChatRoom(any(), any(), any());
    }

    @Test
    void disabledVisionNeverReadsImageBytes() {
        bot.setVisionInputEnabled(false);
        Message source = image(30L, 7L, "private.png");

        BotVisionAttachmentSelector.Selection selected = selector.select(bot, 7L, source, "看图");

        assertTrue(selected.image().attachments().isEmpty());
        verify(visionService, never()).resolve(any(), eq(true));
    }

    private AgentVisionAttachmentService.ImageContext imageContext(String fileName) {
        BotDto.ImageAttachment attachment = new BotDto.ImageAttachment(
                fileName, "image/png", "data:image/png;base64,dGVzdA==");
        return new AgentVisionAttachmentService.ImageContext(
                List.of(attachment), "[图片: " + fileName + "]", false);
    }

    private Message image(Long id, Long roomId, String fileName) {
        Message message = text(id, roomId, fileName);
        message.setMessageType(Message.MessageType.IMAGE);
        message.setFileName(fileName);
        message.setFileUrl("/api/files/chat/" + fileName);
        message.setFileType("image/png");
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private Message text(Long id, Long roomId, String content) {
        Message message = new Message();
        message.setId(id);
        message.setChatRoom(room(roomId));
        message.setContent(content);
        message.setMessageType(Message.MessageType.TEXT);
        message.setIsDeleted(false);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private ChatRoom room(Long id) {
        ChatRoom room = new ChatRoom();
        room.setId(id);
        return room;
    }
}
