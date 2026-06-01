package com.chatapp.repository;

import com.chatapp.entity.PollVote;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PollVoteRepository extends JpaRepository<PollVote, Long> {
    @EntityGraph(attributePaths = {"user"})
    List<PollVote> findByPollId(Long pollId);
    Optional<PollVote> findByPollIdAndUserId(Long pollId, Long userId);
    void deleteByPollIdAndUserId(Long pollId, Long userId);
}
