package com.chatapp.repository;

import com.chatapp.entity.ChatRoomClearState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChatRoomClearStateRepository extends JpaRepository<ChatRoomClearState, Long> {
    Optional<ChatRoomClearState> findByUserIdAndChatRoomId(Long userId, Long chatRoomId);
}
