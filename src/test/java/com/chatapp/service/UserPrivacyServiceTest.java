package com.chatapp.service;

import com.chatapp.dto.MessageDto;
import com.chatapp.entity.Message;
import com.chatapp.entity.UserSettings;
import com.chatapp.repository.UserSettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserPrivacyServiceTest {

    private final UserSettingsRepository repository = mock(UserSettingsRepository.class);
    private final UserPrivacyService service = new UserPrivacyService(repository);

    @Test
    void usersWithoutSettingsRowGetDefaults() {
        when(repository.findByUserId(1L)).thenReturn(Optional.empty());

        assertFalse(service.hidesOnlineStatus(1L));
        assertFalse(service.readReceiptsDisabled(1L));
        assertFalse(service.rejectsFriendRequests(1L));
        assertFalse(service.rejectsDirectMessagesFromStrangers(1L));
        assertFalse(service.messageNotificationsDisabled(1L));
    }

    @Test
    void userAlwaysSeesOwnPresence() {
        UserSettings hidden = new UserSettings();
        hidden.setShowOnlineStatus(false);
        when(repository.findByUserId(1L)).thenReturn(Optional.of(hidden));

        assertFalse(service.hidesOnlineStatusFrom(1L, 1L));
        assertTrue(service.hidesOnlineStatusFrom(1L, 2L));
    }

    @Test
    void viewerWithReceiptsOffDoesNotSeeReadStateOfOwnMessages() {
        UserSettings off = new UserSettings();
        off.setReadReceiptsEnabled(false);
        when(repository.findByUserId(1L)).thenReturn(Optional.of(off));
        MessageDto mine = new MessageDto();
        mine.setSenderId(1L);
        mine.setMessageStatus(Message.MessageStatus.READ);
        mine.setReadCount(3);
        MessageDto theirs = new MessageDto();
        theirs.setSenderId(2L);
        theirs.setMessageStatus(Message.MessageStatus.READ);
        theirs.setReadCount(1);

        service.maskReadStateForViewer(List.of(mine, theirs), 1L);

        assertEquals(Message.MessageStatus.DELIVERED, mine.getMessageStatus());
        assertEquals(0, mine.getReadCount());
        assertEquals(Message.MessageStatus.READ, theirs.getMessageStatus());
    }
}
