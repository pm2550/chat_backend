package com.chatapp.repository;

import com.chatapp.entity.Poll;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PollRepository extends JpaRepository<Poll, Long> {
    @EntityGraph(attributePaths = {"message", "createdBy"})
    Optional<Poll> findByMessageId(Long messageId);
}
