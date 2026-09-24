package com.chatapp.repository;

import com.chatapp.entity.StickerPack;
import org.springframework.data.domain.Pageable;
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

    /**
     * 可见规则和 findAvailableForUser 保持一致：公开包、自己上传的包、已订阅的包。
     */
    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM StickerPack p LEFT JOIN p.ownerUser o " +
           "WHERE p.id = :packId AND (p.isPublic = true OR o.id = :userId " +
           "OR EXISTS (SELECT 1 FROM StickerPackSubscription sub WHERE sub.pack = p AND sub.user.id = :userId))")
    boolean isVisibleToUser(@Param("packId") Long packId, @Param("userId") Long userId);

    /**
     * 找出把 url 用作封面或贴纸图、并且对 userId 可见的贴纸包 id。
     */
    @Query("SELECT p.id FROM StickerPack p LEFT JOIN p.ownerUser o " +
           "WHERE (p.coverUrl = :url OR EXISTS (SELECT 1 FROM Sticker s WHERE s.pack = p AND s.url = :url)) " +
           "AND (p.isPublic = true OR o.id = :userId " +
           "OR EXISTS (SELECT 1 FROM StickerPackSubscription sub WHERE sub.pack = p AND sub.user.id = :userId)) " +
           "ORDER BY p.id ASC")
    List<Long> findVisiblePackIdsReferencingUrl(@Param("url") String url,
                                                @Param("userId") Long userId,
                                                Pageable pageable);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM StickerPack p " +
           "WHERE p.coverUrl = :url OR EXISTS (SELECT 1 FROM Sticker s WHERE s.pack = p AND s.url = :url)")
    boolean existsReferencingUrl(@Param("url") String url);
}
