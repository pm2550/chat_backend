package com.chatapp.repository;

import com.chatapp.entity.AnonymousIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface AnonymousIdentityRepository extends JpaRepository<AnonymousIdentity, Long> {

    Optional<AnonymousIdentity> findByUserIdAndChatRoomIdAndAssignedDate(
            Long userId, Long chatRoomId, LocalDate assignedDate);

    @Query("SELECT COUNT(DISTINCT a.anonymousName) FROM AnonymousIdentity a " +
           "WHERE a.chatRoom.id = :chatRoomId AND a.assignedDate = :date")
    long countDistinctNamesInRoomOnDate(@Param("chatRoomId") Long chatRoomId, @Param("date") LocalDate date);

    @Modifying
    @Query("DELETE FROM AnonymousIdentity a WHERE a.assignedDate < :date")
    int deleteOldIdentities(@Param("date") LocalDate date);
}
