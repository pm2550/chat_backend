package com.chatapp.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 用户的端到端加密开关和当前密钥版本。
 *
 * <p>{@code enabled=false} 只表示"新消息不再加密"：密钥保留，已经加密的历史照样能读。
 * 私聊双方都开着、并且都有当前密钥时，客户端才会加密。</p>
 */
@Entity
@Table(name = "e2ee_user_states")
@Data
@NoArgsConstructor
public class E2eeUserState {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = false;

    @Column(name = "active_key_version")
    private Integer activeKeyVersion;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
