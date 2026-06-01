package com.chatapp.dto;

import com.chatapp.entity.Message;
import com.chatapp.entity.BotConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
