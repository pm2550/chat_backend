package com.chatapp.repository;

import com.chatapp.entity.UserSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {
    Optional<UserSettings> findByUserId(Long userId);

    @Query("SELECT s.user.id FROM UserSettings s WHERE s.user.id IN :userIds AND s.showOnlineStatus = false")
    List<Long> findUserIdsHidingOnlineStatus(@Param("userIds") Collection<Long> userIds);

    @Query("SELECT s.user.id FROM UserSettings s WHERE s.user.id IN :userIds AND s.readReceiptsEnabled = false")
    List<Long> findUserIdsWithReadReceiptsDisabled(@Param("userIds") Collection<Long> userIds);

    @Query("SELECT s.user.id FROM UserSettings s WHERE s.user.id IN :userIds "
            + "AND s.messageNotificationsEnabled = false")
    List<Long> findUserIdsWithMessageNotificationsDisabled(@Param("userIds") Collection<Long> userIds);

    /**
     * 批量读取多个用户的设置，列表接口用来一次性解析头像框，避免逐个查询。
     */
    List<UserSettings> findByUserIdIn(Collection<Long> userIds);
}
