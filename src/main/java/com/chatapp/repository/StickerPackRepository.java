package com.chatapp.repository;

import com.chatapp.entity.StickerPack;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StickerPackRepository extends JpaRepository<StickerPack, Long> {

    @Query("SELECT DISTINCT p FROM StickerPack p LEFT JOIN StickerPackSubscription s ON s.pack = p " +
           "WHERE p.isPublic = true OR p.ownerUser.id = :userId OR s.user.id = :userId " +
           "ORDER BY p.createdAt DESC, p.id DESC")
    List<StickerPack> findAvailableForUser(@Param("userId") Long userId);
}
