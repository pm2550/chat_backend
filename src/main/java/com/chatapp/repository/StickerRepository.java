package com.chatapp.repository;

import com.chatapp.entity.Sticker;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StickerRepository extends JpaRepository<Sticker, Long> {

    @EntityGraph(attributePaths = {"pack"})
    List<Sticker> findByPackIdOrderByIndexInPackAscIdAsc(Long packId);

    /**
     * 贴纸所在的包对 userId 可见（公开 / 自己的 / 已订阅）时才能拿来发送。
     */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM Sticker s " +
           "JOIN s.pack p LEFT JOIN p.ownerUser o " +
           "WHERE s.id = :stickerId AND (p.isPublic = true OR o.id = :userId " +
           "OR EXISTS (SELECT 1 FROM StickerPackSubscription sub WHERE sub.pack = p AND sub.user.id = :userId))")
    boolean isStickerVisibleToUser(@Param("stickerId") Long stickerId, @Param("userId") Long userId);
}
