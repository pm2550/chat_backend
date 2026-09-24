package com.chatapp.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 端到端加密的长期身份密钥（X25519），每个用户每个版本一行。
 *
 * <p>公钥是公开信息，任何人都能取来给这个用户加密。私钥只以"密文"形式存在这里：
 * 客户端用登录密码经 Argon2id（独立的盐，和登录用的 clientHash 不是同一个值）派生出
 * 包装密钥，再用 AES-256-GCM 把私钥包起来上传。服务器拿不到密码，也就解不开私钥——
 * 只是替用户保管，让手机、网页、电脑登录后都能取回同一把私钥读同一份历史。</p>
 *
 * <p>另外可以有一份"恢复码包装"（见 {@link #recoveryWrappedPrivateKey}），忘记密码时用。</p>
 *
 * <p>旧版本的密钥不删：对方要靠它的公钥校验以前的消息，自己也要靠它的私钥读以前的消息。
 * 当前用哪个版本由 {@link E2eeUserState#getActiveKeyVersion()} 决定。</p>
 */
@Entity
@Table(name = "e2ee_identity_keys",
        uniqueConstraints = @UniqueConstraint(name = "uk_e2ee_identity_keys_user_version",
                columnNames = {"user_id", "key_version"}))
@Data
@NoArgsConstructor
public class E2eeIdentityKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "key_version", nullable = false)
    private Integer keyVersion;

    /** X25519 公钥，base64（32 字节）。 */
    @Column(name = "public_key", nullable = false, length = 64)
    private String publicKey;

    /** base64(iv || AES-256-GCM(私钥))，服务器无法解开。 */
    @Column(name = "wrapped_private_key", nullable = false, length = 255)
    private String wrappedPrivateKey;

    /** 派生包装密钥用的 Argon2id 盐，base64。 */
    @Column(name = "wrap_salt", nullable = false, length = 64)
    private String wrapSalt;

    /** Argon2id 参数，格式同登录：m=...,t=...,p=...,v=...,hashLen=... */
    @Column(name = "wrap_params", nullable = false, length = 100)
    private String wrapParams;

    /**
     * 同一把私钥的第二份包装：用恢复码经 HKDF-SHA256 派生的密钥做 AES-256-GCM，
     * base64(iv || 密文)。恢复码只在客户端生成、显示给用户一次，服务器从来拿不到。
     * 忘记密码（或被管理员重置）后，用户输入恢复码在本机解开，再用新密码重新包装。
     * 没设置过恢复码时为空。
     */
    @Column(name = "recovery_wrapped_private_key", length = 255)
    private String recoveryWrappedPrivateKey;

    /** 恢复码派生用的盐，base64。同一个恢复码包装的各个版本共用一个盐。 */
    @Column(name = "recovery_wrap_salt", length = 64)
    private String recoveryWrapSalt;

    /** 恢复码派生参数，目前固定为 hkdf-sha256,v=1。 */
    @Column(name = "recovery_wrap_params", length = 100)
    private String recoveryWrapParams;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
