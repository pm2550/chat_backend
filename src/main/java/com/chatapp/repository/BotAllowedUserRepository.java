package com.chatapp.repository;

import com.chatapp.entity.BotAllowedUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BotAllowedUserRepository extends JpaRepository<BotAllowedUser, Long> {

    List<BotAllowedUser> findByBotConfigIdOrderByUserUsernameAsc(Long botConfigId);

    boolean existsByBotConfigIdAndUserId(Long botConfigId, Long userId);

    void deleteByBotConfigId(Long botConfigId);
}
