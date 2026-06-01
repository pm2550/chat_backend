package com.chatapp.repository;

import com.chatapp.entity.AgentTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"chatRoom", "requestedBy", "botConfig", "resultMessage", "resultMessage.sender", "resultMessage.botConfig", "resultMessage.anonymousIdentity", "resultMessage.replyToMessage", "resultMessage.replyToMessage.sender", "resultMessage.replyToMessage.anonymousIdentity"})
    Page<AgentTask> findByChatRoomIdOrderByCreatedAtDesc(Long chatRoomId, Pageable pageable);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"chatRoom", "requestedBy", "botConfig", "resultMessage", "resultMessage.sender", "resultMessage.botConfig", "resultMessage.anonymousIdentity", "resultMessage.replyToMessage", "resultMessage.replyToMessage.sender", "resultMessage.replyToMessage.anonymousIdentity"})
    @Query("SELECT t FROM AgentTask t WHERE t.id = :id")
    Optional<AgentTask> findWithDetailsById(@Param("id") Long id);
}
