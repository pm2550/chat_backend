package com.chatapp.repository;

import com.chatapp.entity.ContactGroupItem;
import com.chatapp.entity.ContactGroupItem.TargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContactGroupItemRepository extends JpaRepository<ContactGroupItem, Long> {

    List<ContactGroupItem> findByUserId(Long userId);

    Optional<ContactGroupItem> findByUserIdAndTargetTypeAndTargetId(
            Long userId,
            TargetType targetType,
            Long targetId);

    void deleteByGroupIdAndUserId(Long groupId, Long userId);

    void deleteByUserIdAndTargetTypeAndTargetId(Long userId, TargetType targetType, Long targetId);
}
