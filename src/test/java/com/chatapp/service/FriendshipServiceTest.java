package com.chatapp.service;

import com.chatapp.entity.Friendship;
import com.chatapp.entity.User;
import com.chatapp.repository.FriendshipRepository;
import com.chatapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FriendshipService")
class FriendshipServiceTest {

    @Mock private FriendshipRepository friendshipRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private FriendshipService service;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = new User();
        alice.setId(1L);
        alice.setUsername("alice");
        alice.setDisplayName("Alice");
        bob = new User();
        bob.setId(2L);
        bob.setUsername("bob");
        bob.setDisplayName("Bob");
    }

    @Test
    @DisplayName("sendFriendRequest creates PENDING when no prior link")
    void send_creates_pending() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.findById(2L)).thenReturn(Optional.of(bob));
        when(friendshipRepository.areFriends(1L, 2L)).thenReturn(false);
        when(friendshipRepository.hasPendingRequest(1L, 2L)).thenReturn(false);
        when(friendshipRepository.hasPendingRequest(2L, 1L)).thenReturn(false);
        when(friendshipRepository.save(any(Friendship.class))).thenAnswer(inv -> inv.getArgument(0));

        Friendship result = service.sendFriendRequest(1L, 2L);

        assertEquals(Friendship.FriendshipStatus.PENDING, result.getStatus());
        assertEquals(alice, result.getUser());
        assertEquals(bob, result.getFriend());
    }

    @Test
    @DisplayName("sendFriendRequest rejects adding self")
    void send_self_forbidden() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.sendFriendRequest(1L, 1L));
        assertTrue(ex.getMessage().contains("自己"));
    }

    @Test
    @DisplayName("sendFriendRequest rejects when already friends")
    void send_already_friends() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.findById(2L)).thenReturn(Optional.of(bob));
        when(friendshipRepository.areFriends(1L, 2L)).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.sendFriendRequest(1L, 2L));
    }

    @Test
    @DisplayName("sendFriendRequest auto-accepts when counterparty already requested")
    void send_auto_accept_reverse_pending() {
        // bob→alice request already exists
        Friendship pending = new Friendship(bob, alice);
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.findById(2L)).thenReturn(Optional.of(bob));
        when(friendshipRepository.areFriends(1L, 2L)).thenReturn(false);
        when(friendshipRepository.hasPendingRequest(1L, 2L)).thenReturn(false);
        when(friendshipRepository.hasPendingRequest(2L, 1L)).thenReturn(true);
        // service then calls acceptFriendRequest(userId=1, friendId=2)
        // which looks up findFriendshipBetween(1, 2)
        when(friendshipRepository.findFriendshipBetween(1L, 2L)).thenReturn(Optional.of(pending));
        when(friendshipRepository.save(any(Friendship.class))).thenAnswer(inv -> inv.getArgument(0));

        Friendship result = service.sendFriendRequest(1L, 2L);
        assertEquals(Friendship.FriendshipStatus.ACCEPTED, result.getStatus());
    }

    @Test
    @DisplayName("acceptFriendRequest only works for receiver")
    void accept_wrong_receiver_rejected() {
        Friendship pending = new Friendship(alice, bob);
        when(friendshipRepository.findFriendshipBetween(1L, 2L)).thenReturn(Optional.of(pending));
        // alice trying to accept her own outgoing request
        assertThrows(IllegalArgumentException.class,
                () -> service.acceptFriendRequest(1L, 2L));
    }

    @Test
    @DisplayName("acceptFriendRequest succeeds for receiver")
    void accept_success() {
        Friendship pending = new Friendship(alice, bob);
        when(friendshipRepository.findFriendshipBetween(2L, 1L)).thenReturn(Optional.of(pending));
        when(friendshipRepository.save(any(Friendship.class))).thenAnswer(inv -> inv.getArgument(0));

        Friendship result = service.acceptFriendRequest(2L, 1L);
        assertEquals(Friendship.FriendshipStatus.ACCEPTED, result.getStatus());
        assertNotNull(result.getAcceptedAt());
    }

    @Test
    @DisplayName("declineFriendRequest flips state to DECLINED")
    void decline_success() {
        Friendship pending = new Friendship(alice, bob);
        when(friendshipRepository.findFriendshipBetween(2L, 1L)).thenReturn(Optional.of(pending));

        service.declineFriendRequest(2L, 1L);
        assertEquals(Friendship.FriendshipStatus.DECLINED, pending.getStatus());
    }

    @Test
    @DisplayName("removeFriend deletes only when already friends")
    void remove_requires_accepted() {
        Friendship accepted = new Friendship(alice, bob);
        accepted.accept();
        when(friendshipRepository.findFriendshipBetween(1L, 2L)).thenReturn(Optional.of(accepted));
        when(friendshipRepository.areFriends(1L, 2L)).thenReturn(true);

        service.removeFriend(1L, 2L);
        verify(friendshipRepository).delete(accepted);
    }

    @Test
    @DisplayName("blockUser creates friendship when none exists")
    void block_creates_new() {
        when(friendshipRepository.findFriendshipBetween(1L, 2L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.findById(2L)).thenReturn(Optional.of(bob));
        when(friendshipRepository.save(any(Friendship.class))).thenAnswer(inv -> inv.getArgument(0));

        service.blockUser(1L, 2L);

        ArgumentCaptor<Friendship> captor = ArgumentCaptor.forClass(Friendship.class);
        verify(friendshipRepository).save(captor.capture());
        assertEquals(Friendship.FriendshipStatus.BLOCKED, captor.getValue().getStatus());
    }

    @Test
    @DisplayName("unblockUser clears block state")
    void unblock_clears() {
        Friendship blocked = new Friendship(alice, bob);
        blocked.block();
        when(friendshipRepository.findFriendshipBetween(1L, 2L)).thenReturn(Optional.of(blocked));
        when(friendshipRepository.save(any(Friendship.class))).thenAnswer(inv -> inv.getArgument(0));

        service.unblockUser(1L, 2L);
        assertFalse(blocked.getIsBlocked());
        assertEquals(Friendship.FriendshipStatus.DECLINED, blocked.getStatus());
    }

    @Test
    @DisplayName("getFriends returns counterparty users")
    void get_friends_maps_counterparty() {
        Friendship f1 = new Friendship(alice, bob);
        f1.accept();
        when(friendshipRepository.findAcceptedFriendsByUserId(1L)).thenReturn(List.of(f1));
        List<User> friends = service.getFriends(1L);
        assertEquals(1, friends.size());
        assertEquals(bob, friends.get(0));
    }

    @Test
    @DisplayName("getFriendCount delegates to repo")
    void count_delegates() {
        when(friendshipRepository.countFriendsByUserId(1L)).thenReturn(7L);
        assertEquals(7L, service.getFriendCount(1L));
    }

    @Test
    @DisplayName("areFriends delegates to repo")
    void are_friends_delegates() {
        when(friendshipRepository.areFriends(1L, 2L)).thenReturn(true);
        assertTrue(service.areFriends(1L, 2L));
    }

    @Test
    @DisplayName("togglePinFriend flips pinned flag")
    void toggle_pin() {
        Friendship f = new Friendship(alice, bob);
        f.accept();
        f.setIsPinned(false);
        when(friendshipRepository.findFriendshipBetween(1L, 2L)).thenReturn(Optional.of(f));
        when(friendshipRepository.areFriends(1L, 2L)).thenReturn(true);

        service.togglePinFriend(1L, 2L);
        assertTrue(f.getIsPinned());
        service.togglePinFriend(1L, 2L);
        assertFalse(f.getIsPinned());
    }

    @Test
    @DisplayName("setFriendAlias rejects non-friends")
    void set_alias_requires_friend() {
        Friendship f = new Friendship(alice, bob);
        when(friendshipRepository.findFriendshipBetween(1L, 2L)).thenReturn(Optional.of(f));
        when(friendshipRepository.areFriends(1L, 2L)).thenReturn(false);
        assertThrows(IllegalArgumentException.class,
                () -> service.setFriendAlias(1L, 2L, "Bobby"));
    }
}
