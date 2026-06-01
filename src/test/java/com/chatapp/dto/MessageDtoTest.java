package com.chatapp.dto;

import com.chatapp.entity.Message;
import com.chatapp.entity.BotConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MessageDtoTest {

    @Test
    void fromEntityParsesStoredLinkPreviewJson() {
        Message message = new Message();
        message.setId(7L);
        message.setContent("https://example.com/post");
        message.setMessageType(Message.MessageType.TEXT);
        message.setMessageStatus(Message.MessageStatus.SENT);
        message.setLinkPreviewJson("""
                {
                  "url": "https://example.com/post",
                  "title": "Example",
                  "description": "Description",
                  "imageUrl": "https://example.com/cover.png",
                  "siteName": "example.com",
                  "faviconUrl": "https://example.com/favicon.ico"
                }
                """);

        MessageDto dto = MessageDto.fromEntity(message);

        assertNotNull(dto.getLinkPreview());
        assertEquals("Example", dto.getLinkPreview().getTitle());
        assertEquals("https://example.com/favicon.ico", dto.getLinkPreview().getFaviconUrl());
    }

    @Test
    void fromEntitySerializesBotIdentity() {
        BotConfig bot = new BotConfig();
        bot.setId(42L);
        bot.setBotName("HelperBot");
        bot.setBotAvatar("/api/files/avatar/bot.png");

        Message message = new Message();
        message.setId(8L);
        message.setContent("bot answer");
        message.setMessageType(Message.MessageType.TEXT);
        message.setMessageStatus(Message.MessageStatus.SENT);
        message.setBotConfig(bot);

        MessageDto dto = MessageDto.fromEntity(message);

        assertEquals(42L, dto.getBotConfigId());
        assertEquals(42L, dto.getBotSenderId());
        assertEquals("HelperBot", dto.getBotName());
        assertEquals("/api/files/avatar/bot.png", dto.getBotAvatar());
        assertEquals("bot answer", dto.getContent());
    }

    @Test
    void fromEntitySerializesSystemAgentReplyWithoutTextPrefix() {
        BotConfig bot = new BotConfig();
        bot.setId(99L);
        bot.setBotName("Agent");
        bot.setBotAvatar("/assets/agent-avatar.png");

        Message message = new Message();
        message.setId(9L);
        message.setContent("任务已接收: summarize this room");
        message.setMessageType(Message.MessageType.SYSTEM);
        message.setMessageStatus(Message.MessageStatus.SENT);
        message.setBotConfig(bot);

        MessageDto dto = MessageDto.fromEntity(message);

        assertEquals(99L, dto.getBotConfigId());
        assertEquals(99L, dto.getBotSenderId());
        assertEquals("Agent", dto.getBotName());
        assertEquals("/assets/agent-avatar.png", dto.getBotAvatar());
        assertFalse(dto.getContent().startsWith("[Agent] "));
    }
}
