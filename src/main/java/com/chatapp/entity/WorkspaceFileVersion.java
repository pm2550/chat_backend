package com.chatapp.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "workspace_file_versions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"file_id", "version_number"})
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = {"file", "uploadedBy", "uploadedByBot"})
@ToString(exclude = {"file", "uploadedBy", "uploadedByBot"})
public class WorkspaceFileVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", nullable = false)
    private WorkspaceFile file;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "storage_name", nullable = false, length = 255)
    private String storageName;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "mime_type", length = 160)
    private String mimeType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(name = "version_note", length = 500)
    private String versionNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status")
    private WorkspaceFile.ScanStatus scanStatus = WorkspaceFile.ScanStatus.CLEAN;

    @Column(name = "scan_summary", length = 500)
    private String scanSummary;

    @Column(name = "scanned_at")
    private LocalDateTime scannedAt;

    @Column(name = "storage_provider", length = 40)
    private String storageProvider = "LOCAL";

    @Column(name = "object_key", length = 500)
    private String objectKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_bot")
    private BotConfig uploadedByBot;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
