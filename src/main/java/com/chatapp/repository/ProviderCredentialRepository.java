package com.chatapp.repository;

import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ProviderCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProviderCredentialRepository extends JpaRepository<ProviderCredential, Long> {
    List<ProviderCredential> findByOwnerIdOrderByUpdatedAtDesc(Long ownerId);

    List<ProviderCredential> findByOwnerIdAndLlmProviderAndIsActiveTrueOrderByUpdatedAtDesc(
            Long ownerId,
            BotConfig.LLMProvider llmProvider);

    Optional<ProviderCredential> findByIdAndOwnerId(Long id, Long ownerId);
}
