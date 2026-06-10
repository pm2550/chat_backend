package com.chatapp.repository;

import com.chatapp.entity.ContactGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContactGroupRepository extends JpaRepository<ContactGroup, Long> {

    List<ContactGroup> findByUserIdOrderBySortOrderAscNameAsc(Long userId);

    Optional<ContactGroup> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndName(Long userId, String name);

    @Query("SELECT COALESCE(MAX(g.sortOrder), -1) FROM ContactGroup g WHERE g.user.id = :userId")
    int maxSortOrderForUser(@Param("userId") Long userId);
}
