package com.chatapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "message_notifications_enabled")
    private Boolean messageNotificationsEnabled = true;

    @Column(name = "show_online_status")
    private Boolean showOnlineStatus = true;

    @Column(name = "allow_friend_requests")
    private Boolean allowFriendRequests = true;

    @Column(name = "allow_direct_messages")
    private Boolean allowDirectMessages = true;

    @Column(name = "read_receipts_enabled")
    private Boolean readReceiptsEnabled = true;

    @Column(name = "chat_background_preset", length = 50)
    private String chatBackgroundPreset = "cloud_gradient";

    @Column(name = "chat_background_custom_url", length = 500)
    private String chatBackgroundCustomUrl;

    @Column(name = "avatar_frame_preset", length = 50)
    private String avatarFramePreset = "none";

    @Column(name = "bubble_style_preset", length = 50)
    private String bubbleStylePreset = "default_gradient";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
