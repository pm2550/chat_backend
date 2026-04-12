package com.chatapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "app_versions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"platform", "version_code"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceToken.Platform platform;

    @Column(name = "version_name", nullable = false, length = 50)
    private String versionName;

    @Column(name = "version_code", nullable = false)
    private Integer versionCode;

    @Column(name = "force_update", nullable = false)
    private Boolean forceUpdate = false;

    @Column(name = "release_notes", columnDefinition = "TEXT")
    private String releaseNotes;

    @Column(name = "download_url", length = 500)
    private String downloadUrl;

    @Column(name = "artifact_filename", length = 255)
    private String artifactFilename;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "published_by")
    private User publishedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
