package com.chatapp.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "workspace_files")
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = {"workspace", "folder", "createdBy", "lockedBy", "sourceBot", "deletedBy"})
@ToString(exclude = {"workspace", "folder", "createdBy", "lockedBy", "sourceBot", "deletedBy"})
public class WorkspaceFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    private WorkspaceFolder folder;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "current_storage_name", nullable = false, length = 255)
    private String currentStorageName;

    @Column(name = "mime_type", length = 160)
    private String mimeType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "current_version")
    private Integer currentVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType = SourceType.USER;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_bot_id")
    private BotConfig sourceBot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "is_deleted")
    private Boolean isDeleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private User deletedBy;

    @Column(name = "is_locked")
    private Boolean isLocked = false;

    @Column(name = "lock_reason", length = 500)
    private String lockReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "locked_by")
    private User lockedBy;

    @Column(name = "bot_access_enabled")
    private Boolean botAccessEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status")
    private ScanStatus scanStatus = ScanStatus.CLEAN;

    @Column(name = "scan_summary", length = 500)
    private String scanSummary;

    @Column(name = "scanned_at")
    private LocalDateTime scannedAt;

    @Column(name = "storage_provider", length = 40)
    private String storageProvider = "LOCAL";

    @Column(name = "object_key", length = 500)
    private String objectKey;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum SourceType {
        USER("用户上传"),
        BOT("Bot 提交"),
        SERVICE("服务生成");

        private final String description;

        SourceType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public enum ScanStatus {
        PENDING("等待扫描"),
        CLEAN("已通过"),
        BLOCKED("已拦截"),
        FAILED("扫描失败");

        private final String description;

        ScanStatus(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
