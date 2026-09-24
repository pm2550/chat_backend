package com.chatapp.repository;

import com.chatapp.entity.E2eeIdentityKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface E2eeIdentityKeyRepository extends JpaRepository<E2eeIdentityKey, Long> {
    List<E2eeIdentityKey> findByUserIdOrderByKeyVersionAsc(Long userId);

    Optional<E2eeIdentityKey> findByUserIdAndKeyVersion(Long userId, Integer keyVersion);

    boolean existsByUserId(Long userId);
}
