package com.chatapp.repository;

import com.chatapp.entity.UserKeyBundle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserKeyBundleRepository extends JpaRepository<UserKeyBundle, Long> {
    Optional<UserKeyBundle> findByUserId(Long userId);
    boolean existsByUserId(Long userId);
    void deleteByUserId(Long userId);
}
