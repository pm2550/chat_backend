package com.chatapp.service;

import com.chatapp.entity.User;
import com.chatapp.entity.UserSettings;
import com.chatapp.repository.UserRepository;
import com.chatapp.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 在线时切换"显示在线状态"要通知 WebSocket 层立即广播。 */
@ExtendWith(MockitoExtension.class)
class UserProfileServiceSettingsEventTest {

    @Mock private UserRepository userRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private UserSettingsRepository userSettingsRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private UserProfileService service;

    private UserSettings settings;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "eventPublisher", eventPublisher);
        settings = new UserSettings();
        settings.setUser(new User());
        settings.setShowOnlineStatus(true);
        when(userSettingsRepository.findByUserId(1L)).thenReturn(Optional.of(settings));
        when(userSettingsRepository.save(any(UserSettings.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void hidingOnlineStatusPublishesVisibilityChange() {
        UserProfileService.UserSettingsUpdateRequest request = new UserProfileService.UserSettingsUpdateRequest();
        request.setShowOnlineStatus(false);

        service.updateSettings(1L, request);

        verify(eventPublisher).publishEvent(new UserPrivacyService.PresenceVisibilityChanged(1L, false));
    }

    @Test
    void savingUnchangedValueDoesNotPublish() {
        UserProfileService.UserSettingsUpdateRequest request = new UserProfileService.UserSettingsUpdateRequest();
        request.setShowOnlineStatus(true);
        request.setReadReceiptsEnabled(false);

        service.updateSettings(1L, request);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }
}
