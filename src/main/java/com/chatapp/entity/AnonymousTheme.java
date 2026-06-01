package com.chatapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "anonymous_themes")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnonymousTheme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "theme_key", nullable = false, unique = true, length = 50)
    private String themeKey;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "accent_color", length = 20)
    private String accentColor;

    @Column(name = "background_color", length = 20)
    private String backgroundColor;

    @Column(name = "message_color", length = 20)
    private String messageColor;

    @Column(name = "persona_prefix", length = 60)
    private String personaPrefix;

    @Column(name = "is_enabled")
    private Boolean isEnabled = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
