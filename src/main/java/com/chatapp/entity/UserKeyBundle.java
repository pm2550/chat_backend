package com.chatapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_key_bundles")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserKeyBundle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "identity_public_key", nullable = false, columnDefinition = "TEXT")
    private String identityPublicKey;

    @Column(name = "signed_pre_key", nullable = false, columnDefinition = "TEXT")
    private String signedPreKey;

    @Column(name = "signed_pre_key_signature", nullable = false, columnDefinition = "TEXT")
    private String signedPreKeySignature;

    @Column(name = "one_time_pre_keys", columnDefinition = "TEXT")
    private String oneTimePreKeys;

    @Column(name = "key_version")
    private Integer keyVersion = 1;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
