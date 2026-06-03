package com.chatapp.repository;

import com.chatapp.entity.ChatRoomBot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatRoomBotRepository extends JpaRepository<ChatRoomBot, Long> {

    List<ChatRoomBot> findByChatRoomIdAndIsActiveTrue(Long chatRoomId);

    Optional<ChatRoomBot> findByChatRoomIdAndBotConfigId(Long chatRoomId, Long botConfigId);

    List<ChatRoomBot> findByBotConfigIdAndIsActiveTrue(Long botConfigId);

    @Query("SELECT crb FROM ChatRoomBot crb JOIN FETCH crb.botConfig bc " +
           "LEFT JOIN FETCH bc.providerCredential " +
           "WHERE crb.chatRoom.id = :chatRoomId AND crb.isActive = true")
    List<ChatRoomBot> findActiveBotsWithConfig(@Param("chatRoomId") Long chatRoomId);

    void deleteByChatRoomIdAndBotConfigId(Long chatRoomId, Long botConfigId);
}
