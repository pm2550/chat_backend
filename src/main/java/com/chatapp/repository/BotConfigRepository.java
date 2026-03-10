package com.chatapp.repository;

import com.chatapp.entity.BotConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BotConfigRepository extends JpaRepository<BotConfig, Long> {
    List<BotConfig> findByCreatedById(Long userId);
    List<BotConfig> findByIsActiveTrue();
}
