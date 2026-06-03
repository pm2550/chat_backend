package com.chatapp.dto;

import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MessageDtoTest {

    @Test
    void botNamePrefersPersistedRoomDisplayName() {
        Message message = botMessage("Agent", "无敌高");

        MessageDto dto = MessageDto.fromEntity(message);

        assertEquals("无敌高", dto.getBotName());
    }

    @Test
    void botNameFallsBackToBotConfigNameForLegacyRows() {
        Message message = botMessage("Agent", null);

        MessageDto dto = MessageDto.fromEntity(message);

        assertEquals("Agent", dto.getBotName());
    }

    @Test
    void botDisplayNameTrimsBlankRoomNicknameAndFallsBack() {
        Message msg = botMessage("PrimaryBot", "   ");

        MessageDto dto = MessageDto.fromEntity(msg);

        assertEquals("PrimaryBot", dto.getBotName(),
                "blank/whitespace botDisplayName must fall back to botConfig.botName");
    }

    @Test
    void botDisplayNameIsIndependentPerRoomSnapshot() {
        Message msgA = botMessage("Echo", "无敌高");
        Message msgB = botMessage("Echo", "Echo");

        MessageDto dtoA = MessageDto.fromEntity(msgA);
        MessageDto dtoB = MessageDto.fromEntity(msgB);

        assertEquals("无敌高", dtoA.getBotName());
        assertEquals("Echo", dtoB.getBotName());
    }

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

    private Message botMessage(String botName, String botDisplayName) {
        User sender = new User();
        sender.setId(1L);
        sender.setUsername("admin");

        ChatRoom room = new ChatRoom();
        room.setId(42L);

        BotConfig bot = new BotConfig();
        bot.setId(7L);
        bot.setBotName(botName);

        Message message = new Message();
        message.setId(100L);
        message.setContent("hello");
        message.setSender(sender);
        message.setChatRoom(room);
        message.setBotConfig(bot);
        message.setBotDisplayName(botDisplayName);
        message.setMessageType(Message.MessageType.TEXT);
        message.setMessageStatus(Message.MessageStatus.SENT);
        return message;
    }
}
