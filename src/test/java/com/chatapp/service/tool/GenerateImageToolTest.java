package com.chatapp.service.tool;

import com.chatapp.dto.ImageGenerationDto;
import com.chatapp.dto.MessageDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomBot;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.ImageGenerationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerateImageToolTest {
    @Mock private ImageGenerationService imageGenerationService;
    @Mock private BotConfigRepository botConfigRepository;
    @Mock private ChatRoomBotRepository chatRoomBotRepository;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executesThroughImageGenerationServiceAsBot() throws Exception {
        GenerateImageTool tool = new GenerateImageTool(
                imageGenerationService,
                botConfigRepository,
                chatRoomBotRepository,
                chatRoomRepository,
                userRepository,
                objectMapper);

        User owner = new User();
        owner.setId(8L);
        owner.setUsername("owner");
        BotConfig bot = new BotConfig();
        bot.setId(5L);
        bot.setBotName("Painter");
        bot.setCreatedBy(owner);
        ChatRoom room = new ChatRoom();
        room.setId(20L);
        room.setCreatedBy(owner);
        ChatRoomBot roomBot = new ChatRoomBot();
        roomBot.setBotConfig(bot);
        roomBot.setChatRoom(room);
        roomBot.setRoomNickname("画图猫");

        when(botConfigRepository.findById(5L)).thenReturn(Optional.of(bot));
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(20L, 5L))
                .thenReturn(Optional.of(roomBot));
        when(chatRoomRepository.findById(20L)).thenReturn(Optional.of(room));
        when(userRepository.findById(8L)).thenReturn(Optional.of(owner));

        MessageDto message = new MessageDto();
        message.setId(99L);
        message.setChatRoomId(20L);
        message.setBotConfigId(5L);
        message.setBotName("画图猫");
        when(imageGenerationService.submitAsBot(
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(owner),
                org.mockito.ArgumentMatchers.eq(bot),
                org.mockito.ArgumentMatchers.eq("画图猫"),
                org.mockito.ArgumentMatchers.any(ImageGenerationDto.GenerateRequest.class)))
                .thenReturn(new ImageGenerationDto.GenerateResponse(
                        99L,
                        10,
                        Message.ImageGenerationStatus.QUEUED,
                        message));

        JsonNode params = objectMapper.readTree("""
                {"prompt":"画一只蓝色机器人","ratio":"16:9","expand":false}
                """);
        JsonNode result = tool.execute(params, new ToolContext(20L, 42L, 77L, 5L));

        assertThat(result.path("submitted").asBoolean()).isTrue();
        assertThat(result.path("messageId").asLong()).isEqualTo(99L);
        assertThat(result.path("botName").asText()).isEqualTo("画图猫");

        ArgumentCaptor<ImageGenerationDto.GenerateRequest> requestCaptor =
                ArgumentCaptor.forClass(ImageGenerationDto.GenerateRequest.class);
        verify(imageGenerationService).submitAsBot(
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(owner),
                org.mockito.ArgumentMatchers.eq(bot),
                org.mockito.ArgumentMatchers.eq("画图猫"),
                requestCaptor.capture());
        assertThat(requestCaptor.getValue().getRoomId()).isEqualTo(20L);
        assertThat(requestCaptor.getValue().getPrompt()).isEqualTo("画一只蓝色机器人");
        assertThat(requestCaptor.getValue().getSize()).isEqualTo("1792*1024");
        assertThat(requestCaptor.getValue().getExpand()).isFalse();
    }
}
