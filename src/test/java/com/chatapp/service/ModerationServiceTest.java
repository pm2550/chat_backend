package com.chatapp.service;

import com.chatapp.entity.ChatRoomBot;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModerationServiceTest {

    @Mock private ChatRoomBotRepository chatRoomBotRepository;
    @Mock private ChatRoomRepository chatRoomRepository;
    @InjectMocks private ModerationService service;

    private static ChatRoomBot binding(ChatRoomBot.ModerationGrant grant, boolean active) {
        ChatRoomBot b = new ChatRoomBot();
        b.setIsActive(active);
        b.setModerationGrant(grant);
        return b;
    }

    private void targetIsPlainMember() {
        when(chatRoomRepository.isMember(100L, 9L)).thenReturn(true);
        when(chatRoomRepository.isOwner(100L, 9L)).thenReturn(false);
        when(chatRoomRepository.isAdmin(100L, 9L)).thenReturn(false);
    }

    // ---- muteByBot ----

    @Test
    void muteWithGrantMutesNonPrivilegedMember() {
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 5L))
                .thenReturn(Optional.of(binding(ChatRoomBot.ModerationGrant.MUTE, true)));
        targetIsPlainMember();
        service.muteByBot(5L, 100L, 9L, true);
        verify(chatRoomRepository).setMemberMuted(100L, 9L, true);
    }

    @Test
    void muteWithoutGrantIsDenied() {
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 5L))
                .thenReturn(Optional.of(binding(ChatRoomBot.ModerationGrant.NONE, true)));
        assertThrows(AccessDeniedException.class, () -> service.muteByBot(5L, 100L, 9L, true));
        verify(chatRoomRepository, never()).setMemberMuted(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void muteCannotTargetAnAdmin() {
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 5L))
                .thenReturn(Optional.of(binding(ChatRoomBot.ModerationGrant.MUTE, true)));
        when(chatRoomRepository.isMember(100L, 9L)).thenReturn(true);
        when(chatRoomRepository.isOwner(100L, 9L)).thenReturn(false);
        when(chatRoomRepository.isAdmin(100L, 9L)).thenReturn(true);
        assertThrows(AccessDeniedException.class, () -> service.muteByBot(5L, 100L, 9L, true));
        verify(chatRoomRepository, never()).setMemberMuted(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void unboundBotIsDenied() {
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 5L)).thenReturn(Optional.empty());
        assertThrows(AccessDeniedException.class, () -> service.muteByBot(5L, 100L, 9L, true));
    }

    @Test
    void inactiveBindingIsDenied() {
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 5L))
                .thenReturn(Optional.of(binding(ChatRoomBot.ModerationGrant.KICK, false)));
        assertThrows(AccessDeniedException.class, () -> service.kickByBot(5L, 100L, 9L));
    }

    // ---- kickByBot (monotonic grant: KICK >= MUTE) ----

    @Test
    void muteGrantCannotKick() {
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 5L))
                .thenReturn(Optional.of(binding(ChatRoomBot.ModerationGrant.MUTE, true)));
        assertThrows(AccessDeniedException.class, () -> service.kickByBot(5L, 100L, 9L));
        verify(chatRoomRepository, never()).removeMember(anyLong(), anyLong());
    }

    @Test
    void kickGrantRemovesMember() {
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 5L))
                .thenReturn(Optional.of(binding(ChatRoomBot.ModerationGrant.KICK, true)));
        targetIsPlainMember();
        service.kickByBot(5L, 100L, 9L);
        verify(chatRoomRepository).removeMember(100L, 9L);
    }

    @Test
    void kickGrantImpliesMutePower() {
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 5L))
                .thenReturn(Optional.of(binding(ChatRoomBot.ModerationGrant.KICK, true)));
        targetIsPlainMember();
        service.muteByBot(5L, 100L, 9L, true); // KICK includes MUTE
        verify(chatRoomRepository).setMemberMuted(100L, 9L, true);
    }

    // ---- setBotModerationGrant (owner-only) ----

    @Test
    void ownerCanSetBotGrant() {
        ChatRoomBot b = binding(ChatRoomBot.ModerationGrant.NONE, true);
        when(chatRoomRepository.isOwner(100L, 1L)).thenReturn(true);
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 5L)).thenReturn(Optional.of(b));
        when(chatRoomBotRepository.save(any(ChatRoomBot.class))).thenAnswer(inv -> inv.getArgument(0));
        service.setBotModerationGrant(100L, 1L, 5L, ChatRoomBot.ModerationGrant.KICK);
        assertEquals(ChatRoomBot.ModerationGrant.KICK, b.getModerationGrant());
    }

    @Test
    void nonOwnerCannotSetBotGrant() {
        when(chatRoomRepository.isOwner(100L, 2L)).thenReturn(false);
        assertThrows(AccessDeniedException.class,
                () -> service.setBotModerationGrant(100L, 2L, 5L, ChatRoomBot.ModerationGrant.MUTE));
        verify(chatRoomBotRepository, never()).save(any());
    }
}
