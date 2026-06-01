package com.chatapp.repository;

import com.chatapp.entity.BotConfig;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BotConfigRepository extends JpaRepository<BotConfig, Long> {
    @Override
    @EntityGraph(attributePaths = {"providerCredential"})
    Optional<BotConfig> findById(Long id);

    @EntityGraph(attributePaths = {"providerCredential"})
    List<BotConfig> findByCreatedById(Long userId);

    @EntityGraph(attributePaths = {"providerCredential"})
    List<BotConfig> findByIsActiveTrue();

    @EntityGraph(attributePaths = {"providerCredential"})
    Optional<BotConfig> findFirstByBotNameAndCreatedByIsNullOrderByIdAsc(String botName);
}
