package com.chatapp.controller;

import com.chatapp.entity.User;
import com.chatapp.exception.GlobalExceptionHandler;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.BotImageGenerationClient;
import com.chatapp.service.E2eeKeyService;
import com.chatapp.service.FileStorageService;
import com.chatapp.service.ImageGenerationClient;
import com.chatapp.service.ImageGenerationService;
import com.chatapp.service.MessageService;
import com.chatapp.service.PointsService;
import com.chatapp.service.UserService;
import com.chatapp.websocket.RawWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ImageGenerationControllerTest {

    @Mock private MessageRepository messageRepository;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private UserRepository userRepository;
    @Mock private MessageService messageService;
    @Mock private PointsService pointsService;
    @Mock private ImageGenerationClient generationClient;
    @Mock private BotImageGenerationClient botImageGenerationClient;
    @Mock private FileStorageService fileStorageService;
    @Mock private RawWebSocketHandler rawWebSocketHandler;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private E2eeKeyService e2eeKeyService;
    @Mock private UserService userService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ImageGenerationService service = new ImageGenerationService(
                messageRepository, chatRoomRepository, userRepository, messageService, pointsService,
                generationClient, botImageGenerationClient, fileStorageService, rawWebSocketHandler,
                transactionTemplate, Runnable::run, e2eeKeyService);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ImageGenerationController(service, userService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void unknownPromptHelperLevelIsA400WithAChineseReason() throws Exception {
        User alice = new User();
        alice.setId(1L);
        alice.setUsername("alice");
        when(userService.findUserByUsername("alice")).thenReturn(alice);
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));

        mockMvc.perform(post("/api/v1/images/generate")
                        .principal(new UsernamePasswordAuthenticationToken("alice", null, List.of()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roomId", 10,
                                "prompt", "一只橘猫",
                                "promptHelper", "high"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "扩写档位只能是 off（关闭）、low（仅翻译）或 medium（创意扩写）"));

        verifyNoInteractions(messageRepository, pointsService, generationClient);
    }
}
