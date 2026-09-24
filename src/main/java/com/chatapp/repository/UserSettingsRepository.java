package com.chatapp.repository;

import com.chatapp.entity.UserSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {
    Optional<UserSettings> findByUserId(Long userId);

    /**
     * 批量读取多个用户的设置，列表接口用来一次性解析头像框，避免逐个查询。
     */
    List<UserSettings> findByUserIdIn(Collection<Long> userIds);
}
