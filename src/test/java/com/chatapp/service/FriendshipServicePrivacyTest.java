package com.chatapp.service;

import com.chatapp.entity.Friendship;
import com.chatapp.entity.User;
import com.chatapp.repository.FriendshipRepository;
import com.chatapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** "允许好友请求"关闭后，别人发不了好友请求。 */
@ExtendWith(MockitoExtension.class)
class FriendshipServicePrivacyTest {

    @Mock private FriendshipRepository friendshipRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserPrivacyService userPrivacyService;

    @InjectMocks private FriendshipService service;

    @BeforeEach
    void setUp() {
        User alice = new User();
        alice.setId(1L);
        alice.setUsername("alice");
        User bob = new User();
        bob.setId(2L);
        bob.setUsername("bob");
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.findById(2L)).thenReturn(Optional.of(bob));
    }

    @Test
    void requestIsRejectedWhenTargetTurnedOffFriendRequests() {
        when(friendshipRepository.findDirectFriendship(1L, 2L)).thenReturn(Optional.empty());
        when(userPrivacyService.rejectsFriendRequests(2L)).thenReturn(true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.sendFriendRequest(1L, 2L));

        assertEquals("对方已关闭好友请求，暂时无法添加", error.getMessage());
        verify(friendshipRepository, never()).save(any(Friendship.class));
    }

    @Test
    void requestFromSomeoneTheTargetAlreadyAskedIsStillAccepted() {
        // bob 先向 alice 发了请求；bob 关掉"允许好友请求"不影响 alice 接受。
        when(friendshipRepository.hasPendingRequest(1L, 2L)).thenReturn(false);
        when(friendshipRepository.hasPendingRequest(2L, 1L)).thenReturn(true);
        Friendship pending = new Friendship();
        User alice = userRepository.findById(1L).orElseThrow();
        User bob = userRepository.findById(2L).orElseThrow();
        pending.setUser(bob);
        pending.setFriend(alice);
        pending.setStatus(Friendship.FriendshipStatus.PENDING);
        when(friendshipRepository.findPendingRequestFromSenderToReceiver(2L, 1L)).thenReturn(Optional.of(pending));
        when(friendshipRepository.save(any(Friendship.class))).thenAnswer(inv -> inv.getArgument(0));

        Friendship result = service.sendFriendRequest(1L, 2L);

        assertEquals(Friendship.FriendshipStatus.ACCEPTED, result.getStatus());
        verify(userPrivacyService, never()).rejectsFriendRequests(any());
    }
}
